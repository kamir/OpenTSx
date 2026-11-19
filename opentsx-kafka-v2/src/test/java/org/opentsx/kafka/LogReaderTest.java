package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogReaderTest {

    private static final String TOPIC = "tsx.unit.series-stats.v2";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final long SECOND = 1_000_000_000L;

    /** A consumer on a clock that moves only when it is called: every call takes two seconds and records its timeout. */
    static final class Slow extends MockConsumer<byte[], byte[]> {
        long nanos;
        final List<Duration> timeouts = new ArrayList<>();

        Slow(long endOffset) {
            super(OffsetResetStrategy.EARLIEST);
            Node node = new Node(0, "localhost", 9092);
            updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, node, new Node[]{node}, new Node[]{node})));
            updateBeginningOffsets(Map.of(P0, 0L));
            updateEndOffsets(Map.of(P0, endOffset));
        }

        private void take(Duration timeout) {
            timeouts.add(timeout);
            nanos += 2 * SECOND;
        }

        @Override
        public synchronized List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
            take(timeout);
            return super.partitionsFor(topic, timeout);
        }

        @Override
        public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            take(timeout);
            return super.beginningOffsets(partitions, timeout);
        }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            take(timeout);
            return super.endOffsets(partitions, timeout);
        }

        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            nanos += 2 * SECOND;
            return super.poll(timeout);
        }
    }

    /**
     * A partition lookup that answers half a millisecond before the timeout. As KafkaConsumer 3.9.2, an offset request
     * with less than a millisecond answers an empty map without asking the broker.
     */
    static final class Tight extends MockConsumer<byte[], byte[]> {
        long nanos;

        Tight() {
            super(OffsetResetStrategy.EARLIEST);
            Node node = new Node(0, "localhost", 9092);
            updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, node, new Node[]{node}, new Node[]{node})));
            updateBeginningOffsets(Map.of(P0, 0L));
            updateEndOffsets(Map.of(P0, 1L));
        }

        @Override
        public synchronized List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
            nanos += timeout.toNanos() - 500_000;
            return super.partitionsFor(topic, timeout);
        }

        @Override
        public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            return timeout.toMillis() == 0 ? Map.of() : super.beginningOffsets(partitions, timeout);
        }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            return timeout.toMillis() == 0 ? Map.of() : super.endOffsets(partitions, timeout);
        }
    }

    @Test
    void lessThanAMillisecondLeftIsATimeout() {
        Tight consumer = new Tight();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LogReader.readAll(consumer, TOPIC, r -> { }, Duration.ofSeconds(7), () -> consumer.nanos));

        assertEquals("timed out reading " + TOPIC, e.getMessage());
    }

    @Test
    void eachCallGetsWhatIsLeftOfTheOneTimeout() {
        Slow consumer = new Slow(1);
        consumer.schedulePollTask(() -> consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, null, new byte[]{1})));
        List<Long> read = new ArrayList<>();

        LogReader.readAll(consumer, TOPIC, r -> read.add(r.offset()), Duration.ofSeconds(7), () -> consumer.nanos);

        assertEquals(List.of(0L), read);
        assertEquals(List.of(Duration.ofSeconds(7), Duration.ofSeconds(5), Duration.ofSeconds(3)), consumer.timeouts);
        assertEquals(Set.of(), consumer.assignment(), "the consumer is unassigned after the read");
    }

    @Test
    void theReadGivesUpWhenTheOneTimeoutHasPassed() {
        Slow consumer = new Slow(1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LogReader.readAll(consumer, TOPIC, r -> { }, Duration.ofSeconds(7), () -> consumer.nanos));

        assertEquals("timed out reading [" + P0 + "]", e.getMessage());
        assertEquals(8 * SECOND, consumer.nanos, "three metadata calls and one poll, then the timeout of 7 s has passed");
    }

    @Test
    void theMetadataCallsGiveUpWhenTheOneTimeoutHasPassed() {
        Slow consumer = new Slow(1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LogReader.readAll(consumer, TOPIC, r -> { }, Duration.ofSeconds(3), () -> consumer.nanos));

        assertEquals("timed out reading " + TOPIC, e.getMessage());
        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(1)), consumer.timeouts);
    }
}
