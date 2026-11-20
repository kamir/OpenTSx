package org.opentsx.store.iceberg;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.Patience;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.InMemorySeriesStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeLogSyncTest {

    private static final TopicLayout LAYOUT = new TopicLayout("sync");
    private static final TopicPartition P0 = new TopicPartition(LAYOUT.episodes(), 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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

    /** What a consumer throws when KafScale refuses the fetch (REQUEST_TIMED_OUT, error code 7). */
    static IllegalStateException refusal() {
        return new IllegalStateException("Unexpected error code 7 while fetching at offset 0 from topic-partition " + P0);
    }

    static List<Episode> episodes(int n) {
        List<Episode> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Episodes.regular(SeriesKeys.of("synthetic.temperature", Map.of(), "Cel"),
                    i * 3_000_000L, 1_000_000L, new double[]{i, i + 1, i + 2}).source("synthetic").build());
        }
        return out;
    }

    /** Partition 0 of the episodes topic holds {@code episodes} from offset {@code logStart} on. */
    static <C extends MockConsumer<byte[], byte[]>> C fill(C c, List<Episode> episodes, long logStart) {
        OpenTsxAvro avro = new OpenTsxAvro();
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(LAYOUT.episodes(), List.of(new PartitionInfo(LAYOUT.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        c.updateBeginningOffsets(Map.of(P0, logStart));
        c.updateEndOffsets(Map.of(P0, logStart + episodes.size()));
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        for (int i = 0; i < episodes.size(); i++) {
            records.add(new ConsumerRecord<>(LAYOUT.episodes(), 0, logStart + i, null, avro.encode(episodes.get(i))));
        }
        c.schedulePollTask(() -> records.forEach(c::addRecord));
        return c;
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

    @Test
    void aRefusedFetchIsWaitedOutAndEveryRecordIsCopied() {
        List<Episode> eps = episodes(5);
        Refusing consumer = fill(new Refusing(refusal()), eps, 0);
        InMemorySeriesStore store = new InMemorySeriesStore();
        List<Long> pauses = new ArrayList<>();
        EpisodeLogSync.Result r = EpisodeLogSync.sync(consumer, LAYOUT, store, Map.of(), 2, TIMEOUT,
                new Patience(TIMEOUT, Duration.ofSeconds(2), () -> 0L, pauses::add), System::nanoTime);
        assertEquals(eps.size(), r.written());
        assertEquals(Map.of(P0, 5L), r.nextPositions());
        assertEquals(List.of(2000L), pauses, "the refusal is waited out through the sleeper, not with a real sleep");
    }

    @Test
    void aMissingTopicFails() {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EpisodeLogSync.sync(consumer, LAYOUT, new InMemorySeriesStore(), Map.of(), 2, TIMEOUT));
        assertEquals("tsx.sync.episodes.v2: no such topic; check the domain, or create the topic first", e.getMessage());
    }

    @Test
    void aLogStartAboveTheSavedPositionIsLoggedAndCounted() {
        MockConsumer<byte[], byte[]> consumer = fill(new MockConsumer<>(OffsetResetStrategy.EARLIEST), episodes(3), 5);
        EpisodeLogSync.Result[] r = new EpisodeLogSync.Result[1];
        String err = stderrOf(() -> r[0] = EpisodeLogSync.sync(consumer, LAYOUT, new InMemorySeriesStore(), Map.of(P0, 2L), 2, TIMEOUT));
        assertTrue(err.contains("WARN org.opentsx.store.iceberg.EpisodeLogSync - retention deleted records before they were read;"
                + " reading on from the log start topic=tsx.sync.episodes.v2 partition=0 position=2 logStart=5 lost=3"), err);
        assertEquals(3, r[0].lostToRetention());
        assertEquals(3, r[0].written());
    }

    /** Partition 0 of the episodes topic holds {@code values} from offset 0 on, each at the offset of its index. */
    static MockConsumer<byte[], byte[]> log(List<byte[]> values) {
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(LAYOUT.episodes(), List.of(new PartitionInfo(LAYOUT.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        c.updateBeginningOffsets(Map.of(P0, 0L));
        c.updateEndOffsets(Map.of(P0, (long) values.size()));
        c.schedulePollTask(() -> {
            for (int i = 0; i < values.size(); i++) {
                c.addRecord(new ConsumerRecord<>(LAYOUT.episodes(), 0, i, null, values.get(i)));
            }
        });
        return c;
    }

    /** The WARN lines slf4j-simple wrote, from the level on, without the thread name in front. */
    static List<String> warnings(String err) {
        return err.lines().filter(l -> l.contains("] WARN ")).map(l -> l.substring(l.indexOf("] ") + 2)).toList();
    }

    @Test
    void anUndecodableRecordIsLoggedOncePerPartitionWithItsOffsetAndCounted() {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Episode> eps = episodes(2);
        MockConsumer<byte[], byte[]> consumer = log(List.of(avro.encode(eps.get(0)), new byte[]{1, 2, 3}, new byte[]{4},
                avro.encode(eps.get(1))));
        InMemorySeriesStore store = new InMemorySeriesStore();
        EpisodeLogSync.Result[] r = new EpisodeLogSync.Result[1];

        String err = stderrOf(() -> r[0] = EpisodeLogSync.sync(consumer, LAYOUT, store, Map.of(), 2, TIMEOUT));

        assertEquals(2, r[0].undecodable());
        assertEquals(2, r[0].written());
        assertEquals(Map.of(P0, 4L), r[0].nextPositions());
        assertEquals(List.of("WARN org.opentsx.store.iceberg.EpisodeLogSync - undecodable record skipped and counted in"
                + " undecodable; later ones of this partition are only counted topic=tsx.sync.episodes.v2 partition=0 offset=1"
                + " cause=unrecognised payload framing"), warnings(err));
    }

    @Test
    void anInvalidEpisodeIsCountedLoggedOncePerPartitionAndNotWritten() {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Episode> eps = episodes(4);
        MockConsumer<byte[], byte[]> consumer = log(List.of(avro.encode(eps.get(0)),
                avro.encode(Episode.newBuilder(eps.get(1)).setCount(4).build()),
                avro.encode(Episode.newBuilder(eps.get(2)).setIntervalMicros(null).build()), avro.encode(eps.get(3))));
        InMemorySeriesStore store = new InMemorySeriesStore();
        EpisodeLogSync.Result[] r = new EpisodeLogSync.Result[1];

        String err = stderrOf(() -> r[0] = EpisodeLogSync.sync(consumer, LAYOUT, store, Map.of(), 2, TIMEOUT));

        assertEquals(2, r[0].invalid());
        assertEquals(0, r[0].undecodable());
        assertEquals(2, r[0].written());
        assertEquals(Map.of(P0, 4L), r[0].nextPositions());
        assertTrue(store.episode(eps.get(1).getEpisodeId()).isEmpty(), "an invalid episode is not written");
        assertTrue(store.episode(eps.get(2).getEpisodeId()).isEmpty(), "an invalid episode is not written");
        assertEquals(List.of("WARN org.opentsx.store.iceberg.EpisodeLogSync - invalid episode skipped and counted in"
                + " invalid; later ones of this partition are only counted topic=tsx.sync.episodes.v2 partition=0 offset=1"
                + " cause=count: \"4\" differs from the length 3 of values; expected the number of values"), warnings(err));
    }

    @Test
    void aGapThatOpensDuringTheRunIsCountedAndLogged() {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Episode> eps = episodes(3);
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(LAYOUT.episodes(), List.of(new PartitionInfo(LAYOUT.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        consumer.updateBeginningOffsets(Map.of(P0, 0L));
        consumer.updateEndOffsets(Map.of(P0, 6L));
        long[] offsets = {0, 1, 5};
        consumer.schedulePollTask(() -> {
            for (int i = 0; i < offsets.length; i++) {
                consumer.addRecord(new ConsumerRecord<>(LAYOUT.episodes(), 0, offsets[i], null, avro.encode(eps.get(i))));
            }
        });
        EpisodeLogSync.Result[] r = new EpisodeLogSync.Result[1];

        String err = stderrOf(() -> r[0] = EpisodeLogSync.sync(consumer, LAYOUT, new InMemorySeriesStore(), Map.of(), 2, TIMEOUT));

        assertEquals(3, r[0].written());
        assertEquals(3, r[0].lostToRetention(), "offsets 2, 3 and 4 were deleted while the sync read the partition");
        assertEquals(Map.of(P0, 6L), r[0].nextPositions());
        assertEquals(List.of("WARN org.opentsx.store.iceberg.EpisodeLogSync - retention deleted records before they were"
                + " read; reading on from the log start topic=tsx.sync.episodes.v2 partition=0 position=2 logStart=5 lost=3"),
                warnings(err));
    }

    @Test
    void aLogStartThatMovesPastTheRemainingRecordsDuringTheRunIsCountedAndTheSyncEnds() {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(LAYOUT.episodes(), List.of(new PartitionInfo(LAYOUT.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        consumer.updateBeginningOffsets(Map.of(P0, 0L));
        consumer.updateEndOffsets(Map.of(P0, 3L));
        consumer.schedulePollTask(() -> consumer.updateBeginningOffsets(Map.of(P0, 3L)));
        EpisodeLogSync.Result[] r = new EpisodeLogSync.Result[1];

        String err = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> stderrOf(() -> r[0] = EpisodeLogSync.sync(
                consumer, LAYOUT, new InMemorySeriesStore(), Map.of(), 2, Duration.ofSeconds(5))));

        assertEquals(0, r[0].written());
        assertEquals(3, r[0].lostToRetention(), "offsets 0 to 2 were deleted before they were read");
        assertEquals(Map.of(P0, 3L), r[0].nextPositions(), "the next sync reads on from offset 3");
        assertEquals(List.of("WARN org.opentsx.store.iceberg.EpisodeLogSync - retention deleted records before they were"
                + " read; reading on from the log start topic=tsx.sync.episodes.v2 partition=0 position=0 logStart=3 lost=3"),
                warnings(err));
    }

    /**
     * A broker that answers the first requests and then delivers nothing; each poll moves the clock by {@code pollNanos}.
     * As KafkaConsumer 3.9.2, a later log start request with less than a millisecond answers an empty map without
     * asking the broker, and one with more gets no answer and throws the client's TimeoutException.
     */
    static final class Stalled extends MockConsumer<byte[], byte[]> {
        private final long pollNanos;
        private boolean polled;
        long nanos;

        Stalled(long pollNanos) {
            super(OffsetResetStrategy.EARLIEST);
            this.pollNanos = pollNanos;
            Node node = new Node(0, "localhost", 9092);
            updatePartitions(LAYOUT.episodes(), List.of(new PartitionInfo(LAYOUT.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
            updateBeginningOffsets(Map.of(P0, 0L));
            updateEndOffsets(Map.of(P0, 3L));
        }

        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            polled = true;
            nanos += pollNanos;
            return ConsumerRecords.empty();
        }

        @Override
        public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            if (!polled) {
                return super.beginningOffsets(partitions, timeout);
            }
            if (timeout.toMillis() == 0) {
                return Map.of();
            }
            nanos += timeout.toNanos();
            throw new TimeoutException("Failed to get offsets by times in " + timeout.toMillis() + "ms");
        }
    }

    static EpisodeLogSync.Result syncOn(Stalled consumer) {
        return EpisodeLogSync.sync(consumer, LAYOUT, new InMemorySeriesStore(), Map.of(), 2, TIMEOUT,
                new Patience(TIMEOUT, Duration.ofSeconds(2), () -> consumer.nanos, millis -> { }), () -> consumer.nanos);
    }

    @Test
    void lessThanAMillisecondLeftAfterAnEmptyPollIsATimeout() {
        Stalled consumer = new Stalled(TIMEOUT.toNanos() - 500_000);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> syncOn(consumer));

        assertEquals("timed out syncing tsx.sync.episodes.v2", e.getMessage());
    }

    @Test
    void aLogStartRequestThatGetsNoAnswerInTheTimeLeftIsATimeout() {
        Stalled consumer = new Stalled(Duration.ofMillis(500).toNanos());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> syncOn(consumer));

        assertEquals("timed out syncing tsx.sync.episodes.v2", e.getMessage());
        assertEquals("Failed to get offsets by times in 29500ms", e.getCause().getMessage());
        assertTrue(e.getCause() instanceof TimeoutException, e.getCause().toString());
    }

    /**
     * A broker that does not answer the metadata requests before the copy: each request takes the time it is given
     * on the clock {@code nanos} and throws the client's TimeoutException, as KafkaConsumer does. The partitions come
     * after {@code partitionsNanos}, or never when that is negative.
     */
    static final class Silent extends MockConsumer<byte[], byte[]> {
        private final long partitionsNanos;
        long nanos;

        Silent(long partitionsNanos) {
            super(OffsetResetStrategy.EARLIEST);
            this.partitionsNanos = partitionsNanos;
            Node node = new Node(0, "localhost", 9092);
            updatePartitions(LAYOUT.episodes(), List.of(new PartitionInfo(LAYOUT.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        }

        @Override
        public synchronized List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
            if (partitionsNanos < 0) {
                nanos += timeout.toNanos();
                throw new TimeoutException("Timeout expired while fetching topic metadata");
            }
            nanos += partitionsNanos;
            return super.partitionsFor(topic, timeout);
        }

        @Override
        public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            nanos += timeout.toNanos();
            throw new TimeoutException("Failed to get offsets by times in " + timeout.toMillis() + "ms");
        }
    }

    @Test
    void theRequestsBeforeTheCopyShareTheTimeoutAndATimeoutNamesTheTopic() {
        for (long partitionsNanos : new long[]{-1, TIMEOUT.toNanos() - Duration.ofSeconds(1).toNanos()}) {
            Silent consumer = new Silent(partitionsNanos);

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> EpisodeLogSync.sync(consumer, LAYOUT,
                    new InMemorySeriesStore(), Map.of(), 2, TIMEOUT,
                    new Patience(TIMEOUT, Duration.ofSeconds(2), () -> consumer.nanos, millis -> { }), () -> consumer.nanos));

            assertEquals("timed out syncing tsx.sync.episodes.v2", e.getMessage());
            assertTrue(e.getCause() instanceof TimeoutException, String.valueOf(e.getCause()));
            assertTrue(consumer.nanos <= TIMEOUT.toNanos(), "the call ends within the timeout: " + consumer.nanos + " ns");
        }
    }

    @Test
    void aFirstReadWithoutASavedPositionLosesNothing() {
        MockConsumer<byte[], byte[]> consumer = fill(new MockConsumer<>(OffsetResetStrategy.EARLIEST), episodes(3), 5);
        EpisodeLogSync.Result r = EpisodeLogSync.sync(consumer, LAYOUT, new InMemorySeriesStore(), Map.of(), 2, TIMEOUT);
        assertEquals(0, r.lostToRetention());
        assertEquals(Map.of(P0, 8L), r.nextPositions());
    }
}
