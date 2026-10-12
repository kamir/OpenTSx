package org.opentsx.windpark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.BucketCatalog;
import org.opentsx.kafka.FileEpisodeIndex;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.ReplayRequest;
import org.opentsx.kafka.ReplayResult;
import org.opentsx.kafka.ReplayService;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.sim.SimulationReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindparkSimEndToEndTest {

    @TempDir
    Path dir;

    /** A 20-minute copy of the demo scenario, so tests stay fast. */
    private Path shortScenario() throws Exception {
        String yaml = Files.readString(Path.of("scenarios/ostsee-demo.yaml")).replace("duration: PT6H", "duration: PT20M");
        Path p = dir.resolve("short.yaml");
        Files.writeString(p, yaml);
        return p;
    }

    @Test
    void cliWritesIcebergAndGroundTruth() throws Exception {
        Path gt = dir.resolve("gt.json");
        String iceberg = "catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog,uri=jdbc:sqlite:" + dir.resolve("c.db")
                + ",jdbc.schema-version=V1,warehouse=" + dir.resolve("wh").toUri();
        SimulationReport r = WindparkSim.run(Map.of("scenario", shortScenario().toString(), "iceberg", iceberg,
                "ground-truth", gt.toString()));
        assertEquals(10 * 10 * 2, r.episodes());
        JsonNode truth = new ObjectMapper().readTree(gt.toFile());
        assertEquals(17, truth.size());
        assertTrue(WindparkSim.summary(r).contains("T10"));
        try (var store = org.opentsx.store.iceberg.IcebergSeriesStore.open(WindparkSim.props(iceberg), "tsx")) {
            assertEquals(100, store.listSeries(org.opentsx.store.SeriesSelector.all()).size()); // 10 turbines x 10 channels
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
    void cliWritesAVerifiableBucketToKafScale() throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        String domain = "wp-" + UUID.randomUUID().toString().substring(0, 8);
        Path index = dir.resolve("index.tsv");
        SimulationReport r = WindparkSim.run(Map.of("scenario", shortScenario().toString(), "kafscale", bootstrap,
                "domain", domain, "index", index.toString(), "partitions", "2"));

        TopicLayout layout = new TopicLayout(domain);
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             FileEpisodeIndex idx = new FileEpisodeIndex(index)) {
            BucketManifest m = BucketCatalog.load(consumer, layout, Duration.ofSeconds(60)).values().iterator().next();
            assertEquals("ostsee-demo", m.getDescriptor().get("scenario"));
            List<Episode> replayed = new ArrayList<>();
            ReplayResult res = new ReplayService(consumer, layout, idx, Duration.ofSeconds(60))
                    .replay(ReplayRequest.bucket(m.getBucketId()), replayed::add);
            assertTrue(res.verified(), res.toString());
            assertEquals(r.episodes(), replayed.size());
        }
    }
}
