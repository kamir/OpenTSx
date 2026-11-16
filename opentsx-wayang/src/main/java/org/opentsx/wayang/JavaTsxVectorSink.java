package org.opentsx.wayang;

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
import org.opentsx.retrieval.vector.VectorJson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Java execution of {@link TsxVectorSink}; returns only after every record is acknowledged, like {@link JavaTsxTopicSink}. */
public final class JavaTsxVectorSink extends TsxVectorSink implements JavaExecutionOperator {

    public JavaTsxVectorSink(TsxVectorSink that) {
        super(that);
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        List<Future<RecordMetadata>> sent = new ArrayList<>();
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap())) {
            ((JavaChannelInstance) inputs[0]).<TsxVectorRecord>provideStream()
                    .forEach(r -> sent.add(producer.send(new ProducerRecord<>(topic(), r.key(), VectorJson.bytes(r.json())))));
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
        return "wayang.java.tsx.vectorsink.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxVectorSink(this);
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
