package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.core.TSBucket;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TSOConsumerTest {

    private static final TopicPartition PARTITION = new TopicPartition("tsx_probe", 0);

    @Test
    void aTopicWithFewerRecordsThanRequestedEndsAfterEmptyPollsAndClosesTheOneConsumer() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(PARTITION));
            consumer.addRecord(new ConsumerRecord<>("tsx_probe", 0, 0L, "s1", "{\"label\":\"s1\",\"dataset\":[1.0,2.0]}"));
        });
        TSBucket bucket = new TSBucket(true);
        bucket.inMEM = true;

        new TSOConsumer(consumer, "tsx_probe").runConsumer(bucket, 5, "tsx_probe");

        assertEquals(1, bucket.getBucketData().size());
        assertEquals("s1", bucket.getBucketData().get(0).getLabel());
        assertEquals(Set.of("tsx_probe"), consumer.subscription());
        assertTrue(consumer.closed());
    }
}
