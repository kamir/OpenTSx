package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogTail;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.vector.VectorProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxCleanParityIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void oneDagMatchesTheDeployedProcessorAndReportsStatus(@TempDir Path dir) throws Exception {
        KafScaleFixture k = new KafScaleFixture();
        List<Episode> w = KafScaleFixture.scenarioW();
        ObjectMapper json = new ObjectMapper();
        ObjectNode dag = json.createObjectNode().put("dag", "a2-clean").put("version", 1);
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker").put("start", "earliest");
        source.putArray("topics").add(k.layout.episodes());
        var series = source.putArray("series");
        w.stream().map(e -> e.getSeries().getSeriesId().toString()).distinct().sorted().forEach(series::add);
        dag.set("nodes", json.readTree("""
                [{"id":"decode","op":"decode.avro-soe"},
                 {"id":"check","op":"dq.checks","in":["decode"],
                  "params":{"min":0,"max":1000000,"mode":"gap"}},
                 {"id":"sample","op":"resample","in":["check"],
                  "params":{"every":"PT7M","agg":"MEAN"}}]
                """));
        ObjectNode sink = dag.putArray("sinks").addObject().put("from", "sample")
                .put("topic", k.layout.clean()).put("schema", "opentsx.episode.v2").put("origin", "SYNTHETIC");
        sink.putObject("signal").put("system", "ostsee").put("id", "wind").put("grade", "clean");
        String spec = json.writeValueAsString(dag);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(spec.getBytes(StandardCharsets.UTF_8)));
        DagToWayang compiled = DagToWayang.compile(dag, k.layout, hash, Path.of("..", "systems", "processors"));
        String sinkName = compiled.series().get(0).name();
        TsxIncrement.Config config = new TsxIncrement.Config(k.bootstrap, "a2-clean", 1, hash,
                k.layout.episodes(), KafScaleFixture.EPISODE_ONLY, "earliest", dir.resolve("track-a.json"));
        k.write(k.layout.episodes(), w);
        AtomicInteger increments = new AtomicInteger();
        try (TsxStatus status = new TsxStatus(k.bootstrap, k.layout.dagStatus())) {
            assertEquals(w.size(), TsxIncrement.loop(config, compiled.plan(k.bootstrap, sinkName), status,
                    Duration.ZERO, () -> increments.getAndIncrement() >= 1));
        }
        List<JsonNode> status = TsxStatusIntegrationTest.assertStatus(k.readAll(k.layout.dagStatus()), config);
        JsonNode stopped = status.get(status.size() - 1);
        assertEquals(w.size(), stopped.path("records").asLong());
        assertTrue(stopped.path("waitingFor").isEmpty());
        assertEquals(0, stopped.path("skippedBlocks").asLong());
        assertTrue(stopped.path("watermark").isTextual());

        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, Episode> trackA = new TreeMap<>();
        Map<String, Episode> processor = new TreeMap<>();
        try (KafkaConsumer<byte[], byte[]> reader = KafScaleClients.consumer(k.bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(k.bootstrap)) {
            LogTail output = new LogTail(reader, k.layout.clean(), TIMEOUT);
            int count = output.poll(r -> unique(trackA, avro.decode(r.value(), Episode.class)));
            assertTrue(count > 100, "W must produce more than 100 clean episodes");
            assertEquals(count, trackA.size());
            producer.send(new ProducerRecord<>(k.layout.dags(), "a2-clean",
                    VectorProcessor.deployRecord("a2-clean", spec, "integration-test"))).get(30, TimeUnit.SECONDS);
            try (VectorProcessor deployed = new VectorProcessor(() -> KafScaleClients.consumer(k.bootstrap),
                    () -> KafScaleClients.producer(k.bootstrap), k.layout, dir.resolve("processor"),
                    new VectorProcessor.Timing(Duration.ZERO, Duration.ofSeconds(30), Duration.ZERO,
                            Duration.ZERO, Duration.ofMillis(50), TIMEOUT))) {
                long deadline = System.nanoTime() + TIMEOUT.toNanos();
                do {
                    deployed.step();
                    output.poll(r -> unique(processor, avro.decode(r.value(), Episode.class)));
                } while (processor.size() < trackA.size() && System.nanoTime() < deadline);
                assertEquals(Map.of("a2-clean/" + sinkName, "running"), deployed.states());
            }
            output.poll(r -> unique(processor, avro.decode(r.value(), Episode.class)));
        }
        assertEquals(trackA.keySet(), processor.keySet());
        List<String> differences = new ArrayList<>();
        trackA.forEach((id, expected) -> {
            Episode actual = processor.get(id);
            assertArrayEquals(Episodes.timestampsMicros(expected), Episodes.timestampsMicros(actual), id);
            if (!java.util.Arrays.equals(Episodes.values(expected), Episodes.values(actual))) {
                differences.add(id);
            }
        });
        assertEquals(List.of(), differences);
    }

    private static void unique(Map<String, Episode> episodes, Episode episode) {
        assertEquals(null, episodes.put(episode.getEpisodeId(), episode), "duplicate " + episode.getEpisodeId());
    }
}
