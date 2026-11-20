package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.federation.AggregateInventory;
import org.opentsx.wayang.federation.AggregateRecord;
import org.opentsx.wayang.site.EphemeralSiteTlsFixture;
import org.opentsx.wayang.site.SignedJws;
import org.opentsx.wayang.site.TsxPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class RustAgentDaemonIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String JOB = "b3-daemon";
    private static final String ABI = "opentsx.rust.site-state.v1";
    @TempDir(factory = FederationLogDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test void realDaemonConformsPullsExecutesAcknowledgesAndStops() throws Exception {
        Path original = requiredPath("opentsx.rust.b3.runner");
        Path runnerLog = requiredPath("opentsx.rust.b3.testLog"), contractsLog = requiredPath("opentsx.rust.b3.contractsLog");
        TopicLayout topics = new TopicLayout("b3daemon");
        try (var brokers = new FederationBrokers(directory.resolve("brokers"), List.of("site", "signal"));
             var tls = EphemeralSiteTlsFixture.create("s1")) {
            for (String cluster : List.of("site", "signal")) try (Admin admin = KafScaleClients.admin(brokers.bootstrap(cluster))) {
                topics.provision(admin, 3, (short) 1);
                topics.provisionTopic(admin, topics.aggregates(), (short) 1);
                topics.provisionTopic(admin, topics.dagStatus(), (short) 1);
            }
            var input = KafScaleFixture.scenarioW().subList(0, 2);
            var data = FederatedWindparkData.fromEpisodes(input, 1);
            try (var producer = KafScaleClients.producer(brokers.bootstrap("site"))) {
                var avro = new OpenTsxAvro();
                for (var episode : input) producer.send(JavaTsxTopicSink.toRecord(avro, topics.episodes(), episode)).get();
                producer.flush();
            }
            var pem = tls.clientPem("s1");
            Path privateDirectory = pem.get("privateKey").getParent(), runner = privateDirectory.resolve("tsx-runner");
            String before = digest(Files.readAllBytes(original));
            Files.copy(original, runner);
            Files.setPosixFilePermissions(runner, PosixFilePermissions.fromString("rwx------"));
            assertEquals(before, digest(Files.readAllBytes(original)));
            assertEquals(before, digest(Files.readAllBytes(runner)));
            String artifact = "sha256:" + before;
            String version = process(List.of(runner.toString(), "--version"), "version", null).strip().substring("tsx-runner ".length());
            qualify(original, runner, brokers.bootstrap("site"), runnerLog, contractsLog, privateDirectory.resolve("qualification.json"));
            var driver = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var site = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            writePem(privateDirectory.resolve("driver.pem"), "PUBLIC KEY", driver.getPublic().getEncoded());
            writePem(privateDirectory.resolve("site.pem"), "PRIVATE KEY", site.getPrivate().getEncoded());
            String fragment = fragment(topics).toString(), hash = ExecutionAssignments.sha256(fragment);
            ObjectNode assignment = assignment(topics, brokers.bootstrap("site"), fragment, artifact);
            ArrayNode members = JSON.createArrayNode();
            ObjectNode member = members.addObject();
            for (String field : List.of("assignment", "site", "node", "generation", "fence", "fragmentHash")) member.set(field, assignment.get(field));
            ArrayNode scopes = JSON.createArrayNode();
            data.centralized().keySet().forEach(w -> scopes.addObject().put("groupId", w.groupId()).put("windowType", w.windowType())
                    .put("startMicros", Long.toString(w.startMicros())).put("endMicros", Long.toString(w.endMicros())));
            ObjectNode document = JSON.createObjectNode().put("schema", "opentsx.runner-plan.v1").put("job", JOB).put("planVersion", 1)
                    .put("issued", Instant.now().minusSeconds(60).toString()).put("expires", Instant.now().plusSeconds(3600).toString())
                    .put("site", "s1").put("catalog", "tsx-catalog/1").put("specHash", hash).put("assignmentText", assignment.toString());
            document.putArray("inputContracts").addObject().put("binding", "site").put("topic", topics.episodes()).putArray("fingerprints")
                    .add(KafScaleFixture.EPISODE_ONLY.iterator().next());
            document.putArray("outputs").addObject().put("sink", topics.aggregates()).put("cluster", "site").put("topic", topics.aggregates())
                    .put("partitionCount", "1").putArray("fingerprints").add(String.format(java.util.Locale.ROOT, "%016x", OpenTsxAvro.fingerprint(SeriesStats.getClassSchema())));
            ObjectNode federation = document.putObject("federation").put("route", "signal-cluster").put("maxDelay", "PT1M");
            federation.set("expectedAssignments", members); federation.set("scopes", scopes); federation.putArray("groupBy").add("park");
            var stopped = new java.util.concurrent.CompletableFuture<JsonNode>();
            try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), tls.certificateSites(),
                    directory.resolve("driver"), Clock.systemUTC(), (p, value) -> {
                        if (value.path("state").asText().equals("failed")) stopped.completeExceptionally(new IllegalStateException(value.path("error").asText()));
                        if (value.path("state").asText().equals("stopped")) stopped.complete(value.deepCopy());
                    }, (p, value) -> fail("unexpected HTTP aggregate"))) {
                service.registerQualifiedRuntime("s1", "node-s1", version, artifact, ABI, "rust");
                String signed = SignedJws.sign(JSON.writeValueAsBytes(document), driver.getPrivate());
                Path signedFile = privateDirectory.resolve("plan.jws");
                privateWrite(signedFile, signed);
                service.publish(signed);
                int port = service.start(new InetSocketAddress("127.0.0.1", 0), tls.serverContext()).getAddress().getPort();
                Path config = privateDirectory.resolve("config.json"), state = privateDirectory.resolve("state");
                Files.createDirectory(state); Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
                privateWrite(config, config(topics, brokers.bootstrap("site"), brokers.bootstrap("signal"), port, pem).toString());
                try (var watch = state.getFileSystem().newWatchService()) {
                    state.register(watch, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                    Process process = new ProcessBuilder(runner.toString(), "agent", "--config", config.toString())
                            .redirectOutput(directory.resolve("daemon.stdout.log").toFile()).redirectError(directory.resolve("daemon.stderr.log").toFile()).start();
                    process.onExit().thenAccept(exit -> {
                        if (!stopped.isDone()) stopped.completeExceptionally(new IllegalStateException("daemon exited before terminal status with code " + exit.exitValue()));
                    });
                    try {
                        JsonNode terminal = stopped.get(90, TimeUnit.SECONDS);
                        assertEquals(2, terminal.path("records").asInt());
                        assertEquals("B", terminal.path("track").asText());
                        JsonNode checkpoint = awaitCursor(state.resolve("agent.json"), watch, process);
                        assertEquals("1", checkpoint.path("cursor").asText());
                        assertTrue(checkpoint.path("pending").isNull());
                        assertEquals("done", checkpoint.path("commands").path(JOB + "/1").path("phase").asText());
                        var inventory = new AggregateInventory(JOB, 1, hash, members, scopes);
                        service.admitAggregateInventory("s1", JOB, inventory);
                        var partials = FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates());
                        assertEquals(data.centralized().size(), partials.size());
                        for (var record : partials) inventory.validate(AggregateRecord.fromConsumerRecord(record));
                        assertTrue(FederationFixture.readAll(brokers.bootstrap("signal"), topics.episodes()).isEmpty());
                        process.destroy();
                        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the daemon did not stop after SIGTERM");
                        assertEquals(0, process.exitValue(), readError(directory.resolve("daemon.stderr.log")));
                        JsonNode outcome = JSON.readTree(directory.resolve("daemon.stdout.log").toFile());
                        assertEquals("opentsx.rust-agent-outcome.v1", outcome.path("schema").asText());
                        assertEquals("stopped", outcome.path("state").asText()); assertEquals("1", outcome.path("cursor").asText());
                        String reportJws = process(List.of(runner.toString(), "conform", "--config", config.toString(), "--job", JOB), "conform", null).strip();
                        JsonNode report = JSON.readTree(SignedJws.verify(reportJws, site.getPublic()));
                        assertEquals("opentsx.site-conformance.v1", report.path("schema").asText());
                        assertEquals(JOB, report.path("job").asText()); assertEquals("B", report.path("track").asText());
                        assertEquals(artifact, report.path("artifact").path("digest").asText());
                        assertEquals(10, report.path("checks").size());
                        for (var check : report.path("checks")) assertEquals("pass", check.path("result").asText());
                        JsonNode saved = checkpoint.path("commands").path(JOB + "/1").path("outcome");
                        JsonNode fileReplay = JSON.readTree(process(List.of(runner.toString(), "run", "--plan", signedFile.toString(), "--config", config.toString()), "run-file", null));
                        assertEquals(saved, fileReplay);
                        JsonNode stdinReplay = JSON.readTree(process(List.of(runner.toString(), "run", "--plan", "-", "--config", config.toString()), "run-stdin", null,
                                signed.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                        assertEquals(saved, stdinReplay);
                        assertEquals(partials.size(), FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates()).size());
                        Path evidence = Files.createDirectories(Path.of("target", "rust-b3-daemon"));
                        ObjectNode result = JSON.createObjectNode().put("schema", "opentsx.rust-b3-daemon-acceptance.v1")
                                .put("episodes", input.size()).put("partials", partials.size()).put("cursor", "1").put("exitCode", process.exitValue())
                                .put("artifactDigest", artifact).put("runnerVersion", version).put("cliReplays", 2).put("signedConformanceAccepted", true);
                        result.set("status", terminal); result.set("outcome", outcome);
                        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("result.json").toFile(), result);
                    } finally {
                        if (process.isAlive()) { process.destroyForcibly(); if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("daemon cleanup did not terminate its owned process"); }
                    }
                }
            }
        }
    }

    private static JsonNode awaitCursor(Path path, java.nio.file.WatchService watch, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            if (Files.exists(path)) {
                JsonNode state = JSON.readTree(path.toFile()).path("payload");
                if (state.path("cursor").asText().equals("1")) return state;
            }
            if (!process.isAlive()) throw new IllegalStateException("the daemon exited before acknowledging its delivery");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("the daemon did not persist its acknowledged delivery cursor");
            var event = watch.poll(remaining, TimeUnit.NANOSECONDS);
            if (event == null) throw new IllegalStateException("the daemon did not persist its acknowledged delivery cursor");
            event.pollEvents(); if (!event.reset()) throw new IllegalStateException("daemon checkpoint watch closed");
        }
    }

    private void qualify(Path original, Path runner, String bootstrap, Path runnerLog, Path contractsLog, Path output) throws Exception {
        Path protocol = directory.resolve("protocol"), placement = directory.resolve("placement");
        Path probe = original.resolveSibling("examples").resolve("b1_acceptance"), placementBinary = original.resolveSibling("examples").resolve("b1_placement");
        process(List.of("python3", ROOT.resolve("tools/rust_b1_conformance.py").toString(), "--binary", probe.toString(), "--output", protocol.toString()), "protocol", bootstrap);
        RustB1Fixture.main(new String[]{"placement-prepare", bootstrap, placement.toString()});
        JsonNode rust = JSON.readTree(process(List.of(placementBinary.toString(), placement.resolve("placement.json").toString()), "placement", bootstrap));
        RustB1Fixture.main(new String[]{"placement-verify", bootstrap, placement.toString()});
        ObjectNode results = JSON.createObjectNode(); results.set("rust", rust); results.set("java", JSON.readTree(placement.resolve("placement-verified.json").toFile()));
        JSON.writeValue(placement.resolve("result.json").toFile(), results);
        process(List.of("python3", ROOT.resolve("tools/rust_b3_qualification.py").toString(), "--runner", runner.toString(), "--bootstrap", bootstrap,
                "--test-log", runnerLog.toString(), "--test-log", contractsLog.toString(), "--protocol-report", protocol.resolve("result.json").toString(),
                "--placement-report", placement.resolve("result.json").toString(), "--placement-manifest", placement.resolve("placement.json").toString(),
                "--expires", Instant.now().plusSeconds(7200).toString(), "--output", output.toString()), "qualification", null);
    }

    private String process(List<String> arguments, String name, String bootstrap) throws Exception {
        return process(arguments, name, bootstrap, null);
    }

    private String process(List<String> arguments, String name, String bootstrap, byte[] input) throws Exception {
        Path stdout = directory.resolve(name + ".stdout.log"), stderr = directory.resolve(name + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        if (bootstrap != null) builder.environment().put("KAFSCALE_BOOTSTRAP", bootstrap);
        Process process = builder.start();
        try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input); }
        if (!process.waitFor(20, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IOException("B3 fixture process timed out; inspect " + stderr); }
        assertEquals(0, process.exitValue(), () -> "B3 fixture process failed; inspect " + stderr + ": " + readError(stderr));
        return Files.readString(stdout);
    }

    private static String readError(Path path) {
        try { return Files.readString(path); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static String digest(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path requiredPath(String property) throws IOException {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException(property + " is required for B3 acceptance");
        Path path = Path.of(value).toRealPath();
        if (!Files.isRegularFile(path)) throw new IllegalStateException(property + " must name an existing regular file");
        return path;
    }

    private static ObjectNode fragment(TopicLayout topics) {
        ObjectNode fragment = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", JOB);
        fragment.putObject("source").put("kind", "kafscale-broker").putArray("topics").add(topics.episodes());
        var nodes = fragment.putArray("nodes"); nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        var stats = nodes.addObject().put("id", "stats").put("op", "stats.welford-ddsketch"); stats.putArray("in").add("decode");
        stats.putObject("params").put("alpha", 0.01).putArray("windows").add("PT10M").add("PT1H");
        fragment.putArray("sinks").addObject().put("from", "stats").put("topic", topics.aggregates()).put("schema", "org.opentsx.model.v2.SeriesStats");
        return fragment;
    }

    private static ObjectNode assignment(TopicLayout topics, String bootstrap, String fragment, String digest) {
        ObjectNode assignment = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1").put("deployment", JOB)
                .put("generation", "1").put("assignment", "assignment-s1").put("attempt", "attempt-1").put("fence", "1")
                .put("fragment", "fragment-s1").put("site", "s1").put("node", "node-s1").put("runtime", "rust")
                .put("catalog", "tsx-catalog/1").put("artifactDigest", digest).put("specHash", ExecutionAssignments.sha256(fragment))
                .put("sourceDagText", fragment).put("fragmentHash", ExecutionAssignments.sha256(fragment)).put("fragmentText", fragment).put("mode", "bounded");
        var positions = assignment.putArray("inputs").addObject().put("binding", "site").put("topic", topics.episodes()).put("partitionCount", "3").putArray("positions");
        try (var consumer = KafScaleClients.consumer(bootstrap)) {
            var partitions = consumer.partitionsFor(topics.episodes()).stream().map(p -> new org.apache.kafka.common.TopicPartition(topics.episodes(), p.partition())).sorted(java.util.Comparator.comparingInt(org.apache.kafka.common.TopicPartition::partition)).toList();
            var starts = consumer.beginningOffsets(partitions);
            var ends = consumer.endOffsets(partitions);
            for (var p : partitions) positions.addObject().put("partition", Integer.toString(p.partition())).put("from", Long.toString(starts.get(p))).put("to", Long.toString(ends.get(p)));
        }
        return assignment;
    }

    private static ObjectNode config(TopicLayout topics, String site, String signal, int port, Map<String, Path> pem) {
        ObjectNode config = JSON.createObjectNode().put("schema", "opentsx.rust-site-config.v1").put("site", "s1").put("node", "node-s1");
        config.putObject("driver").put("url", "https://localhost:" + port).put("ca", pem.get("ca").toString())
                .put("certificate", pem.get("certificate").toString()).put("privateKey", pem.get("privateKey").toString());
        config.put("driverPublicKey", "driver.pem").put("sitePrivateKey", "site.pem").put("qualification", "qualification.json").put("state", "state");
        config.putArray("jobs").add(JOB);
        var bindings = config.putObject("bindings");
        var local = bindings.putObject("site").put("bootstrap", site); local.putArray("inputs").add(topics.episodes()); local.putArray("outputs").add(topics.aggregates()).add(topics.dagStatus());
        var remote = bindings.putObject("signal").put("bootstrap", signal); remote.putArray("inputs"); remote.putArray("outputs").add(topics.aggregates());
        var policy = config.putObject("policy"); policy.putArray("domains").add(topics.domain()); policy.putObject("inputs").putArray("site").add(topics.episodes());
        var outputs = policy.putObject("outputs"); outputs.putArray("site").add("aggregates").add("dag-status"); outputs.putArray("signal").add("aggregates"); policy.putArray("catalogs").add("tsx-catalog/1");
        config.putObject("limits").put("records", 100).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                .put("samples", 1_000_000).put("outputBytes", 16_777_216).put("series", 1024).put("stateBytes", 1_048_576);
        return config;
    }

    private static void writePem(Path path, String type, byte[] bytes) throws IOException {
        privateWrite(path, "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes) + "\n-----END " + type + "-----\n");
    }

    private static void privateWrite(Path path, String value) throws IOException {
        Files.writeString(path, value); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

}
