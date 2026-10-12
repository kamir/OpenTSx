package org.opentsx.kafka;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void layoutNamesAndValidation() {
        assertEquals("tsx.wind.episodes.v2", layout.episodes());
        assertEquals("tsx.wind.series-stats.v2", layout.seriesStats());
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("Wind Park"));
    }
}
