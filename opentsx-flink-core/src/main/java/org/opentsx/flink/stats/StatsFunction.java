package org.opentsx.flink.stats;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.TimeDomain;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;

import java.util.List;

/**
 * Runs one {@link StatsEngine} per series id, the same calls as the KafScale processor makes.
 *
 * <ul>
 *   <li>Every input goes to {@link StatsEngine#onEpisode} or {@link StatsEngine#onObservation}; the windows it
 *       closes are emitted.</li>
 *   <li>GLOBAL: a processing-time timer emits {@link StatsEngine#emitGlobal()} every {@code globalEveryMs}
 *       (0 = only at the end of a bounded run).</li>
 *   <li>End of a bounded run: an event-time timer at {@link Long#MAX_VALUE} fires with the final watermark and
 *       calls {@link StatsEngine#flush()}. The job emits no other watermarks, because the engine keeps event time
 *       per series itself.</li>
 * </ul>
 */
public final class StatsFunction extends KeyedProcessFunction<String, StatsInput, StatsRecord> {

    private static final long serialVersionUID = 1L;

    private final EngineSpec spec;
    private final long globalEveryMs;

    private transient ValueState<StatsEngine> engine;
    private transient ValueState<Long> nextGlobal;
    private transient OpenTsxAvro avro;
    private transient Counter undecodable;

    public StatsFunction(EngineSpec spec, long globalEveryMs) {
        if (globalEveryMs < 0) {
            throw new IllegalArgumentException("globalEveryMs must not be negative");
        }
        this.spec = spec;
        this.globalEveryMs = globalEveryMs;
    }

    @Override
    public void open(OpenContext openContext) {
        engine = getRuntimeContext().getState(new ValueStateDescriptor<>("engine", new StatsEngineSerializer(spec)));
        nextGlobal = getRuntimeContext().getState(new ValueStateDescriptor<>("next-global", Types.LONG));
        avro = new OpenTsxAvro();
        undecodable = getRuntimeContext().getMetricGroup().counter("undecodable");
    }

    @Override
    public void processElement(StatsInput in, Context ctx, Collector<StatsRecord> out) throws Exception {
        StatsEngine e = engine.value();
        if (e == null) {
            e = spec.newEngine();
        }
        List<SeriesStats> closed;
        try {
            closed = in.episode ? e.onEpisode(avro.decode(in.payload, Episode.class))
                    : e.onObservation(avro.decode(in.payload, Observation.class));
        } catch (RuntimeException ex) {
            undecodable.inc();
            return;
        }
        engine.update(e);
        emit(closed, out);
        ctx.timerService().registerEventTimeTimer(Long.MAX_VALUE);
        if (globalEveryMs > 0 && nextGlobal.value() == null) {
            long at = ctx.timerService().currentProcessingTime() + globalEveryMs;
            ctx.timerService().registerProcessingTimeTimer(at);
            nextGlobal.update(at);
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<StatsRecord> out) throws Exception {
        StatsEngine e = engine.value();
        if (e == null) {
            return;
        }
        if (ctx.timeDomain() == TimeDomain.EVENT_TIME) {
            emit(e.flush(), out);
        } else {
            emit(e.emitGlobal(), out);
            nextGlobal.clear();    // the next input of this series arms the timer again
        }
        engine.update(e);
    }

    private void emit(List<SeriesStats> stats, Collector<StatsRecord> out) {
        for (SeriesStats s : stats) {
            out.collect(new StatsRecord(s.getSeriesId(), Math.floorDiv(Micros.of(s.getWindowEnd()), 1000L), avro.encode(s)));
        }
    }
}
