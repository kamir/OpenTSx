package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
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
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Java execution of {@link TsxTopicSink}. The operator returns only after the broker acknowledged every record, so
 * a checkpoint written after the job never runs ahead of the output (S6.3 of the specification).
 */
public final class JavaTsxTopicSink extends TsxTopicSink implements JavaExecutionOperator {

    public JavaTsxTopicSink(TsxTopicSink that) {
        super(that);
    }

    static Properties producerProperties(String bootstrap) {
        return KafScaleClients.producerProps(bootstrap);
    }

    static ProducerRecord<String, byte[]> toRecord(OpenTsxAvro avro, String topic, SpecificRecord r) {
        ProducerRecord<String, byte[]> out;
        if (r instanceof Episode e) {
            out = new ProducerRecord<>(topic, null, Math.floorDiv(Micros.of(e.getTStart()), 1_000L),
                    e.getSeries().getSeriesId(), avro.encode(canonical(e)));
        } else if (r instanceof SeriesStats s) {
            out = new ProducerRecord<>(topic, s.getSeriesId(), avro.encode(s));
        } else {
            throw new IllegalArgumentException("no key rule for " + r.getSchema().getFullName());
        }
        out.headers().add(TopicLayout.HEADER_SCHEMA, r.getSchema().getFullName().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /**
     * The episode with its maps in UTF-8 key order, as the model specification requires for encoding
     * (docs/datamodel-2026/04-MODELL-V2-SPEC.md, section 5). Avro decodes maps into hash maps, so an episode that was
     * read and is written again would otherwise change its bytes.
     */
    static Episode canonical(Episode e) {
        Episode c = Episode.newBuilder(e).build();
        c.getSeries().setTags(SeriesKeys.sortedByUtf8(c.getSeries().getTags()));
        c.setLabels(SeriesKeys.sortedByUtf8(c.getLabels()));
        if (c.getSegmentation() != null) {
            c.getSegmentation().setParams(SeriesKeys.sortedByUtf8(c.getSegmentation().getParams()));
        }
        return c;
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Future<RecordMetadata>> sent = new ArrayList<>();
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap())) {
            ((JavaChannelInstance) inputs[0]).<SpecificRecord>provideStream()
                    .forEach(r -> sent.add(producer.send(toRecord(avro, topic(), r))));
            producer.flush();
            for (Future<RecordMetadata> f : sent) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing " + topic(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("the broker did not acknowledge a record of " + topic(), e.getCause());
        }
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.topicsink.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxTopicSink(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        throw new UnsupportedOperationException(this + " has no outputs");
    }
}
