package org.opentsx.wayang;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TsxStrictCutTest {

    private static final String TOPIC = "tsx.wind.episodes.v2";
    private static final TopicPartition ZERO = new TopicPartition(TOPIC, 0);
    private static final TopicPartition ONE = new TopicPartition(TOPIC, 1);

    private static TsxRead read(Map<Integer, long[]> ranges) {
        return new TsxRead("unused", TOPIC, ranges, Set.of(), "wind", 1, true);
    }

    private static MockConsumer<byte[], byte[]> consumer() {
        var consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.NONE);
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, Node.noNode(), new Node[0], new Node[0]),
                new PartitionInfo(TOPIC, 1, Node.noNode(), new Node[0], new Node[0])));
        consumer.updateBeginningOffsets(Map.of(ZERO, 2L, ONE, 10L));
        consumer.updateEndOffsets(Map.of(ZERO, 8L, ONE, 20L));
        return consumer;
    }

    @Test void existingConstructorAndDefaultConsumerPropertiesStayUnchanged() {
        var legacy = new TsxRead("unused", TOPIC, Map.of(), Set.of(), "wind", 1);
        assertFalse(legacy.strictOffsets());
        assertEquals(JavaTsxTopicSource.consumerProperties("unused"), JavaTsxTopicSource.consumerProperties(legacy));
        assertEquals("earliest", JavaTsxTopicSource.consumerProperties(legacy).get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test void strictReadsDisableOffsetResetWithoutChangingOtherSettings() {
        var expected = JavaTsxTopicSource.consumerProperties("unused");
        expected.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        assertEquals(expected, JavaTsxTopicSource.consumerProperties(read(Map.of())));
    }

    @Test void exactWholeTopicCutsWithinRetainedBoundsAreAccepted() {
        var consumer = consumer();
        assertDoesNotThrow(() -> JavaTsxTopicSource.validateStrictCut(consumer, read(Map.of(0, new long[]{2, 8}, 1, new long[]{10, 20}))));
        assertDoesNotThrow(() -> JavaTsxTopicSource.validateStrictCut(consumer, read(Map.of(0, new long[]{8, 8}, 1, new long[]{10, 10}))));
        assertTrue(consumer.assignment().isEmpty());
    }

    @Test void retainedAwayFutureReversedAndMalformedCutsAreRejectedBeforeAssignment() {
        var consumer = consumer();
        for (long[] range : List.of(new long[]{1, 8}, new long[]{2, 9}, new long[]{7, 6}, new long[]{-1, 8}, new long[]{2}, new long[]{2, 8, 8})) {
            assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSource.validateStrictCut(consumer, read(Map.of(0, range, 1, new long[]{10, 20}))));
            assertTrue(consumer.assignment().isEmpty());
        }
    }

    @Test void omittedExtraAndUnknownPartitionsAreRejected() {
        var consumer = consumer();
        for (Map<Integer, long[]> ranges : List.of(Map.of(0, new long[]{2, 8}), Map.of(0, new long[]{2, 8}, 1, new long[]{10, 20}, 2, new long[]{0, 0}),
                Map.of(0, new long[]{2, 8}, 2, new long[]{10, 20}))) {
            assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSource.validateStrictCut(consumer, read(ranges)));
        }
        consumer.updatePartitions(TOPIC, List.of());
        assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSource.validateStrictCut(consumer, read(Map.of())));
    }

    @Test void incompleteBrokerOffsetMetadataIsRejected() {
        var consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.NONE) {
            @Override public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
                return Map.of();
            }
        };
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, Node.noNode(), new Node[0], new Node[0])));
        consumer.updateEndOffsets(Map.of(ZERO, 8L));
        assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSource.validateStrictCut(consumer, read(Map.of(0, new long[]{2, 8}))));
    }

    @Test void legacyReadsDoNotAddMetadataOrRetentionChecks() {
        var consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.NONE) {
            @Override public synchronized List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
                throw new AssertionError("legacy reads must not request additional metadata");
            }
        };
        var legacy = new TsxRead("unused", TOPIC, Map.of(0, new long[]{0, 100}), Set.of(), "wind", 1);
        assertDoesNotThrow(() -> JavaTsxTopicSource.validateStrictCut(consumer, legacy));
    }
}
