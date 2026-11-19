package org.opentsx.flink.stats;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.TimeDomain;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs one {@link StatsEngine} per series id, the same calls as the KafScale processor makes.
 *
 * <ul>
 *   <li>Every input goes to {@link StatsEngine#onEpisode} or {@link StatsEngine#onObservation}; the windows it
 *       closes are emitted. An input that does not decode is counted in the metric {@code undecodable} and dropped.
 *       An episode that decodes but fails {@link Episodes#validate} is counted in the metric {@code invalid} and
 *       dropped. The first input of each kind per subtask and partition is logged at WARN. A failure of the
 *       engine fails the task, which restarts from the last checkpoint.</li>
 *   <li>GLOBAL: a processing-time timer emits {@link StatsEngine#emitGlobal()} every {@code globalEveryMs}
 *       (0 = only at the end of a bounded run). Every input arms it, also one that does not decode or is invalid, so
 *       the first timer of each interval logs the counts of the subtask at INFO even when all its inputs are bad.</li>
 *   <li>End of a bounded run: an event-time timer at {@link Long#MAX_VALUE} fires with the final watermark and
 *       calls {@link StatsEngine#flush()}. The job emits no other watermarks, because the engine keeps event time
 *       per series itself.</li>
 * </ul>
 */
public final class StatsFunction extends KeyedProcessFunction<String, StatsInput, StatsRecord> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(StatsFunction.class);

    private final EngineSpec spec;
    private final long globalEveryMs;

    private transient ValueState<StatsEngine> engine;
    private transient ValueState<Long> nextGlobal;
    private transient OpenTsxAvro avro;
    private transient Counter undecodable;
    private transient Counter invalid;
    private transient Set<TopicPartition> undecodableReported;
    private transient Set<TopicPartition> invalidReported;
    private transient long records;
    private transient long statsEmitted;
    private transient long progressDue;

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
        invalid = getRuntimeContext().getMetricGroup().counter("invalid");
        undecodableReported = new HashSet<>();
        invalidReported = new HashSet<>();
        records = 0;
        statsEmitted = 0;
        progressDue = Long.MIN_VALUE;
    }

    @Override
    public void processElement(StatsInput in, Context ctx, Collector<StatsRecord> out) throws Exception {
        records++;
        if (globalEveryMs > 0 && nextGlobal.value() == null) {
            long at = ctx.timerService().currentProcessingTime() + globalEveryMs;
            ctx.timerService().registerProcessingTimeTimer(at);
            nextGlobal.update(at);
        }
        StatsEngine e = engine.value();
        if (e == null) {
            e = spec.newEngine();
        }
        Episode episode = null;
        Observation observation = null;
        try {
            if (in.episode) {
                episode = avro.decode(in.payload, Episode.class);
            } else {
                observation = avro.decode(in.payload, Observation.class);
            }
        } catch (RuntimeException ex) {
            undecodable.inc();
            TopicPartition tp = new TopicPartition(in.topic, in.partition);
            if (undecodableReported.add(tp)) {
                LOG.warn("undecodable record skipped and counted in undecodable; later ones of this partition are only counted"
                        + " by this subtask topic={} partition={} offset={} cause={}", in.topic, in.partition, in.offset,
                        ex.getMessage());
            }
            return;
        }
        if (episode != null && !consistent(in, episode)) {
            return;
        }
        List<SeriesStats> closed = in.episode ? e.onEpisode(episode) : e.onObservation(observation);
        engine.update(e);
        emit(closed, out);
        ctx.timerService().registerEventTimeTimer(Long.MAX_VALUE);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<StatsRecord> out) throws Exception {
        boolean global = ctx.timeDomain() == TimeDomain.PROCESSING_TIME;
        StatsEngine e = engine.value();
        if (e != null) {
            emit(global ? e.emitGlobal() : e.flush(), out);
            engine.update(e);
        }
        if (global) {
            nextGlobal.clear();    // the next input of this series arms the timer again
            if (timestamp >= progressDue) {
                LOG.info("progress subtask={} records={} undecodable={} invalid={} statsEmitted={}",
                        getRuntimeContext().getTaskInfo().getIndexOfThisSubtask(), records, undecodable.getCount(),
                        invalid.getCount(), statsEmitted);
                progressDue = timestamp + globalEveryMs;
            }
        }
    }

    private boolean consistent(StatsInput in, Episode episode) {
        try {
            Episodes.validate(episode);
            return true;
        } catch (IllegalArgumentException e) {
            invalid.inc();
            TopicPartition tp = new TopicPartition(in.topic, in.partition);
            if (invalidReported.add(tp)) {
                LOG.warn("invalid episode skipped and counted in invalid; later ones of this partition are only counted by this"
                        + " subtask topic={} partition={} offset={} cause={}", in.topic, in.partition, in.offset, e.getMessage());
            }
            return false;
        }
    }

    private void emit(List<SeriesStats> stats, Collector<StatsRecord> out) {
        for (SeriesStats s : stats) {
            out.collect(new StatsRecord(s.getSeriesId(), Math.floorDiv(Micros.of(s.getWindowEnd()), 1000L), avro.encode(s)));
        }
        statsEmitted += stats.size();
    }
}
