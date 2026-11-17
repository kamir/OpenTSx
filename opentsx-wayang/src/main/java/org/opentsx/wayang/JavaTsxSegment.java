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
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.retrieval.segment.StreamingSegmenter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

public final class JavaTsxSegment extends TsxSegment implements JavaExecutionOperator {

    public JavaTsxSegment(TsxSegment that) {
        super(that);
    }

    static List<Episode> run(TsxSegment op, List<? extends SpecificRecord> input) {
        StreamingSegmenter stream = new StreamingSegmenter(op.segmenter());
        JsonNode saved = op.state().get(op.node());
        try {
            if (saved != null) {
                if (!saved.isTextual()) throw new IOException("segmentation checkpoint must be Base64 text");
                byte[] encoded;
                try {
                    encoded = Base64.getDecoder().decode(saved.textValue());
                } catch (IllegalArgumentException e) {
                    throw new IOException("segmentation checkpoint is not Base64", e);
                }
                try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encoded))) {
                    stream.readState(data);
                    if (data.available() != 0) throw new IOException("segmentation checkpoint has trailing bytes");
                }
            }
            List<Episode> out = new ArrayList<>();
            for (SpecificRecord record : input) {
                if (!(record instanceof Episode episode)) {
                    throw new IllegalArgumentException("unsupported segmentation input: "
                            + (record == null ? "null" : record.getSchema().getFullName()));
                }
                out.addAll(stream.onEpisode(episode));
            }
            if (op.complete()) out.addAll(stream.flush());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                stream.writeState(data);
            }
            op.state().put(op.node(), TextNode.valueOf(Base64.getEncoder().encodeToString(bytes.toByteArray())));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("segmentation state of node " + op.node(), e);
        }
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        List<SpecificRecord> input = ((JavaChannelInstance) inputs[0]).<SpecificRecord>provideStream().toList();
        ((CollectionChannel.Instance) outputs[0]).accept(new ArrayList<SpecificRecord>(run(this, input)));
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return segmenter().strategy() == SegmentationStrategy.FEATURE_THRESHOLD
                ? "wayang.java.tsx.segmentthreshold.load" : "wayang.java.tsx.segmentextremum.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxSegment(this);
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
