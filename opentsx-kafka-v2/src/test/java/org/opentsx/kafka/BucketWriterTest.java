package org.opentsx.kafka;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Buckets;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.BucketState;
import org.opentsx.model.v2.Episode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketWriterTest {

    private final TopicLayout layout = new TopicLayout("wind");
    private final OpenTsxAvro avro = new OpenTsxAvro();

    @Test
    void writesOpenManifestEpisodesAndSealedManifest() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        List<Episode> episodes = WindData.episodes(2, List.of("wind_speed"), 3, 60, 1);

        BucketManifest sealed;
        try (BucketWriter w = BucketWriter.open(producer, layout, index, "b-1", "exp", Map.of("scenario", "s1"))) {
            episodes.forEach(w::append);
            sealed = w.seal();
        }

        List<ProducerRecord<String, byte[]>> sent = producer.history();
        assertEquals(episodes.size() + 2, sent.size());
        BucketManifest open = avro.decode(sent.get(0).value(), BucketManifest.class);
        assertEquals(BucketState.OPEN, open.getState());
        assertEquals(layout.buckets(), sent.get(0).topic());
        assertEquals(sealed, avro.decode(sent.get(sent.size() - 1).value(), BucketManifest.class));

        assertEquals(BucketState.SEALED, sealed.getState());
        assertEquals(episodes.size(), sealed.getEpisodeCount());
        assertEquals(Buckets.checksum(episodes.stream().map(Episode::getEpisodeId).toList()), sealed.getChecksum());
        assertEquals(episodes.get(0).getTStart(), sealed.getTMin());

        ProducerRecord<String, byte[]> first = sent.get(1);
        Episode e0 = avro.decode(first.value(), Episode.class);
        assertEquals(layout.episodes(), first.topic());
        assertEquals(e0.getSeries().getSeriesId(), first.key());
        assertEquals("b-1", e0.getBucketId());
        assertEquals(Micros.of(e0.getTStart()) / 1000, first.timestamp());
        assertEquals("b-1", new String(first.headers().lastHeader(TopicLayout.HEADER_BUCKET).value(), StandardCharsets.UTF_8));

        assertEquals(episodes.size(), index.bucket("b-1").size());
        assertEquals(6, index.find(null, Long.MIN_VALUE, Long.MAX_VALUE).size());
    }

    @Test
    void doesNotSealWhenAnEpisodeFails() {
        MockProducer<String, byte[]> producer = new MockProducer<>(false, new StringSerializer(), new ByteArraySerializer());
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        // the OPEN manifest is sent synchronously, so acknowledge it from another thread
        Thread acker = new Thread(() -> {
            while (producer.history().isEmpty()) {
                Thread.onSpinWait();
            }
            producer.completeNext();
        });
        acker.start();
        BucketWriter w = BucketWriter.open(producer, layout, index, "b-2", "exp", Map.of());
        List<Episode> episodes = WindData.episodes(1, List.of("wind_speed"), 2, 10, 2);
        w.append(episodes.get(0));
        w.append(episodes.get(1));
        producer.completeNext();
        producer.errorNext(new RuntimeException("broker gone"));
        assertThrows(BucketWriter.BucketWriteException.class, w::seal);
        assertEquals(1, index.size());
    }

    @Test
    void doesNotSealWhenTheIndexFails() {
        // KafkaProducer runs callbacks on its I/O thread and only logs what they throw
        List<RuntimeException> loggedByProducer = new ArrayList<>();
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback) {
                return super.send(record, callback == null ? null : (md, ex) -> {
                    try {
                        callback.onCompletion(md, ex);
                    } catch (RuntimeException e) {
                        loggedByProducer.add(e);
                    }
                });
            }
        };
        IllegalStateException full = new IllegalStateException("index full");
        EpisodeIndex failing = new InMemoryEpisodeIndex() {
            @Override
            public synchronized void add(IndexEntry entry) {
                throw full;
            }
        };
        BucketWriter w = BucketWriter.open(producer, layout, failing, "b-4", "exp", Map.of());
        CompletableFuture<IndexEntry> f = w.append(WindData.episodes(1, List.of("wind_speed"), 1, 10, 4).get(0));
        assertEquals(List.of(), loggedByProducer);
        assertTrue(f.isCompletedExceptionally(), "the caller learns that the episode is not indexed");
        BucketWriter.BucketWriteException e = assertThrows(BucketWriter.BucketWriteException.class, w::seal);
        assertSame(full, e.getCause());
        assertEquals(List.of(BucketState.OPEN), manifests(producer));
    }

    @Test
    void doesNotSealWhenInterruptedBeforeEveryEpisodeIsAcknowledged() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback) {
                return record.topic().equals(layout.episodes()) ? new CompletableFuture<>() : super.send(record, callback);
            }
        };
        BucketWriter w = BucketWriter.open(producer, layout, new InMemoryEpisodeIndex(), "b-5", "exp", Map.of());
        w.append(WindData.episodes(1, List.of("wind_speed"), 1, 10, 5).get(0));
        Thread.currentThread().interrupt();
        assertThrows(BucketWriter.BucketWriteException.class, w::seal);
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertEquals(List.of(BucketState.OPEN), manifests(producer));
    }

    @Test
    void doesNotSealWhenTheAcknowledgementsTakeLongerThanTheWait() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback) {
                return record.topic().equals(layout.episodes()) ? new CompletableFuture<>() : super.send(record, callback);
            }
        };
        BucketWriter w = BucketWriter.open(producer, layout, new InMemoryEpisodeIndex(), "b-7", "exp", Map.of());
        w.ackWait = Duration.ofMillis(10);
        w.append(WindData.episodes(1, List.of("wind_speed"), 1, 10, 7).get(0));
        BucketWriter.BucketWriteException e = assertThrows(BucketWriter.BucketWriteException.class, w::seal);
        assertEquals("bucket b-7 not sealed: episodes not acknowledged within PT0.01S", e.getMessage());
        assertInstanceOf(TimeoutException.class, e.getCause());
        assertEquals(List.of(BucketState.OPEN), manifests(producer));
    }

    @Test
    void anInterruptWhileAManifestIsSentFailsTheWriterAndKeepsTheInterrupt() {
        MockProducer<String, byte[]> producer = new MockProducer<>(false, new StringSerializer(), new ByteArraySerializer());
        Thread.currentThread().interrupt();
        BucketWriter.BucketWriteException e = assertThrows(BucketWriter.BucketWriteException.class,
                () -> BucketWriter.open(producer, layout, new InMemoryEpisodeIndex(), "b-6", "exp", Map.of()));
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertEquals("cannot write OPEN manifest of bucket b-6", e.getMessage());
        assertInstanceOf(InterruptedException.class, e.getCause());
    }

    private List<BucketState> manifests(MockProducer<String, byte[]> producer) {
        return producer.history().stream().filter(r -> r.topic().equals(layout.buckets()))
                .map(r -> avro.decode(r.value(), BucketManifest.class).getState()).toList();
    }

    @Test
    void rejectsEpisodesOfAnotherBucket() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        Episode foreign = Episode.newBuilder(WindData.episodes(1, List.of("rpm"), 1, 10, 3).get(0)).setBucketId("other").build();
        try (BucketWriter w = BucketWriter.open(producer, layout, new InMemoryEpisodeIndex(), "b-3", "exp", Map.of())) {
            assertThrows(IllegalArgumentException.class, () -> w.append(foreign));
        }
    }

    @Test
    void indexKeepsFirstPositionAndQueriesByTimeAndSeries(@TempDir Path dir) {
        Path file = dir.resolve("index.tsv");
        try (FileEpisodeIndex index = new FileEpisodeIndex(file)) {
            index.add(new IndexEntry("e1", "s1", 0, 10, "b", "t", 0, 5));
            index.add(new IndexEntry("e1", "s1", 0, 10, "b", "t", 0, 9)); // duplicate written later
            index.add(new IndexEntry("e2", "s2", 10, 20, null, "t", 1, 3));
        }
        try (FileEpisodeIndex reloaded = new FileEpisodeIndex(file)) {
            assertEquals(2, reloaded.size());
            assertEquals(5, reloaded.bucket("b").get(0).offset());
            assertEquals(List.of("e2"), reloaded.find(Set.of("s2"), 15, 16).stream().map(IndexEntry::episodeId).toList());
            assertTrue(reloaded.find(null, 20, 30).isEmpty());
            assertNull(reloaded.find(Set.of("s2"), 0, 100).get(0).bucketId());
        }
    }

    @Test
    void aBadIndexFileNamesTheFileAndTheLine(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("index.tsv"), "e1\ts1\t0\t10\tb\tt\t0\t5\ne2\ts2\t10\n");
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class, () -> new FileEpisodeIndex(file));
        assertEquals(file + ":2: malformed index line \"e2\ts2\t10\"; expected 8 tab-separated fields, see the header line",
                malformed.getMessage());

        Path notANumber = Files.writeString(dir.resolve("offset.tsv"), "#header\ne1\ts1\t0\t10\tb\tt\t0\tfive\n");
        IllegalArgumentException offset = assertThrows(IllegalArgumentException.class, () -> new FileEpisodeIndex(notANumber));
        assertTrue(offset.getMessage().startsWith(notANumber + ":2: "), offset.getMessage());

        UncheckedIOException unreadable = assertThrows(UncheckedIOException.class, () -> new FileEpisodeIndex(dir));
        assertEquals("cannot open episode index " + dir, unreadable.getMessage());
    }

    @Test
    void layoutNamesAndValidation() {
        assertEquals("tsx.wind.episodes.v2", layout.episodes());
        assertEquals("tsx.wind.series-stats.v2", layout.seriesStats());
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("Wind Park"));
    }
}
