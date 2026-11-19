package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatienceTest {

    /** What a consumer throws when KafScale refuses the fetch (REQUEST_TIMED_OUT, error code 7). */
    static IllegalStateException refusal() {
        return new IllegalStateException("Unexpected error code 7 while fetching at offset 612 from topic-partition tsx.wind.episodes.v2-3");
    }

    /** A clock that moves only when {@link Patience} pauses or the test moves it; records every pause. */
    static final class Time {
        long nanos;
        final List<Long> pausesMillis = new ArrayList<>();

        Patience patience(Duration limit, Duration pause) {
            return new Patience(limit, pause, () -> nanos, millis -> {
                pausesMillis.add(millis);
                nanos += millis * 1_000_000L;
            });
        }
    }

    /** Runs {@code action} and returns what it wrote to stderr, where slf4j-simple writes its lines. */
    static String stderrOf(Runnable action) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(stderr);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    /** A consumer whose next polls fail with the given exceptions before it delivers again. */
    static final class Refusing extends MockConsumer<byte[], byte[]> {
        final java.util.ArrayDeque<RuntimeException> failures = new java.util.ArrayDeque<>();

        Refusing(RuntimeException... failures) {
            super(OffsetResetStrategy.EARLIEST);
            this.failures.addAll(List.of(failures));
        }

        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            if (!failures.isEmpty()) {
                throw failures.poll();
            }
            return super.poll(timeout);
        }
    }

    @Test
    void tellsARefusalFromARealError() {
        assertTrue(Patience.isRefusal(refusal()));
        assertTrue(Patience.isRefusal(new TimeoutException("request timed out")));
        assertFalse(Patience.isRefusal(new IllegalStateException("Consumer is not subscribed to any topics")));
        assertFalse(Patience.isRefusal(new IllegalStateException("Unexpected error code 3 while fetching at offset 0")));
        assertFalse(Patience.isRefusal(new IllegalArgumentException("x")));
    }

    /**
     * What kafka-clients does when one partition is refused: the poll has already taken the records of the other
     * partitions and moved their positions on, and then the refusal is thrown. The caller gets no records.
     */
    static final class DropsWhatItTook extends MockConsumer<byte[], byte[]> {
        boolean refuseNext = true;

        DropsWhatItTook() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            ConsumerRecords<byte[], byte[]> taken = super.poll(timeout);
            if (refuseNext && !taken.isEmpty()) {
                refuseNext = false;
                throw refusal();
            }
            return taken;
        }
    }

    @Test
    void recordsTheClientTookBeforeARefusalAreReadAgain() {
        TopicPartition tp = new TopicPartition("tsx.wind.episodes.v2", 0);
        DropsWhatItTook c = new DropsWhatItTook();
        c.assign(List.of(tp));
        c.updateBeginningOffsets(Map.of(tp, 0L));
        c.seek(tp, 0);
        for (int i = 0; i < 3; i++) {
            c.addRecord(new ConsumerRecord<>(tp.topic(), 0, i, new byte[0], new byte[]{(byte) i}));
        }
        Patience patience = new Time().patience(Duration.ofSeconds(30), Duration.ofMillis(1));
        assertEquals(0, patience.poll(c, Duration.ofMillis(1)).count(), "the refused poll gives nothing");
        assertEquals(0, c.position(tp), "and the consumer is back where it was");
        // a MockConsumer hands a record out once: what was taken has to be put there again, as a broker would on a fetch
        for (int i = 0; i < 3; i++) {
            c.addRecord(new ConsumerRecord<>(tp.topic(), 0, i, new byte[0], new byte[]{(byte) i}));
        }
        ConsumerRecords<byte[], byte[]> again = patience.poll(c, Duration.ofMillis(1));
        assertEquals(3, again.count());
        assertEquals(0, again.iterator().next().offset(), "the first record is not skipped");
    }

    @Test
    void waitsOutRefusalsAndThenDeliversFromTheSamePosition() {
        TopicPartition tp = new TopicPartition("t", 0);
        Refusing consumer = new Refusing(refusal(), new TimeoutException("again"));
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.seek(tp, 0);
        consumer.addRecord(new ConsumerRecord<>("t", 0, 0, new byte[0], "a".getBytes()));
        Time time = new Time();
        Patience patience = time.patience(Duration.ofSeconds(30), Duration.ofMillis(5));

        assertEquals(0, patience.poll(consumer, Duration.ofMillis(10)).count(), "first refusal: nothing, no exception");
        assertEquals(0, patience.poll(consumer, Duration.ofMillis(10)).count(), "second refusal");
        assertEquals(0, consumer.position(tp), "a refusal does not move the position");
        ConsumerRecords<byte[], byte[]> records = patience.poll(consumer, Duration.ofMillis(10));
        assertEquals(1, records.count());
        assertEquals(1, consumer.position(tp));
        assertEquals(List.of(5L, 5L), time.pausesMillis, "one pause after each refusal, none after the answer");
    }

    @Test
    void givesUpWhenTheBrokerRefusesLongerThanTheLimit() {
        Refusing consumer = new Refusing(refusal(), refusal(), refusal());
        Time time = new Time();
        Patience patience = time.patience(Duration.ofMillis(20), Duration.ofMillis(15));
        assertEquals(0, patience.poll(consumer, Duration.ofMillis(1)).count());
        assertEquals(0, patience.poll(consumer, Duration.ofMillis(1)).count(), "15 ms of refusals are within the limit");
        time.nanos += Duration.ofMillis(30).toNanos();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> patience.poll(consumer, Duration.ofMillis(1)));
        assertTrue(e.getMessage().contains("refused reads for more than"), e.getMessage());
        assertTrue(Patience.isRefusal(e.getCause()));
        assertEquals(List.of(15L, 15L), time.pausesMillis, "no pause after giving up");
    }

    @Test
    void aRefusalWindowLogsOneWarningAndOneRecovery() {
        TopicPartition tp = new TopicPartition("t", 0);
        Refusing consumer = new Refusing(refusal(), new TimeoutException("again"));
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.seek(tp, 0);
        Patience patience = new Time().patience(Duration.ofSeconds(30), Duration.ofMillis(5));

        String log = stderrOf(() -> {
            for (int i = 0; i < 4; i++) {
                patience.poll(consumer, Duration.ofMillis(1));
            }
        });

        assertEquals(List.of(
                "WARN org.opentsx.kafka.Patience - the broker refuses reads; waiting pause=PT0.005S limit=PT30S"
                        + " cause=Unexpected error code 7 while fetching at offset 612 from topic-partition tsx.wind.episodes.v2-3",
                "INFO org.opentsx.kafka.Patience - the broker answers reads again refusedFor=PT0.01S"),
                log.lines().map(l -> l.substring(l.indexOf("] ") + 2)).toList(), log);
    }

    @Test
    void letsEveryOtherErrorThrough() {
        IllegalStateException real = new IllegalStateException("Consumer is not subscribed to any topics or assigned any partitions");
        Refusing consumer = new Refusing(real);
        assertSame(real, assertThrows(IllegalStateException.class, () -> Patience.standard().poll(consumer, Duration.ofMillis(1))));
    }

    @Test
    void aProducerRetriesForMinutesNotForFiveQuickAttempts() {
        Properties p = KafScaleClients.producerProps("localhost:9092");
        assertEquals(180_000, p.get("delivery.timeout.ms"));
        assertEquals(1000L, p.get("retry.backoff.ms"));
        assertFalse(p.containsKey("retries"), "no fixed number of retries: the delivery timeout decides");
        assertEquals(false, p.get("enable.idempotence"));
        assertEquals(1, p.get("max.in.flight.requests.per.connection"));
        // the client accepts the combination
        new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer()).close();
    }
}
