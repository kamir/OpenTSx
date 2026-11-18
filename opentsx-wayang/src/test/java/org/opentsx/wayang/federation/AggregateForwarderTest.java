package org.opentsx.wayang.federation;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.wayang.TsxCheckpoint;
import org.opentsx.model.XxHash64;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.wayang.federation.FederationFixtures.*;

class AggregateForwarderTest {

    @TempDir Path directory;

    private static MockConsumer<byte[], byte[]> source(String topic, List<ConsumerRecord<byte[], byte[]>> records, long begin, long end) {
        var source = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.NONE);
        var partition = new TopicPartition(topic, 0);
        source.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, Node.noNode(), new Node[0], new Node[0])));
        source.updateBeginningOffsets(Map.of(partition, begin));
        source.updateEndOffsets(Map.of(partition, end));
        source.schedulePollTask(() -> records.forEach(source::addRecord));
        return source;
    }

    private static ConsumerRecord<byte[], byte[]> record(AggregateRecord value) {
        var producer = value.producerRecord(TOPIC);
        return new ConsumerRecord<>(TOPIC, 0, 0, 1234, TimestampType.CREATE_TIME, -1, -1,
                producer.key().getBytes(StandardCharsets.UTF_8), producer.value(), producer.headers(), Optional.empty());
    }

    private static MockProducer<byte[], byte[]> target() {
        return new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());
    }

    @Test void forwardingPreservesRawBytesHeadersTimestampAndAcknowledgedPosition() {
        var input = record(partial(inventory(), "s1", 0, 1, Double.NaN));
        var target = target();
        Path state = directory.resolve("forward.json");
        assertEquals(1, AggregateForwarder.copy(source(TOPIC, List.of(input), 0, 1), target, SPEC, TOPIC, state, Duration.ofSeconds(1)));
        var output = target.history().get(0);
        assertArrayEquals(input.key(), output.key());
        assertArrayEquals(input.value(), output.value());
        assertEquals(input.timestamp(), output.timestamp());
        var before = input.headers().toArray();
        var after = output.headers().toArray();
        assertEquals(before.length, after.length);
        for (int i = 0; i < before.length; i++) {
            assertEquals(before[i].key(), after[i].key());
            assertArrayEquals(before[i].value(), after[i].value());
        }
        assertEquals(Map.of(0, 1L), TsxCheckpoint.load(state, SPEC).orElseThrow().next());
        assertEquals(0, AggregateForwarder.copy(source(TOPIC, List.of(), 0, 1), target, SPEC, TOPIC, state, Duration.ofSeconds(1)));
        assertEquals(1, target.history().size());
    }

    @Test void unacknowledgedSendNeverAdvancesCheckpointAndReplayIsIdentical() {
        var input = record(partial(inventory(), "s1", 0, 1));
        Path state = directory.resolve("failure.json");
        var failed = new MockProducer<byte[], byte[]>(false, new ByteArraySerializer(), new ByteArraySerializer()) {
            @Override public synchronized void flush() {
                while (errorNext(new IllegalStateException("rejected test send"))) { }
            }
        };
        assertThrows(IllegalStateException.class, () -> AggregateForwarder.copy(source(TOPIC, List.of(input), 0, 1), failed, SPEC, TOPIC, state, Duration.ofSeconds(1)));
        assertFalse(Files.exists(state));
        assertEquals(1, failed.history().size());
        var target = target();
        assertEquals(1, AggregateForwarder.copy(source(TOPIC, List.of(input), 0, 1), target, SPEC, TOPIC, state, Duration.ofSeconds(1)));
        assertArrayEquals(failed.history().get(0).value(), target.history().get(0).value());
    }

    @Test void malformedAggregateIsRejectedBeforeSendAndCheckpoint() {
        var input = record(partial(inventory(), "s1", 0, 1));
        RecordHeaders headers = new RecordHeaders(input.headers());
        headers.add("tsx-site", "s1".getBytes(StandardCharsets.UTF_8));
        var invalid = new ConsumerRecord<>(TOPIC, 0, 0, 1, TimestampType.CREATE_TIME, -1, -1,
                input.key(), input.value(), headers, Optional.empty());
        var target = target();
        Path state = directory.resolve("invalid.json");
        assertThrows(IllegalArgumentException.class, () -> AggregateForwarder.copy(source(TOPIC, List.of(invalid), 0, 1), target, SPEC, TOPIC, state, Duration.ofSeconds(1)));
        assertTrue(target.history().isEmpty());
        assertFalse(Files.exists(state));
    }

    @Test void wrongIdentityLostRetentionAndRawTopicsAreRefused() {
        Path state = directory.resolve("position.json");
        new TsxCheckpoint("aggregate-forwarder", 1, SPEC, TOPIC, Map.of(0, 1L), Map.of()).write(state);
        assertThrows(IllegalArgumentException.class, () -> AggregateForwarder.copy(source(TOPIC, List.of(), 0, 2), target(), "0".repeat(64), TOPIC, state, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> AggregateForwarder.copy(source(TOPIC, List.of(), 2, 3), target(), SPEC, TOPIC, state, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> AggregateForwarder.copy(source(TOPIC, List.of(), 0, 0), target(), SPEC, "tsx.wind.episodes.v2", state, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> AggregateForwarder.copy("broker:9092", "broker:9092", TOPIC, state));
    }

    @Test void signalForwardingKeepsOriginalJsonBytesAndRefusesWrongSeriesKey() {
        String topic = "tsx.wind.signals.v1";
        String at = "1970-01-01T00:10:00Z";
        String identity = "wind|s1|" + GROUP + "|threshold.enter|" + at;
        var signal = AggregateRecord.JSON.createObjectNode().put("schema", "opentsx.signal.v1").put("job", "wind")
                .put("site", "s1").put("seriesId", GROUP).put("kind", "threshold.enter").put("at", at).put("specHash", SPEC)
                .put("id", XxHash64.hex(XxHash64.hash(identity.getBytes(StandardCharsets.UTF_8))));
        byte[] bytes = signal.toPrettyString().getBytes(StandardCharsets.UTF_8);
        RecordHeaders headers = new RecordHeaders();
        headers.add("tsx-schema", "opentsx.signal.v1".getBytes(StandardCharsets.UTF_8));
        headers.add("trace-id", "test-route-s".getBytes(StandardCharsets.UTF_8));
        var input = new ConsumerRecord<>(topic, 0, 0, 1234, TimestampType.CREATE_TIME, -1, -1,
                GROUP.getBytes(StandardCharsets.UTF_8), bytes, headers, Optional.empty());
        var target = target();
        Path state = directory.resolve("signal.json");
        assertEquals(1, AggregateForwarder.copy(source(topic, List.of(input), 0, 1), target, SPEC, topic, state, Duration.ofSeconds(1)));
        assertArrayEquals(bytes, target.history().get(0).value());
        assertArrayEquals("test-route-s".getBytes(StandardCharsets.UTF_8), target.history().get(0).headers().lastHeader("trace-id").value());
        var wrong = new ConsumerRecord<>(topic, 0, 0, 1234, TimestampType.CREATE_TIME, -1, -1,
                "fedcba9876543210".getBytes(StandardCharsets.UTF_8), bytes, headers, Optional.empty());
        var refused = target();
        Path invalidState = directory.resolve("wrong-signal.json");
        assertThrows(IllegalArgumentException.class, () -> AggregateForwarder.copy(source(topic, List.of(wrong), 0, 1), refused, SPEC, topic, invalidState, Duration.ofSeconds(1)));
        assertTrue(refused.history().isEmpty());
        assertFalse(Files.exists(invalidState));
    }
}
