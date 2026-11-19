package org.opentsx.store.iceberg;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        EpisodeLogSync.Result r = EpisodeLogSync.sync(consumer, LAYOUT, store, Map.of(), 2, TIMEOUT,
                new Patience(TIMEOUT, Duration.ofMillis(1)));
        assertEquals(eps.size(), r.written());
        assertEquals(Map.of(P0, 5L), r.nextPositions());
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

    @Test
    void aFirstReadWithoutASavedPositionLosesNothing() {
        MockConsumer<byte[], byte[]> consumer = fill(new MockConsumer<>(OffsetResetStrategy.EARLIEST), episodes(3), 5);
        EpisodeLogSync.Result r = EpisodeLogSync.sync(consumer, LAYOUT, new InMemorySeriesStore(), Map.of(), 2, TIMEOUT);
        assertEquals(0, r.lostToRetention());
        assertEquals(Map.of(P0, 8L), r.nextPositions());
    }
}
