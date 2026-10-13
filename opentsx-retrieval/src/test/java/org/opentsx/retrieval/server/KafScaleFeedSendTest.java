package org.opentsx.retrieval.server;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run must not report success for measuring points the broker never confirmed. */
class KafScaleFeedSendTest {

    static List<ProducerRecord<String, byte[]>> records(int n) {
        List<ProducerRecord<String, byte[]>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new ProducerRecord<>("tsx.wind.smart.x.vectors.v1", "turbine=T01", ("{\"i\":" + i + "}").getBytes()));
        }
        return out;
    }

    @Test
    void aRunOutlastsTheMinuteTheBrokerRefusesWrites() {
        java.util.Properties p = KafScaleFeed.patientProducerProps("localhost:9092");
        assertEquals(180_000, p.get("delivery.timeout.ms"));
        assertEquals(1000L, p.get("retry.backoff.ms"));
        assertTrue(!p.containsKey("retries"), "no fixed number of retries: the delivery timeout decides");
        assertEquals(false, p.get("enable.idempotence"));
        assertEquals(1, p.get("max.in.flight.requests.per.connection"));
        // the client accepts the combination
        new org.apache.kafka.clients.producer.KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer()).close();
    }

    @Test
    void confirmsEveryRecord() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        assertDoesNotThrow(() -> KafScaleFeed.sendAll(producer, records(5)));
        assertEquals(5, producer.history().size());
    }

    @Test
    void failsWithTheNumberTheBrokerConfirmed() {
        // manual completion: the broker confirms the first three records and times out on the other two
        MockProducer<String, byte[]> producer = new MockProducer<>(false, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized void flush() {
                for (int i = 0; i < 3; i++) {
                    completeNext();
                }
                while (errorNext(new TimeoutException("Expiring 2 record(s): 120000 ms has passed since batch creation"))) {
                    // every remaining record fails
                }
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> KafScaleFeed.sendAll(producer, records(5)));
        assertTrue(e.getMessage().contains("confirmed only 3 of 5 measuring points for tsx.wind.smart.x.vectors.v1"), e.getMessage());
        assertTrue(e.getMessage().contains("TimeoutException"), e.getMessage());
    }
}
