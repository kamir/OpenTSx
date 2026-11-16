package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsAccumulator;
import org.opentsx.stats.StatsEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Java execution of {@link TsxStatsWindow}. The engine state ({@link StatsEngine#writeState}) is kept in
 * {@link TsxState} as Base64 text, so open windows continue in the next increment.
 */
public final class JavaTsxStatsWindow extends TsxStatsWindow implements JavaExecutionOperator {

    public JavaTsxStatsWindow(TsxStatsWindow that) {
        super(that);
    }

    static List<SeriesStats> run(TsxStatsWindow op, List<? extends SpecificRecord> in) {
        StatsEngine engine = new StatsEngine(op.config());
        JsonNode saved = op.state().get(op.node());
        if (op.windowSeriesId() != null && saved != null) {
            throw new IllegalArgumentException("explicit statistics windows cannot resume incremental state");
        }
        try {
            if (saved != null) {
                engine.readState(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(saved.asText()))));
            }
            List<SeriesStats> out = new ArrayList<>();
            for (SpecificRecord record : in) {
                if (record == null) {
                    throw new IllegalArgumentException("unsupported statistics input: null");
                }
                if (record instanceof Episode episode) {
                    if (op.windowSeriesId() != null) {
                        validateWindowSeries(op, episode.getSeries().getSeriesId());
                        for (long ts : Episodes.timestampsMicros(episode)) {
                            validateWindowInput(op, episode.getSeries().getSeriesId(), ts);
                        }
                    }
                    out.addAll(engine.onEpisode(episode));
                } else if (record instanceof Observation observation) {
                    if (op.windowSeriesId() != null) {
                        validateWindowInput(op, observation.getSeriesId(), Micros.of(observation.getTs()));
                    }
                    out.addAll(engine.onObservation(observation));
                } else {
                    throw new IllegalArgumentException("unsupported statistics input: " + record.getSchema().getFullName());
                }
            }
            if (op.complete()) {
                out.addAll(engine.flush());
            }
            if (op.windowSeriesId() != null) {
                List<SeriesStats> windows = new ArrayList<>();
                for (Duration duration : op.config().windows()) {
                    SeriesStats found = null;
                    for (SeriesStats record : out) {
                        if (record.getWindowType().equals(duration.toString())
                                && Micros.of(record.getWindowStart()) == op.windowStartMicros()) {
                            found = record;
                            break;
                        }
                    }
                    windows.add(found == null ? new StatsAccumulator(op.config().sketchAlpha())
                            .toSeriesStats(op.windowSeriesId(), duration.toString(), op.windowStartMicros(),
                                    Math.addExact(op.windowStartMicros(), duration.toNanos() / 1000)) : found);
                }
                return windows;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                engine.writeState(data);
            }
            op.state().put(op.node(), TextNode.valueOf(Base64.getEncoder().encodeToString(bytes.toByteArray())));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("statistics state of node " + op.node(), e);
        }
    }

    private static void validateWindowInput(TsxStatsWindow op, String seriesId, long timestamp) {
        validateWindowSeries(op, seriesId);
        long maxEnd = op.windowStartMicros();
        for (Duration duration : op.config().windows()) {
            maxEnd = Math.max(maxEnd, Math.addExact(op.windowStartMicros(), duration.toNanos() / 1000));
        }
        if (timestamp < op.windowStartMicros() || timestamp >= maxEnd) {
            throw new IllegalArgumentException("sample is outside the requested statistics windows: " + timestamp);
        }
    }

    private static void validateWindowSeries(TsxStatsWindow op, String seriesId) {
        if (!op.windowSeriesId().equals(seriesId)) {
            throw new IllegalArgumentException("statistics window expects series " + op.windowSeriesId() + ", not " + seriesId);
        }
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        List<SpecificRecord> in = ((JavaChannelInstance) inputs[0]).<SpecificRecord>provideStream().toList();
        ((CollectionChannel.Instance) outputs[0]).accept(new ArrayList<SpecificRecord>(run(this, in)));
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.statswindow.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxStatsWindow(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR);
    }
}
