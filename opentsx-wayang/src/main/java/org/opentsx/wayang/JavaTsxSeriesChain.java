package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Java execution of {@link TsxSeriesChain} through {@link SeriesStream}; its state survives in {@link TsxState}. */
public final class JavaTsxSeriesChain extends TsxSeriesChain implements JavaExecutionOperator {

    public JavaTsxSeriesChain(TsxSeriesChain that) {
        super(that);
    }

    /** Runs the episodes through a stream restored from {@code saved} (null to begin anew); returns what is final. */
    static List<Episode> run(TsxSeriesChain op, List<Episode> in) {
        CleanChain chain = CleanChain.of(op.nodes());
        JsonNode saved = op.state().get(op.node());
        SeriesStream stream = saved == null ? new SeriesStream(chain, op.target(), op.expected())
                : SeriesStream.restore(chain, op.target(), op.expected(), saved);
        List<Episode> out = new ArrayList<>();
        op.state().progress(op.node(), stream.watermark(), stream.waitingFor(), stream.skippedBlocks());
        for (Episode e : in) {
            out.addAll(stream.add(e));
            op.state().progress(op.node(), stream.watermark(), stream.waitingFor(), stream.skippedBlocks());
        }
        op.state().put(op.node(), stream.state());
        return out;
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        List<Episode> in = ((JavaChannelInstance) inputs[0]).<SpecificRecord>provideStream()
                .map(r -> (Episode) r).toList();
        ((CollectionChannel.Instance) outputs[0]).accept(new ArrayList<SpecificRecord>(run(this, in)));
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.serieschain.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxSeriesChain(this);
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
