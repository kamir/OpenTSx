package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxIncrementLoopTest {

    static final TsxIncrement.Config CONFIG = new TsxIncrement.Config("unused", "job", 7, "hash", "episodes",
            Set.of("fingerprint"), "earliest", Path.of("unused.checkpoint"));

    @Test
    void reportsEveryThirtySecondsDuringAnIncrementAndStopsTheHeartbeat() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        MockProducer<String, byte[]> producer = producer();
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            assertEquals(7, TsxIncrement.loop(CONFIG, beforeCheckpoint -> {
                timer.advance(Duration.ofSeconds(125));
                beforeCheckpoint.run();
                increments.incrementAndGet();
                return new TsxIncrement.Result(Map.of(), 7);
            }, status, Duration.ofSeconds(1), () -> increments.get() == 1, clock::nanos, timer::advance));
            assertEquals(Duration.ofSeconds(30).toNanos(), timer.period);
            List<JsonNode> records = records(producer);
            assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 7L, 7L),
                    records.stream().map(n -> n.path("records").asLong()).toList());
            assertEquals("stopped", records.get(records.size() - 1).path("state").asText());
            assertGaps(records);
            assertTrue(timer.future.isCancelled());
            timer.advance(Duration.ofMinutes(2));
            assertEquals(records.size(), producer.history().size());
        }
    }

    @Test
    void reportsDuringTheWaitBetweenIncrements() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        MockProducer<String, byte[]> producer = producer();
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            assertEquals(2, TsxIncrement.loop(CONFIG, beforeCheckpoint -> {
                beforeCheckpoint.run();
                increments.incrementAndGet();
                return new TsxIncrement.Result(Map.of(), 2);
            }, status, Duration.ofSeconds(95), () -> increments.get() == 1, clock::nanos, timer::advance));
        }
        List<JsonNode> records = records(producer);
        assertEquals(List.of(0L, 2L, 2L, 2L, 2L, 2L), records.stream().map(n -> n.path("records").asLong()).toList());
        assertGaps(records);
    }

    @Test
    void reportsAnyRuntimeFailureAndKeepsTheCommittedCount() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        MockProducer<String, byte[]> producer = producer();
        RuntimeException source = new IllegalStateException("checkpoint cannot be read");
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            assertSame(source, assertThrows(RuntimeException.class, () -> TsxIncrement.loop(CONFIG, beforeCheckpoint -> {
                if (increments.getAndIncrement() == 1) throw source;
                beforeCheckpoint.run();
                return new TsxIncrement.Result(Map.of(), 3);
            }, status, Duration.ofSeconds(1), () -> false, clock::nanos, timer::advance)));
        }
        List<JsonNode> records = records(producer);
        JsonNode failed = records.get(records.size() - 1);
        assertEquals("failed", failed.path("state").asText());
        assertEquals(source.getMessage(), failed.path("error").asText());
        assertEquals(3, failed.path("records").asLong());
        assertTrue(timer.future.isCancelled());
    }

    @Test
    void statusFailureDoesNotReplaceTheSourceFailure() {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        RuntimeException broker = new IllegalStateException("status broker refused the record");
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
                if (history().size() == 1) return CompletableFuture.failedFuture(broker);
                return super.send(record);
            }
        };
        RuntimeException source = new TsxIncrementException("source fingerprint mismatch", new IllegalStateException("source fingerprint mismatch"));
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            assertSame(source, assertThrows(RuntimeException.class, () -> TsxIncrement.loop(CONFIG,
                    beforeCheckpoint -> { throw source; }, status, Duration.ofSeconds(1), () -> false,
                    clock::nanos, timer::advance)));
        }
        assertEquals(1, source.getSuppressed().length);
        assertSame(broker, source.getSuppressed()[0].getCause());
    }

    @Test
    void aHeartbeatFailurePreventsTheNextCheckpoint() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        RuntimeException broker = new IllegalStateException("heartbeat was not acknowledged");
        AtomicInteger sends = new AtomicInteger();
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
                if (sends.incrementAndGet() == 2) return CompletableFuture.failedFuture(broker);
                return super.send(record);
            }
        };
        AtomicInteger checkpoints = new AtomicInteger();
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> TsxIncrement.loop(CONFIG, beforeCheckpoint -> {
                timer.advance(Duration.ofSeconds(45));
                beforeCheckpoint.run();
                checkpoints.incrementAndGet();
                return new TsxIncrement.Result(Map.of(), 5);
            }, status, Duration.ofSeconds(1), () -> false, clock::nanos, timer::advance));
            assertSame(broker, thrown.getCause());
        }
        assertEquals(0, checkpoints.get());
        List<JsonNode> records = records(producer);
        assertEquals("failed", records.get(records.size() - 1).path("state").asText());
        assertEquals(0, records.get(records.size() - 1).path("records").asLong());
        assertTrue(timer.future.isCancelled());
    }

    @Test
    void aHeartbeatFailureDuringTheIdleIntervalPreventsAnotherIncrement() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        RuntimeException broker = new IllegalStateException("idle heartbeat was not acknowledged");
        AtomicInteger sends = new AtomicInteger();
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
                if (sends.incrementAndGet() == 3) return CompletableFuture.failedFuture(broker);
                return super.send(record);
            }
        };
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> TsxIncrement.loop(CONFIG, beforeCheckpoint -> {
                beforeCheckpoint.run();
                increments.incrementAndGet();
                return new TsxIncrement.Result(Map.of(), 3);
            }, status, Duration.ofSeconds(95), () -> false, clock::nanos, timer::advance));
            assertSame(broker, thrown.getCause());
        }
        assertEquals(1, increments.get());
        JsonNode failed = records(producer).get(producer.history().size() - 1);
        assertEquals("failed", failed.path("state").asText());
        assertEquals(3, failed.path("records").asLong());
    }

    @Test
    void heartbeatsUseTheCurrentProgressSnapshot() throws Exception {
        FakeClock clock = new FakeClock();
        FakeScheduler timer = new FakeScheduler(clock);
        MockProducer<String, byte[]> producer = producer();
        TsxState state = new TsxState(Map.of());
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus("status", producer, clock, timer)) {
            TsxIncrement.loop(CONFIG, beforeCheckpoint -> {
                state.progress("chain", 5_000_000, List.of("missing"), 2);
                timer.advance(Duration.ofSeconds(35));
                beforeCheckpoint.run();
                increments.incrementAndGet();
                return new TsxIncrement.Result(Map.of(), 4);
            }, status, Duration.ofSeconds(1), () -> increments.get() == 1, clock::nanos, timer::advance, state::progress);
        }
        List<JsonNode> records = records(producer);
        assertTrue(records.get(0).path("watermark").isNull());
        JsonNode heartbeat = records.get(1);
        assertEquals("1970-01-01T00:00:05Z", heartbeat.path("watermark").asText());
        assertEquals("missing", heartbeat.path("waitingFor").get(0).asText());
        assertEquals(1, heartbeat.path("waitingForCount").asInt());
        assertEquals(2, heartbeat.path("skippedBlocks").asLong());
        assertEquals(0, heartbeat.path("records").asLong());
        assertEquals(4, records.get(records.size() - 1).path("records").asLong());
    }

    static MockProducer<String, byte[]> producer() {
        return new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    }

    static List<JsonNode> records(MockProducer<String, byte[]> producer) throws Exception {
        ObjectMapper json = new ObjectMapper();
        java.util.ArrayList<JsonNode> records = new java.util.ArrayList<>();
        for (ProducerRecord<String, byte[]> r : producer.history()) {
            assertEquals("status", r.topic());
            assertEquals(CONFIG.job(), r.key());
            JsonNode n = json.readTree(r.value());
            assertEquals("opentsx.dag-status.v1", n.path("schema").asText());
            assertEquals(CONFIG.job(), n.path("job").asText());
            assertEquals(CONFIG.planVersion(), n.path("planVersion").asInt());
            assertEquals("A", n.path("track").asText());
            assertEquals(CONFIG.specHash(), n.path("specHash").asText());
            if (!n.path("state").asText().equals("failed")) assertFalse(n.has("error"));
            records.add(n);
        }
        return records;
    }

    static void assertGaps(List<JsonNode> records) {
        for (int i = 1; i < records.size(); i++) {
            Duration gap = Duration.between(Instant.parse(records.get(i - 1).path("at").asText()),
                    Instant.parse(records.get(i).path("at").asText()));
            assertTrue(gap.compareTo(Duration.ofSeconds(30)) <= 0, "gap " + gap);
        }
    }

    static final class FakeClock extends Clock {
        long now;
        long nanos() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return Instant.parse("2026-11-16T00:00:00Z").plusNanos(now); }
    }

    static final class FakeScheduler extends ScheduledThreadPoolExecutor {
        final FakeClock clock;
        final Task future = new Task();
        Runnable action;
        long next;
        long period;

        FakeScheduler(FakeClock clock) { super(1); this.clock = clock; }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            action = command;
            next = clock.now + unit.toNanos(initialDelay);
            this.period = unit.toNanos(period);
            return future;
        }

        void advance(Duration duration) {
            long end = clock.now + duration.toNanos();
            while (action != null && !future.isCancelled() && next <= end) {
                clock.now = next;
                next += period;
                action.run();
            }
            clock.now = end;
        }
    }

    static final class Task extends FutureTask<Void> implements RunnableScheduledFuture<Void> {
        Task() { super(() -> null); }
        @Override public boolean isPeriodic() { return true; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
