package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.opentsx.retrieval.vector.VectorAligner;
import org.opentsx.retrieval.vector.VectorJson;
import org.opentsx.retrieval.vector.VectorStream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Java execution of {@link TsxAlignVector}. */
public final class JavaTsxAlignVector extends TsxAlignVector implements JavaExecutionOperator {

    public JavaTsxAlignVector(TsxAlignVector that) {
        super(that);
    }

    static List<TsxVectorRecord> run(TsxAlignVector op, List<TsxTagged> in) {
        JsonNode saved = op.state().get(op.node());
        VectorStream stream = saved == null ? new VectorStream(op.expectedByInput(), op.params(), op.latenessMicros())
                : VectorStream.restore(op.expectedByInput(), op.params(), op.latenessMicros(), saved);
        List<VectorAligner.Point> points = new ArrayList<>();
        for (TsxTagged t : in) {
            Episode e = t.episode();
            points.addAll(stream.add(t.input(), e.getSeries().getSeriesId(), e.getSeries().getMetric(),
                    e.getSeries().getTags(), Episodes.timestampsMicros(e), Episodes.values(e), Micros.of(e.getTEnd())));
        }
        if (op.flush()) {
            points.addAll(stream.flush());
        }
        op.state().put(op.node(), stream.state());
        List<TsxVectorRecord> out = new ArrayList<>(points.size());
        for (VectorAligner.Point p : points) {
            out.add(new TsxVectorRecord(VectorJson.keyText(p.key()), VectorJson.record(op.vector(), p, op.dag(), op.specHash())));
        }
        return out;
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        List<TsxTagged> in = ((JavaChannelInstance) inputs[0]).<TsxTagged>provideStream().toList();
        ((CollectionChannel.Instance) outputs[0]).accept(run(this, in));
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.alignvector.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxAlignVector(this);
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
