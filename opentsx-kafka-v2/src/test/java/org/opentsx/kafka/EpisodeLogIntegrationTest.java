package org.opentsx.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.BucketState;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end against a real broker: write buckets, replay them, rebuild the index from the log.
 * Runs only with KAFSCALE_BOOTSTRAP set (KafScale or Apache Kafka).
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EpisodeLogIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
    private final TopicLayout layout = new TopicLayout("it-" + UUID.randomUUID().toString().substring(0, 8));
    private KafkaProducer<String, byte[]> producer;
    private KafkaConsumer<byte[], byte[]> consumer;

    @BeforeAll
    void setUp() {
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            layout.provision(admin, 3, (short) 1);
        }
        producer = KafScaleClients.producer(bootstrap);
        consumer = KafScaleClients.consumer(bootstrap);
    }

    @AfterAll
    void tearDown() {
        producer.close();
        consumer.close();
    }

    @Test
    void bucketRoundTripIsVerifiedAndInEventTimeOrder() {
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        List<Episode> written = WindData.episodes(3, List.of("wind_speed", "power_active"), 4, 120, 21);
        BucketManifest sealed;
        try (BucketWriter w = BucketWriter.open(producer, layout, index, "wind-farm-run-1", Map.of("scenario", "it"))) {
            written.forEach(w::append);
            sealed = w.seal();
        }

        List<Episode> replayed = new ArrayList<>();
        ReplayResult result = new ReplayService(consumer, layout, index, TIMEOUT)
                .replay(ReplayRequest.bucket(sealed.getBucketId()), replayed::add);

        assertTrue(result.verified(), "count and checksum must match the SEALED manifest: " + result);
        assertEquals(written.size(), replayed.size());
        assertEquals(0, result.missing());
        List<Episode> expected = written.stream()
                .map(e -> Episode.newBuilder(e).setBucketId(sealed.getBucketId()).build())
                .sorted(Comparator.comparing(Episode::getTStart).thenComparing(Episode::getEpisodeId))
                .toList();
        assertEquals(expected, replayed);

        // same result in small chunks (bounded memory)
        List<Episode> chunked = new ArrayList<>();
        new ReplayService(consumer, layout, index, TIMEOUT)
                .replay(ReplayRequest.bucket(sealed.getBucketId()).withMaxEpisodesInMemory(5), chunked::add);
        assertEquals(expected, chunked);
    }

    @Test
    void rangeReplayAndIndexRebuildFromTheLog() {
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        List<Episode> written = WindData.episodes(2, List.of("rpm"), 6, 60, 22);
        try (BucketWriter w = BucketWriter.open(producer, layout, index, "range-run", Map.of())) {
            written.forEach(w::append);
        }
        String series = written.get(0).getSeries().getSeriesId();
        long from = WindData.T0 + 90 * 1_000_000L;   // inside episode 1
        long to = WindData.T0 + 200 * 1_000_000L;    // inside episode 3

        List<Episode> got = new ArrayList<>();
        ReplayResult r = new ReplayService(consumer, layout, index, TIMEOUT)
                .replay(ReplayRequest.range(Set.of(series), from, to), got::add);
        assertEquals(3, got.size()); // episodes 1, 2, 3 overlap [90 s, 200 s)
        assertTrue(got.stream().allMatch(e -> e.getSeries().getSeriesId().equals(series)));
        assertFalse(r.verified());

        // the index is derived state: rebuilding it from the log gives the same positions
        InMemoryEpisodeIndex rebuilt = new InMemoryEpisodeIndex();
        EpisodeIndexer.Result scan = EpisodeIndexer.rebuild(consumer, layout, rebuilt, TIMEOUT);
        assertEquals(0, scan.skipped());
        for (IndexEntry e : index.entries()) {
            assertEquals(e, rebuilt.bucket(e.bucketId()).stream()
                    .filter(x -> x.episodeId().equals(e.episodeId())).findFirst().orElseThrow());
        }
    }

    @Test
    void duplicateWritesAreDeliveredOnceAndUnsealedBucketsAreNotVerified() throws Exception {
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 3, 30, 23);
        BucketWriter w = BucketWriter.open(producer, layout, index, "unsealed-with-dup", Map.of());
        written.forEach(w::append);
        producer.flush();
        // simulate a producer retry without idempotence: the same episode lands twice in the log
        Episode dup = Episode.newBuilder(written.get(0)).setBucketId(w.bucketId()).build();
        producer.send(new ProducerRecord<>(layout.episodes(), dup.getSeries().getSeriesId(), new OpenTsxAvro().encode(dup))).get();

        InMemoryEpisodeIndex rebuilt = new InMemoryEpisodeIndex();
        EpisodeIndexer.rebuild(consumer, layout, rebuilt, TIMEOUT);
        List<Episode> got = new ArrayList<>();
        ReplayResult r = new ReplayService(consumer, layout, rebuilt, TIMEOUT)
                .replay(ReplayRequest.bucket(w.bucketId()), got::add);
        assertEquals(3, got.size());
        assertFalse(r.verified(), "an OPEN bucket must not verify");

        BucketManifest sealed = w.seal();
        assertEquals(BucketState.SEALED, BucketCatalog.load(consumer, layout, TIMEOUT).get(sealed.getBucketId()).getState());
        assertTrue(new ReplayService(consumer, layout, rebuilt, TIMEOUT)
                .replay(ReplayRequest.bucket(w.bucketId()), e -> { }).verified());
    }

    @Test
    void pacedReplayFollowsEventTime() {
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        List<Episode> written = WindData.episodes(1, List.of("rpm"), 4, 10, 24); // tStart 0 s, 10 s, 20 s, 30 s
        try (BucketWriter w = BucketWriter.open(producer, layout, index, "paced", Map.of())) {
            written.forEach(w::append);
        }
        List<Long> wall = new ArrayList<>();
        List<Long> event = new ArrayList<>();
        new ReplayService(consumer, layout, index, TIMEOUT).replay(
                ReplayRequest.bucket(index.entries().get(0).bucketId()).withSpeed(60.0), e -> {
                    wall.add(System.nanoTime());
                    event.add(Micros.of(e.getTStart()));
                });
        double eventSeconds = (event.get(3) - event.get(0)) / 1e6;     // 30 s
        double wallSeconds = (wall.get(3) - wall.get(0)) / 1e9;         // ~0.5 s at 60x
        assertEquals(eventSeconds / 60.0, wallSeconds, 0.25);
    }
}
