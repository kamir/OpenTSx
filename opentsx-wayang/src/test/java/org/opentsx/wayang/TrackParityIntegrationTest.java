package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TrackParityIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CREATED_AT = 1_790_812_800_000_000L;

    @Test
    void completeWindparkDagMatchesRustAcrossRestartedAcknowledgedIncrements(@TempDir Path directory) throws Exception {
        Path executable = Path.of(System.getProperty("opentsx.rust.b2.parity",
                "../rust/tsx-runner/target/debug/examples/b2_parity")).toRealPath();
        assertTrue(Files.isExecutable(executable), "the B2 parity example must be built before the service test");
        String artifact = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)));
        KafScaleFixture fixture = new KafScaleFixture();
        List<Episode> input = KafScaleFixture.scenarioW();
        assertTrue(input.size() > 300);
        assertEquals(100, input.stream().map(e -> e.getSeries().getSeriesId()).distinct().count());
        ObjectNode dag = dag(fixture, input);
        String text = dag.toString();
        String hash = ExecutionAssignments.sha256(text);
        fixture.write(fixture.layout.episodes(), input);
        DagToWayang compiled = DagToWayang.compile(dag, fixture.layout, hash, Path.of("..", "systems", "processors"));
        TsxIncrement.Config config = new TsxIncrement.Config(fixture.bootstrap, "b2-parity", 1, hash,
                fixture.layout.episodes(), KafScaleFixture.EPISODE_ONLY, "earliest", directory.resolve("java-checkpoint.json"));
        assertEquals(input.size(), TsxIncrement.run(config, compiled.plan(fixture.bootstrap, compiled.series().get(0).name())).records());
        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, Episode> expected = new TreeMap<>();
        fixture.readAll(fixture.layout.clean()).forEach(record -> {
            Episode episode = avro.decode(record.value(), Episode.class);
            assertEquals(null, expected.put(episode.getEpisodeId(), episode), "Track A produced a duplicate episode");
        });
        assertEquals(input.size(), expected.size());
        int increments = requests(directory, fixture, dag, hash, artifact);
        Process process = new ProcessBuilder(executable.toString(), fixture.bootstrap, directory.toString())
                .redirectOutput(directory.resolve("rust.stdout").toFile())
                .redirectError(directory.resolve("rust.stderr").toFile()).start();
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the timed-out parity process did not terminate");
        }
        assertTrue(completed, "the Rust parity process exceeded five minutes");
        assertEquals(0, process.exitValue(), Files.readString(directory.resolve("rust.stderr")));
        JsonNode report = JSON.readTree(Files.readString(directory.resolve("rust.stdout")));
        assertEquals("opentsx.rust-b2-parity.v1", report.path("schema").asText());
        assertEquals(artifact, report.path("artifactDigest").asText());
        assertEquals(increments, report.path("increments").asInt());
        assertEquals(input.size(), report.path("inputRecords").asInt());
        assertEquals(expected.size(), report.path("outputRecords").asInt());
        assertEquals(increments * 2, report.path("restarts").asInt());
        assertEquals(increments, report.path("completedRetries").asInt());
        Map<String, Integer> copies = new HashMap<>();
        long compared = 0;
        var observed = fixture.readAll(fixture.layout.clean());
        assertEquals(expected.size() * 2, observed.size());
        for (var record : observed) {
            Episode actual = avro.decode(record.value(), Episode.class);
            Episode reference = expected.get(actual.getEpisodeId());
            assertNotNull(reference, "Rust produced an unknown episode identity");
            assertEquals(reference.getSeries(), actual.getSeries(), actual.getEpisodeId());
            assertArrayEquals(Episodes.timestampsMicros(reference), Episodes.timestampsMicros(actual), actual.getEpisodeId());
            assertArrayEquals(Episodes.values(reference), Episodes.values(actual), actual.getEpisodeId());
            copies.merge(actual.getEpisodeId(), 1, Integer::sum);
            compared += actual.getCount();
        }
        assertEquals(expected.keySet(), copies.keySet());
        assertTrue(copies.values().stream().allMatch(count -> count == 2), "each track must publish each identity exactly once");
        assertEquals(input.stream().mapToLong(Episode::getCount).sum() * 2, compared);
        System.out.println(JSON.createObjectNode().put("schema", "opentsx.track-parity-result.v1")
                .put("inputRecords", input.size()).put("matchedEpisodes", expected.size())
                .put("matchedValues", compared / 2).put("increments", increments).put("restarts", increments * 2)
                .put("completedRetries", increments).put("differences", 0));
    }

    private static ObjectNode dag(KafScaleFixture fixture, List<Episode> episodes) throws Exception {
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "b2-parity").put("version", 1);
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker").put("start", "earliest");
        source.putArray("topics").add(fixture.layout.episodes());
        ArrayNode series = source.putArray("series");
        episodes.stream().map(e -> e.getSeries().getSeriesId()).distinct().sorted().forEach(series::add);
        dag.set("nodes", JSON.readTree("""
                [{"id":"decode","op":"decode.avro-soe"},
                 {"id":"check","op":"dq.checks","in":["decode"],"params":{"checks":["PLAUSIBLE_RANGE"],"min":0,"max":1000000,"mode":"clip"}},
                 {"id":"convert","op":"convert.unit","in":["check"],"params":{"factor":1.25,"offset":0.5}},
                 {"id":"shear","op":"model.wind-shear","in":["convert"],"params":{"fromM":50,"toM":100,"alpha":0.2}},
                 {"id":"power","op":"model.power-curve","in":["shear"],"params":{"ratedKw":3000,"cutIn":3,"ratedWind":12,"cutOut":25,"count":1}},
                 {"id":"lag","op":"feature.lag","in":["power"],"params":{"steps":3}},
                 {"id":"rolling","op":"feature.rolling","in":["lag"],"params":{"window":"PT10S","fn":"MEAN"}}]
                """));
        ObjectNode sink = dag.putArray("sinks").addObject().put("from", "rolling").put("schema", "opentsx.episode.v2")
                .put("topic", fixture.layout.clean()).put("origin", "SYNTHETIC");
        sink.putObject("signal").put("system", "ostsee").put("id", "wind").put("grade", "clean");
        return dag;
    }

    private static int requests(Path directory, KafScaleFixture fixture, ObjectNode dag, String hash, String artifact) throws Exception {
        List<TopicPartition> partitions;
        Map<TopicPartition, Long> high;
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(fixture.bootstrap)) {
            partitions = consumer.partitionsFor(fixture.layout.episodes(), Duration.ofSeconds(30)).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).sorted((a, b) -> Integer.compare(a.partition(), b.partition())).toList();
            high = consumer.endOffsets(partitions, Duration.ofSeconds(30));
        }
        assertEquals(3, partitions.size());
        Map<Integer, Long> next = new TreeMap<>();
        partitions.forEach(partition -> next.put(partition.partition(), 0L));
        ObjectNode manifest = JSON.createObjectNode().put("schema", "opentsx.rust-b2-parity-fixture.v1")
                .put("node", "b2-worker").put("site", "local").put("artifactDigest", artifact);
        ArrayNode names = manifest.putArray("requests");
        String previous = null;
        int sequence = 0;
        while (partitions.stream().anyMatch(partition -> next.get(partition.partition()) < high.get(partition))) {
            sequence++;
            ObjectNode assignment = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                    .put("deployment", "b2-parity").put("generation", "1").put("assignment", "assignment-1")
                    .put("attempt", "attempt-1").put("fence", "1").put("fragment", "fragment-1")
                    .put("site", "local").put("node", "b2-worker").put("runtime", "rust").put("catalog", "tsx-catalog/1")
                    .put("artifactDigest", artifact).put("specHash", hash).put("sourceDagText", dag.toString())
                    .put("fragmentHash", hash).put("fragmentText", dag.toString()).put("mode", "bounded");
            ObjectNode input = assignment.putArray("inputs").addObject().put("binding", "local")
                    .put("topic", fixture.layout.episodes()).put("partitionCount", "3");
            ArrayNode positions = input.putArray("positions");
            long budget = 100;
            for (TopicPartition partition : partitions) {
                long from = next.get(partition.partition());
                long count = Math.min(budget, high.get(partition) - from);
                long to = from + count;
                positions.addObject().put("partition", Integer.toString(partition.partition())).put("from", Long.toString(from)).put("to", Long.toString(to));
                next.put(partition.partition(), to);
                budget -= count;
            }
            ExecutionAssignments.validate(assignment.toString());
            ObjectNode request = JSON.createObjectNode().put("schema", "opentsx.local-increment.v1")
                    .put("assignmentText", assignment.toString()).put("createdAtMicros", Long.toString(CREATED_AT))
                    .put("sequence", Integer.toString(sequence));
            if (previous == null) request.putNull("previousRequestHash"); else request.put("previousRequestHash", previous);
            ObjectNode binding = request.putObject("bindings").putObject("local").put("bootstrap", fixture.bootstrap);
            binding.putArray("inputs").add(fixture.layout.episodes());
            binding.putArray("outputs").add(fixture.layout.clean());
            request.putObject("sink").put("name", "wind").put("binding", "local").put("topic", fixture.layout.clean()).put("partitionCount", "1");
            request.putObject("limits").put("records", 100).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                    .put("samples", 1_000_000).put("outputBytes", 16_777_216).put("series", 1_024).put("stateBytes", 1_048_576);
            request.putObject("context");
            String name = "request-%05d.json".formatted(sequence);
            String encoded = request.toString();
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 65_536);
            Files.writeString(directory.resolve(name), encoded, StandardCharsets.UTF_8);
            previous = ExecutionAssignments.sha256(encoded);
            names.add(name);
        }
        JSON.writeValue(directory.resolve("manifest.json").toFile(), manifest);
        return sequence;
    }
}
