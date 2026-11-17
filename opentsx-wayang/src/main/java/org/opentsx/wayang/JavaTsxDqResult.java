package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.opentsx.model.Micros;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.DataQuality;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class JavaTsxDqResult extends TsxDqResult implements JavaExecutionOperator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public JavaTsxDqResult(TsxDqResult that) { super(that); }

    static List<JsonNode> results(TsxDqResult op, SpecificRecord input) {
        if (!(input instanceof SeriesStats stats)) throw new IllegalArgumentException("dq.result requires SeriesStats");
        if (stats.getSeriesId() == null || !stats.getSeriesId().matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("dq.result needs a 16-character lower-case hex seriesId");
        }
        List<JsonNode> out = new ArrayList<>();
        for (DataQuality.Result result : DataQuality.evaluate(stats, op.expectation(), op.nowMicros())) {
            if (!op.checks().contains(result.check())) continue;
            String from = Micros.toInstant(result.windowStartMicros()).toString();
            String to = Micros.toInstant(result.windowEndMicros()).toString();
            String identity = op.job() + "|" + op.site() + "|" + result.seriesId() + "|dq|" + to
                    + "|" + result.check() + "|" + from + "|" + to;
            ObjectNode signal = JSON.createObjectNode();
            signal.put("schema", "opentsx.signal.v1");
            signal.put("id", XxHash64.hex(XxHash64.hash(identity.getBytes(StandardCharsets.UTF_8))));
            signal.put("job", op.job());
            signal.put("site", op.site());
            signal.put("seriesId", result.seriesId());
            signal.put("kind", "dq");
            signal.put("at", to);
            signal.putObject("window").put("from", from).put("to", to);
            signal.put("check", result.check().name());
            signal.put("status", result.status().name());
            if (Double.isFinite(result.value())) signal.put("value", result.value());
            else signal.putNull("value");
            signal.put("threshold", result.threshold());
            signal.put("message", result.message());
            signal.put("specHash", op.specHash());
            out.add(signal);
        }
        return out;
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext context) {
        List<JsonNode> out = ((JavaChannelInstance) inputs[0]).<SpecificRecord>provideStream()
                .flatMap(record -> results(this, record).stream()).toList();
        ((CollectionChannel.Instance) outputs[0]).accept(out);
        return ExecutionOperator.modelEagerExecution(inputs, outputs, context);
    }

    @Override public String getLoadProfileEstimatorConfigurationKey() { return "wayang.java.tsx.dqresult.load"; }
    @Override protected ExecutionOperator createCopy() { return new JavaTsxDqResult(this); }
    @Override public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }
    @Override public List<ChannelDescriptor> getSupportedOutputChannels(int index) { return List.of(CollectionChannel.DESCRIPTOR); }
}
