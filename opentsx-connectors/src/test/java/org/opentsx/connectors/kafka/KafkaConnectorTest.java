package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.core.TSBucket;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConnectorTest {

    private static final TopicPartition PARTITION = new TopicPartition("tsx_probe", 0);

    private static final class CountingConsumer extends MockConsumer<String, String> {
        int closes;

        CountingConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized void close(Duration timeout) {
            closes++;
            super.close(timeout);
        }
    }

    @Test
    void closeWithoutProcessTopicClosesTheConsumerOnce() {
        CountingConsumer kafka = new CountingConsumer();
        KafkaConnector connector = new KafkaConnector("tsx_probe");
        connector.consumer = new TSOConsumer(kafka, "tsx_probe");

        connector.close();
        connector.close();

        assertTrue(kafka.closed());
        assertEquals(1, kafka.closes);
    }

    @Test
    void closeAfterProcessTopicDoesNotCloseTheConsumerAgain() {
        CountingConsumer kafka = new CountingConsumer();
        kafka.updateBeginningOffsets(Map.of(PARTITION, 0L));
        kafka.schedulePollTask(() -> {
            kafka.rebalance(List.of(PARTITION));
            kafka.addRecord(new ConsumerRecord<>("tsx_probe", 0, 0L, "s1", "{\"label\":\"s1\",\"dataset\":[1.0]}"));
        });
        TSBucket bucket = new TSBucket(true);
        bucket.inMEM = true;
        KafkaConnector connector = new KafkaConnector("tsx_probe");
        connector.consumer = new TSOConsumer(kafka, "tsx_probe");

        connector.processTopic(bucket, 1);
        connector.close();

        assertEquals(1, bucket.getBucketData().size());
        assertEquals(1, kafka.closes);
    }

    @Test
    void closeBeforeConnectIsANoOp() {
        new KafkaConnector("tsx_probe").close();
    }
}
