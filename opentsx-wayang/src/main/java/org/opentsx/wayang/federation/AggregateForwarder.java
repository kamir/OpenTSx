package org.opentsx.wayang.federation;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.wayang.JavaTsxTopicSink;
import org.opentsx.wayang.TsxCheckpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;

public final class AggregateForwarder {

    private AggregateForwarder() { }

    public static long copy(String sourceBootstrap, String targetBootstrap, String topic, Path checkpoint) {
        if (sourceBootstrap == null || targetBootstrap == null || sourceBootstrap.isBlank() || targetBootstrap.isBlank()
                || sourceBootstrap.equals(targetBootstrap)) throw new IllegalArgumentException("forwarder needs distinct configured source and target brokers");
        String identity = ExecutionAssignments.sha256(sourceBootstrap + "\n" + targetBootstrap + "\n" + topic);
        try (var source = KafScaleClients.consumer(sourceBootstrap);
             var target = new KafkaProducer<>(KafScaleClients.producerProps(targetBootstrap), new ByteArraySerializer(), new ByteArraySerializer())) {
            return copy(source, target, identity, topic, checkpoint, Duration.ofMinutes(5));
        }
    }

    static long copy(Consumer<byte[], byte[]> source, Producer<byte[], byte[]> target, String identity,
                     String topic, Path checkpoint, Duration timeout) {
        if (!ExecutionAssignments.validTopic(topic) || !(topic.endsWith(".aggregates.v2") || topic.endsWith(".signals.v1"))) {
            throw new IllegalArgumentException("forwarder permits only aggregate and signal topics");
        }
        List<TopicPartition> partitions = source.partitionsFor(topic, timeout).stream()
                .map(p -> new TopicPartition(topic, p.partition())).toList();
        if (partitions.isEmpty()) throw new IllegalArgumentException("forwarder topic has no partitions");
        Map<TopicPartition, Long> begin = source.beginningOffsets(partitions, timeout);
        Map<TopicPartition, Long> end = source.endOffsets(partitions, timeout);
        Map<Integer, Long> from = new TreeMap<>();
        if (Files.exists(checkpoint)) {
            TsxCheckpoint saved = TsxCheckpoint.load(checkpoint, identity)
                    .orElseThrow(() -> new IllegalArgumentException("forwarder continuation does not match"));
            if (!saved.job().equals("aggregate-forwarder") || saved.planVersion() != 1 || !saved.topic().equals(topic)
                    || !saved.state().isEmpty() || !saved.next().keySet().equals(partitions.stream()
                    .map(TopicPartition::partition).collect(java.util.stream.Collectors.toSet()))) {
                throw new IllegalArgumentException("invalid forwarder checkpoint identity");
            }
            from.putAll(saved.next());
        }
        Map<TopicPartition, long[]> ranges = new TreeMap<>(java.util.Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
        Map<Integer, Long> next = new TreeMap<>();
        for (TopicPartition partition : partitions) {
            long start = from.getOrDefault(partition.partition(), begin.get(partition));
            if (start < begin.get(partition) || start > end.get(partition)) throw new IllegalArgumentException("forwarder position is outside the retained log");
            ranges.put(partition, new long[]{start, end.get(partition)});
            next.put(partition.partition(), end.get(partition));
        }
        List<Future<?>> sent = new ArrayList<>();
        long[] copied = {0};
        OpenTsxAvro avro = new OpenTsxAvro();
        LogReader.read(source, ranges, record -> {
            if (topic.endsWith(".aggregates.v2")) AggregateRecord.fromConsumerRecord(record);
            else {
                ProducerRecord<String, byte[]> validated = JavaTsxTopicSink.toRecord(avro, topic, AggregateRecord.parse(record.value(), AggregateRecord.MAX_RECORD_BYTES));
                if (!java.util.Arrays.equals(record.key(), validated.key().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    throw new IllegalArgumentException("forwarded signal key does not match its series");
                }
            }
            RecordHeaders headers = new RecordHeaders();
            record.headers().forEach(h -> headers.add(h.key(), h.value() == null ? null : h.value().clone()));
            sent.add(target.send(new ProducerRecord<>(topic, null, record.timestamp(), record.key() == null ? null : record.key().clone(),
                    record.value().clone(), headers)));
            copied[0] = Math.addExact(copied[0], 1);
            if (sent.size() == 256) acknowledge(target, sent);
        }, timeout);
        acknowledge(target, sent);
        try { Files.createDirectories(checkpoint.toAbsolutePath().getParent()); }
        catch (java.io.IOException e) { throw new IllegalStateException("cannot create forwarder checkpoint directory", e); }
        new TsxCheckpoint("aggregate-forwarder", 1, identity, topic, next, Map.of()).write(checkpoint);
        return copied[0];
    }

    private static void acknowledge(Producer<byte[], byte[]> target, List<Future<?>> sent) {
        target.flush();
        try {
            for (Future<?> future : sent) future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("aggregate forwarding was interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("aggregate forwarding was not acknowledged", e.getCause());
        }
        sent.clear();
    }
}
