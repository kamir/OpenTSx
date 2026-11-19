package org.opentsx.windpark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.OpenTsxConf;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** A checked run of the command line {@code args}, without environment. */
    private static WindparkSim.Run checked(String... args) {
        List<String> argv = new ArrayList<>(List.of(WindparkSim.COMMAND));
        argv.addAll(List.of(args));
        return WindparkSim.check(OpenTsxConf.load(argv, Map.of(), null).conf());
    }

    @Test
    void cliWritesIcebergAndGroundTruth() throws Exception {
        Path gt = dir.resolve("gt.json");
        String iceberg = "catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog,uri=jdbc:sqlite:" + dir.resolve("c.db")
                + ",jdbc.schema-version=V1,warehouse=" + dir.resolve("wh").toUri();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WindparkSim.run(List.of("--scenario", shortScenario().toString(), "--iceberg", iceberg, "--ground-truth",
                gt.toString()), Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), KafScaleClients::admin, KafScaleClients::producer);
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        String summary = out.toString(StandardCharsets.UTF_8);
        assertTrue(summary.startsWith("scenario ostsee-demo: 200 episodes, "), summary);
        assertTrue(summary.contains("T10"), summary);
        JsonNode truth = new ObjectMapper().readTree(gt.toFile());
        assertEquals(17, truth.size());
        try (var store = org.opentsx.store.iceberg.IcebergSeriesStore.open(
                WindparkSim.icebergProperties(List.of(iceberg.split(","))), "tsx")) {
            assertEquals(100, store.listSeries(org.opentsx.store.SeriesSelector.all()).size()); // 10 turbines x 10 channels
        }
    }

    /** Iceberg properties whose table creation works but whose data files cannot be written. */
    private String icebergWithUnwritableData() throws Exception {
        Path tableDir = Files.createDirectories(dir.resolve("wh/tsx/episodes"));
        Files.writeString(tableDir.resolve("data"), "a file where the data directory belongs");
        return "catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog,uri=jdbc:sqlite:" + dir.resolve("c.db")
                + ",jdbc.schema-version=V1,warehouse=" + dir.resolve("wh").toUri();
    }

    private static String trace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void failedFinalIcebergWriteFailsTheRun() throws Exception {
        WindparkSim.Run run = checked("--scenario", shortScenario().toString(), "--iceberg", icebergWithUnwritableData());
        Exception e = assertThrows(Exception.class,
                () -> WindparkSim.simulate(run, KafScaleClients::admin, KafScaleClients::producer, System.out));
        assertTrue(trace(e).contains("wh/tsx/episodes/data"), trace(e));
    }

    @Test
    void failedFinalIcebergWriteIsSuppressedOnTheFirstFailure() throws Exception {
        WindparkSim.Run ok = checked("--scenario", shortScenario().toString(), "--iceberg", icebergWithUnwritableData());
        WindparkSim.Run run = new WindparkSim.Run(ok.simulator(), ok.scenario(), ok.catalog(), ok.bootstrap(), ok.layout(),
                ok.partitions(), ok.index(), ok.iceberg(), ok.namespace(), dir);
        Exception e = assertThrows(Exception.class,
                () -> WindparkSim.simulate(run, KafScaleClients::admin, KafScaleClients::producer, System.out));
        assertTrue(e instanceof FileSystemException, e.toString());
        assertEquals(1, e.getSuppressed().length, trace(e));
        assertTrue(trace(e.getSuppressed()[0]).contains("wh/tsx/episodes/data"));
        assertTrue(WindparkSim.causes(e).startsWith(e + "; suppressed "), WindparkSim.causes(e));
    }

    @Test
    void closeAllClosesEveryCloseableAndKeepsTheFirstFailure() {
        List<String> closed = new ArrayList<>();
        List<AutoCloseable> closeables = List.of(
                () -> {
                    closed.add("flush");
                    throw new IOException("flush");
                },
                () -> closed.add("producer"),
                () -> {
                    closed.add("store");
                    throw new IllegalStateException("store");
                });
        Exception e = assertThrows(IOException.class, () -> WindparkSim.closeAll(closeables, null));
        assertEquals(List.of("flush", "producer", "store"), closed);
        assertEquals(1, e.getSuppressed().length, trace(e));
        assertEquals("store", e.getSuppressed()[0].getMessage());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
    void cliWritesAVerifiableBucketToKafScale() throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        String domain = "wp-" + UUID.randomUUID().toString().substring(0, 8);
        Path index = dir.resolve("index.tsv");
        SimulationReport r = WindparkSim.simulate(checked("--scenario", shortScenario().toString(), "--kafscale", bootstrap,
                "--domain", domain, "--index", index.toString(), "--partitions", "2"), KafScaleClients::admin,
                KafScaleClients::producer, System.out);

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
