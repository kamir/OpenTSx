package org.opentsx.store.iceberg;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.BucketWriter;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.ReplayRequest;
import org.opentsx.kafka.ReplayResult;
import org.opentsx.kafka.ReplayService;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KafScale -> Iceberg: bucket written with an Iceberg index, synced into the Iceberg store, replayed. */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class KafScaleToIcebergIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void bucketLandsInIcebergAndReplaysViaTheIcebergIndex() throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        TopicLayout layout = new TopicLayout("ice-" + UUID.randomUUID().toString().substring(0, 8));
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            layout.provision(admin, 2, (short) 1);
        }
        long t0 = 1_790_000_000_000_000L;
        List<Episode> episodes = new ArrayList<>();
        for (String turbine : List.of("T01", "T02")) {
            SeriesKey key = SeriesKeys.of("wind.turbine.wind_speed", Map.of("park", "ber-01", "turbine", turbine), "m/s");
            for (int e = 0; e < 5; e++) {
                double[] v = new double[30];
                for (int i = 0; i < v.length; i++) {
                    v[i] = 7 + e + i / 100.0;
                }
                episodes.add(Episodes.regular(key, t0 + e * 30_000_000L, 1_000_000L, v).source("it").build());
            }
        }
        Map<String, String> props = IcebergSeriesStoreTest.catalogProps(dir);
        var catalog = IcebergSeriesStore.catalog(props);
        BucketManifest sealed;
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap);
             IcebergEpisodeIndex index = new IcebergEpisodeIndex(catalog, org.apache.iceberg.catalog.Namespace.of("tsx"))) {
            try (BucketWriter w = BucketWriter.open(producer, layout, index, "ice-run", Map.of())) {
                episodes.forEach(w::append);
                sealed = w.seal();
            }
        }

        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             IcebergSeriesStore store = new IcebergSeriesStore(catalog, org.apache.iceberg.catalog.Namespace.of("tsx"))) {
            EpisodeLogSync.Result first = EpisodeLogSync.sync(consumer, layout, store, Map.of(), 4, Duration.ofSeconds(60));
            assertEquals(episodes.size(), first.written());
            // restarting from the beginning is safe: everything is skipped
            EpisodeLogSync.Result again = EpisodeLogSync.sync(consumer, layout, store, Map.of(), 4, Duration.ofSeconds(60));
            assertEquals(0, again.written());
            assertEquals(episodes.size(), again.skipped());
            // continuing from the returned positions reads nothing new
            EpisodeLogSync.Result tail = EpisodeLogSync.sync(consumer, layout, store, first.nextPositions(), 4, Duration.ofSeconds(60));
            assertEquals(0, tail.written() + tail.skipped());
            long total = first.nextPositions().values().stream().mapToLong(Long::longValue).sum();
            assertTrue(total >= episodes.size());

            assertEquals(episodes.size(), store.read(EpisodeQuery.of(SeriesSelector.all(), t0, t0 + 3_600_000_000L)).size());

            IcebergEpisodeIndex durableIndex = new IcebergEpisodeIndex(catalog, org.apache.iceberg.catalog.Namespace.of("tsx"));
            List<Episode> replayed = new ArrayList<>();
            ReplayResult r = new ReplayService(consumer, layout, durableIndex, Duration.ofSeconds(60))
                    .replay(ReplayRequest.bucket(sealed.getBucketId()), replayed::add);
            assertTrue(r.verified(), r.toString());
            assertEquals(episodes.size(), replayed.size());
        } finally {
            ((AutoCloseable) catalog).close();
        }
    }
}
