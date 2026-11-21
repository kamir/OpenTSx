package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.federation.AggregateInventory;
import org.opentsx.wayang.federation.AggregateRecord;
import org.opentsx.wayang.federation.CompletenessCoordinator;
import org.opentsx.wayang.site.EphemeralSiteTlsFixture;
import org.opentsx.wayang.site.SignedJws;
import org.opentsx.wayang.site.TsxPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class RustOfflineCatchupIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String JOB = "b3-offline";
    private static final String ABI = "opentsx.rust.site-state.v1";
    @TempDir(factory = FederationLogDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test void fullWindparkCutSurvivesOfflineSignalAndAcknowledgementLossWithoutMissingFinalWindows() throws Exception {
        Path executable = requiredPath("opentsx.rust.b3.offline");
        Path runnerLog = requiredPath("opentsx.rust.b3.testLog");
        Path contractsLog = requiredPath("opentsx.rust.b3.contractsLog");
        TopicLayout topics = new TopicLayout("b3offline");
        try (var brokers = new FederationBrokers(directory.resolve("brokers"), List.of("site", "signal"));
             var tls = EphemeralSiteTlsFixture.create("s1");
             var observer = new LatencyProtocolObserver(brokers.port("signal"), false);
             var link = new SignalLink(observer.bootstrap())) {
            for (String cluster : List.of("site", "signal")) try (Admin admin = KafScaleClients.admin(brokers.bootstrap(cluster))) {
                topics.provision(admin, 3, (short) 1);
                topics.provisionTopic(admin, topics.aggregates(), (short) 1);
                topics.provisionTopic(admin, topics.dagStatus(), (short) 1);
            }
            var generated = FederatedWindparkData.load(1);
            try (var producer = KafScaleClients.producer(brokers.bootstrap("site"))) {
                var avro = new OpenTsxAvro();
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (var episode : generated.episodes()) futures.add(producer.send(JavaTsxTopicSink.toRecord(avro, topics.episodes(), episode)));
                producer.flush();
                for (var future : futures) future.get();
            }
            var input = FederationFixture.readAll(brokers.bootstrap("site"), topics.episodes());
            assertEquals(3600, input.size());
            var avro = new OpenTsxAvro();
            var data = FederatedWindparkData.fromEpisodes(input.stream().map(r -> avro.decode(r.value(), org.opentsx.model.v2.Episode.class)).toList(), 1);
            assertEquals(2_160_000, data.episodes().stream().mapToLong(org.opentsx.model.v2.Episode::getCount).sum());
            assertEquals(420, data.centralized().size());
            var pem = tls.clientPem("s1");
            Path privateDirectory = pem.get("privateKey").getParent();
            Path runner = privateDirectory.resolve("b3_offline");
            String before = digest(Files.readAllBytes(executable));
            Files.copy(executable, runner);
            Files.setPosixFilePermissions(runner, PosixFilePermissions.fromString("rwx------"));
            assertEquals(before, digest(Files.readAllBytes(executable)));
            assertEquals(before, digest(Files.readAllBytes(runner)));
            String digest = "sha256:" + before;
            String version = process(List.of(runner.toString(), "--version"), "version", null).strip().substring("tsx-runner ".length());
            qualify(executable, runner, brokers.bootstrap("site"), runnerLog, contractsLog, privateDirectory.resolve("qualification.json"));
            var driver = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var site = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            writePem(privateDirectory.resolve("driver.pem"), "PUBLIC KEY", driver.getPublic().getEncoded());
            writePem(privateDirectory.resolve("site.pem"), "PRIVATE KEY", site.getPrivate().getEncoded());
            String fragment = fragment(topics).toString();
            String hash = ExecutionAssignments.sha256(fragment);
            ObjectNode assignment = assignment(topics, brokers.bootstrap("site"), fragment, digest);
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
            String signed = SignedJws.sign(JSON.writeValueAsBytes(document), driver.getPrivate());
            Path plan = privateDirectory.resolve("plan.jws"), config = privateDirectory.resolve("config.json");
            privateWrite(plan, signed);
            try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), tls.certificateSites(),
                    directory.resolve("driver"), Clock.systemUTC(), (p, value) -> fail("unexpected status"), (p, value) -> fail("unexpected HTTP aggregate"))) {
                service.registerQualifiedRuntime("s1", "node-s1", version, digest, ABI, "rust");
                service.publish(signed);
                int port = service.start(new InetSocketAddress("127.0.0.1", 0), tls.serverContext()).getAddress().getPort();
                privateWrite(config, config(topics, brokers.bootstrap("site"), link.bootstrap(), port, pem).toString());
                var conformed = invoke(runner, "conform", config, plan);
                assertEquals("accepted", conformed.path("conformance").path("result").asText());
                var inventory = new AggregateInventory(JOB, 1, hash, members, scopes);
                service.admitAggregateInventory("s1", JOB, inventory);
                assertFalse(link.online);
                var executed = invoke(runner, "execute", config, plan);
                assertTrue(executed.path("completed").asBoolean());
                assertEquals(3600, executed.path("inputRecords").asInt());
                assertEquals(420, executed.path("outputRecords").asInt());
                assertTrue(executed.path("increments").asInt() >= 36);
                assertTrue(FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates()).isEmpty());
                var partials = FederationFixture.readAll(brokers.bootstrap("site"), topics.aggregates());
                assertEquals(420, partials.size());
                var unique = new TreeMap<String, ConsumerRecord<byte[], byte[]>>();
                for (var record : partials) {
                    AggregateRecord aggregate = AggregateRecord.fromConsumerRecord(record);
                    inventory.validate(aggregate);
                    SeriesStats actual = aggregate.statistics();
                    var window = new FederatedWindparkData.Window(actual.getSeriesId(), actual.getWindowType(),
                            org.opentsx.model.Micros.of(actual.getWindowStart()), org.opentsx.model.Micros.of(actual.getWindowEnd()));
                    SeriesStats expected = data.centralized().get(window);
                    assertNotNull(expected);
                    assertEquals(expected.getValidCount(), actual.getValidCount());
                    assertEquals(expected.getNanCount(), actual.getNanCount());
                    assertEquals(expected.getMin(), actual.getMin());
                    assertEquals(expected.getMax(), actual.getMax());
                    assertNull(unique.put(aggregate.key(), record));
                    assertEquals(assignment.path("inputs"), JSON.readTree(aggregate.header("tsx-evidence")).path("inputs"));
                }
                var waiting = invoke(runner, "forward", config, plan);
                assertEquals("waiting", waiting.path("state").asText());
                assertFalse(waiting.path("completed").asBoolean());
                assertTrue(waiting.path("error").isTextual());
                assertTrue(link.denied.get() > 0);
                Path cursor = privateDirectory.resolve("state").resolve(JOB + "-1-forward.json");
                byte[] unchanged = Files.readAllBytes(cursor);
                assertTrue(FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates()).isEmpty());
                link.online = true;
                var lost = invoke(runner, "forward-ack-loss", config, plan);
                assertFalse(lost.path("completed").asBoolean());
                assertArrayEquals(unchanged, Files.readAllBytes(cursor));
                var caughtUp = invoke(runner, "forward", config, plan);
                assertTrue(caughtUp.path("completed").asBoolean());
                var received = FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates());
                assertTrue(received.size() > 420);
                var coordinator = new CompletenessCoordinator(inventory, Duration.ofMinutes(1), 0.01,
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), directory.resolve("merge.json"));
                var deduplicated = new TreeMap<String, AggregateRecord>();
                for (var record : received) {
                    AggregateRecord aggregate = AggregateRecord.fromConsumerRecord(record);
                    ConsumerRecord<byte[], byte[]> original = unique.get(aggregate.key());
                    assertNotNull(original);
                    assertArrayEquals(original.value(), record.value());
                    assertArrayEquals(original.key(), record.key());
                    assertEquals(original.timestamp(), record.timestamp());
                    assertEquals(Arrays.asList(original.headers().toArray()), Arrays.asList(record.headers().toArray()));
                    String id = aggregate.key() + "|" + aggregate.revision();
                    var previous = deduplicated.putIfAbsent(id, aggregate);
                    if (previous != null) assertEquals(previous, aggregate);
                    coordinator.accept(aggregate);
                }
                assertEquals(420, deduplicated.size());
                assertEquals(420, coordinator.pending().size());
                var restored = new CompletenessCoordinator(inventory, Duration.ofMinutes(1), 0.01,
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), directory.resolve("merge.json"));
                for (var record : deduplicated.values()) assertTrue(restored.accept(record).isEmpty());
                for (var record : restored.pending()) {
                    assertEquals("true", record.header("tsx-final"));
                    assertEquals("", record.header("tsx-missing-sites"));
                    assertEquals(0, record.revision());
                }
                var replay = invoke(runner, "forward", config, plan);
                assertTrue(replay.path("completed").asBoolean());
                assertEquals(received.size(), FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates()).size());
                int finalCount = restored.pending().size();
                try (var producer = KafScaleClients.producer(brokers.bootstrap("signal"))) {
                    var finalRecords = restored.pending();
                    var acknowledgements = new ArrayList<java.util.concurrent.Future<?>>();
                    for (var record : finalRecords) acknowledgements.add(producer.send(record.producerRecord(topics.aggregates())));
                    producer.flush();
                    for (var acknowledgement : acknowledgements) acknowledgement.get();
                    for (var record : finalRecords) restored.acknowledge(record);
                }
                assertTrue(restored.pending().isEmpty());
                var publishedFinals = FederationFixture.readAll(brokers.bootstrap("signal"), topics.aggregates()).stream()
                        .map(AggregateRecord::fromConsumerRecord).filter(r -> r.headers().containsKey("tsx-final")).toList();
                assertEquals(420, publishedFinals.size());
                assertEquals(420, publishedFinals.stream().map(AggregateRecord::key).distinct().count());
                for (var record : publishedFinals) assertEquals("true", record.header("tsx-final"));
                ObjectNode result = JSON.createObjectNode().put("schema", "opentsx.rust-b3-offline-acceptance.v1")
                        .put("episodes", input.size()).put("values", 2_160_000).put("expectedWindows", unique.size())
                        .put("receivedRecords", received.size()).put("deduplicatedWindows", deduplicated.size())
                        .put("finalWindows", finalCount).put("missingWindows", 0).put("duplicateWindows", 0)
                        .put("offlineConnectionRefusals", link.denied.get());
                result.set("execution", executed); result.set("forward", caughtUp);
                JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("result.json").toFile(), result);
                observer.check(); link.check();
                Path evidence = Files.createDirectories(Path.of("target", "rust-b3-offline"));
                for (var entry : Map.of("result.json", directory.resolve("result.json"),
                        "protocol.json", directory.resolve("protocol/result.json"),
                        "placement.json", directory.resolve("placement/result.json"),
                        "qualification.json", privateDirectory.resolve("qualification.json")).entrySet()) {
                    Files.copy(entry.getValue(), evidence.resolve(entry.getKey()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void qualify(Path original, Path runner, String bootstrap, Path runnerLog, Path contractsLog, Path output) throws Exception {
        Path protocol = directory.resolve("protocol"), placement = directory.resolve("placement");
        Path probe = original.resolveSibling("b1_acceptance"), placementBinary = original.resolveSibling("b1_placement");
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

    private JsonNode invoke(Path runner, String mode, Path config, Path plan) throws Exception {
        return JSON.readTree(process(List.of(runner.toString(), mode, config.toString(), plan.toString()), mode + "-" + System.nanoTime(), null));
    }

    private String process(List<String> arguments, String name, String bootstrap) throws Exception {
        Path stdout = directory.resolve(name + ".stdout.log"), stderr = directory.resolve(name + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        if (bootstrap != null) builder.environment().put("KAFSCALE_BOOTSTRAP", bootstrap);
        Process process = builder.start();
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

    static final class SignalLink implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final java.util.concurrent.ExecutorService threads = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "b3-signal-link"); t.setDaemon(true); return t; });
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger denied = new AtomicInteger();
        volatile boolean online;
        volatile boolean closed;

        SignalLink(String backend) throws IOException {
            int port = Integer.parseInt(backend.substring(backend.lastIndexOf(':') + 1));
            threads.submit(() -> {
                while (!closed) try {
                    Socket client = server.accept();
                    if (!online) { denied.incrementAndGet(); client.close(); continue; }
                    Socket remote = new Socket(InetAddress.getLoopbackAddress(), port);
                    sockets.add(client); sockets.add(remote);
                    threads.submit(() -> fromRunner(client, remote)); threads.submit(() -> toRunner(remote, client));
                } catch (IOException error) { if (!closed) failure.compareAndSet(null, error); }
            });
        }

        String bootstrap() { return "127.0.0.1:" + server.getLocalPort(); }
        void check() { if (failure.get() != null) throw new IllegalStateException("B3 signal link failed", failure.get()); }
        private void fromRunner(Socket runner, Socket remote) {
            try {
                byte[] buffer = new byte[8192];
                for (int count; (count = readRunner(runner, buffer)) >= 0; ) remote.getOutputStream().write(buffer, 0, count);
                remote.shutdownOutput();
            } catch (IOException error) {
                if (!closed && !runner.isClosed() && !remote.isClosed()) failure.compareAndSet(null, error);
                closeSocket(runner); closeSocket(remote);
            }
        }
        private void toRunner(Socket remote, Socket runner) {
            try {
                byte[] buffer = new byte[8192];
                for (int count; (count = remote.getInputStream().read(buffer)) >= 0; ) {
                    try { runner.getOutputStream().write(buffer, 0, count); } catch (SocketException ended) { return; }
                }
            } catch (IOException error) { if (!closed && !runner.isClosed() && !remote.isClosed()) failure.compareAndSet(null, error); }
            finally { closeSocket(runner); closeSocket(remote); }
        }
        private static int readRunner(Socket runner, byte[] buffer) throws IOException {
            try { return runner.getInputStream().read(buffer); } catch (SocketException reset) { return -1; }
        }
        private void closeSocket(Socket socket) {
            try { socket.close(); } catch (IOException error) { if (!closed) failure.compareAndSet(null, error); }
            sockets.remove(socket);
        }
        @Override public void close() throws IOException {
            closed = true; server.close(); for (Socket socket : sockets) closeSocket(socket); threads.shutdownNow();
        }
    }
}
