package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.XxHash64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Java execution of {@link TsxTopicSource}: manual assignment and seek through {@link LogReader}, then a fingerprint
 * check and Avro decoding of every record. The whole range is held in memory; an increment bounds it.
 */
public final class JavaTsxTopicSource extends TsxTopicSource implements JavaExecutionOperator {

    static final Duration READ_TIMEOUT = Duration.ofMinutes(10);

    public JavaTsxTopicSource(TsxTopicSource that) {
        super(that);
    }

    static Properties consumerProperties(String bootstrap) {
        return KafScaleClients.consumerProps(bootstrap);
    }

    static Properties consumerProperties(TsxRead read) {
        Properties properties = consumerProperties(read.bootstrap());
        if (read.strictOffsets()) properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        return properties;
    }

    static void validateStrictCut(Consumer<byte[], byte[]> consumer, TsxRead read) {
        if (!read.strictOffsets()) return;
        var metadata = consumer.partitionsFor(read.topic(), READ_TIMEOUT);
        if (metadata == null || metadata.isEmpty() || read.ranges() == null) {
            throw new IllegalArgumentException("strict source requires the complete topic partition inventory");
        }
        java.util.Set<Integer> actual = new java.util.HashSet<>();
        List<TopicPartition> partitions = new ArrayList<>();
        for (var partition : metadata) {
            if (partition == null || !read.topic().equals(partition.topic()) || partition.partition() < 0
                    || !actual.add(partition.partition())) throw new IllegalArgumentException("invalid strict source partition metadata");
            partitions.add(new TopicPartition(read.topic(), partition.partition()));
        }
        if (!actual.equals(read.ranges().keySet())) throw new IllegalArgumentException("strict source cut must cover every topic partition");
        Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(partitions, READ_TIMEOUT);
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, READ_TIMEOUT);
        if (beginnings == null || ends == null || !beginnings.keySet().equals(java.util.Set.copyOf(partitions))
                || !ends.keySet().equals(java.util.Set.copyOf(partitions))) {
            throw new IllegalArgumentException("strict source offset metadata is incomplete");
        }
        for (TopicPartition partition : partitions) {
            long[] range = read.ranges().get(partition.partition());
            Long beginning = beginnings.get(partition);
            Long end = ends.get(partition);
            if (range == null || range.length != 2 || beginning == null || end == null || beginning < 0 || end < beginning
                    || range[0] < beginning || range[0] > range[1] || range[1] > end) {
                throw new IllegalArgumentException("strict source cut is outside the retained topic bounds");
            }
        }
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        TsxRead r = read();
        OpenTsxAvro avro = new OpenTsxAvro();
        List<SpecificRecord> records = new ArrayList<>();
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        r.ranges().forEach((p, range) -> ranges.put(new TopicPartition(r.topic(), p), range));
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProperties(r), new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            validateStrictCut(consumer, r);
            LogReader.read(consumer, ranges, rec -> records.add(decode(avro, r, rec)), READ_TIMEOUT);
        }
        ((CollectionChannel.Instance) outputs[0]).accept(records);
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    static SpecificRecord decode(OpenTsxAvro avro, TsxRead r, ConsumerRecord<byte[], byte[]> rec) {
        byte[] v = rec.value();
        String where = "record " + rec.topic() + "/" + rec.partition() + "@" + rec.offset();
        if (v == null || v.length < 10 || v[0] != (byte) 0xC3 || v[1] != 0x01) {
            throw new IllegalStateException(where + " is not Avro single-object encoded");
        }
        String fingerprint = XxHash64.hex(ByteBuffer.wrap(v, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
        if (!r.fingerprints().contains(fingerprint)) {
            throw new IllegalStateException(where + " has schema fingerprint " + fingerprint + ", not in plan "
                    + r.job() + " version " + r.planVersion());
        }
        return avro.decode(v);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.topicsource.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxTopicSource(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        throw new UnsupportedOperationException(this + " has no inputs");
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR);
    }
}
