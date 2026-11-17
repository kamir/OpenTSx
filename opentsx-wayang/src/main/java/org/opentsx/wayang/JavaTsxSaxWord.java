package org.opentsx.wayang;

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
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.pattern.Sax;
import org.opentsx.retrieval.pattern.ZNorm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class JavaTsxSaxWord extends TsxSaxWord implements JavaExecutionOperator {

    public JavaTsxSaxWord(TsxSaxWord that) { super(that); }

    static Episode word(TsxSaxWord op, SpecificRecord input) {
        if (!(input instanceof Episode episode)) throw new IllegalArgumentException("sax.word requires an Episode");
        Episodes.validate(episode);
        double[] values = Episodes.values(episode);
        double[] normalized = op.normalize() ? ZNorm.of(values) : values;
        Episode out = Episode.newBuilder(episode).build();
        out.getSummary().setSax(Sax.word(normalized, op.w(), op.a()));
        String identity = out.getSeries().getSeriesId() + "|" + Micros.of(out.getTStart()) + "|"
                + Micros.of(out.getTEnd()) + "|" + op.specHash();
        out.setEpisodeId("c" + XxHash64.hex(XxHash64.hash(identity.getBytes(StandardCharsets.UTF_8))));
        out.getProvenance().setParentEpisodeId(episode.getEpisodeId());
        out.getProvenance().setProducer("opentsx-wayang/sax.word");
        out.getProvenance().setCreatedAt(out.getTEnd());
        return out;
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext context) {
        List<SpecificRecord> out = ((JavaChannelInstance) inputs[0]).<SpecificRecord>provideStream()
                .map(record -> (SpecificRecord) word(this, record)).toList();
        ((CollectionChannel.Instance) outputs[0]).accept(out);
        return ExecutionOperator.modelEagerExecution(inputs, outputs, context);
    }

    @Override public String getLoadProfileEstimatorConfigurationKey() { return "wayang.java.tsx.saxword.load"; }
    @Override protected ExecutionOperator createCopy() { return new JavaTsxSaxWord(this); }
    @Override public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }
    @Override public List<ChannelDescriptor> getSupportedOutputChannels(int index) { return List.of(CollectionChannel.DESCRIPTOR); }
}
