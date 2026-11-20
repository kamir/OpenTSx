package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.wayang.federation.AggregateForwarder;
import org.opentsx.wayang.federation.AggregateInventory;
import org.opentsx.wayang.federation.AggregateRecord;
import org.opentsx.wayang.federation.CompletenessCoordinator;
import org.opentsx.wayang.federation.SiteStats;
import org.opentsx.wayang.site.ConformanceHandshake;
import org.opentsx.wayang.site.EphemeralSiteTlsFixture;
import org.opentsx.wayang.site.RunnerPlan;
import org.opentsx.wayang.site.SignedJws;
import org.opentsx.wayang.site.TsxPlanClient;
import org.opentsx.wayang.site.TsxPlanService;
import org.opentsx.wayang.site.TsxSiteAgent;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

final class FederationFixture implements AutoCloseable {
    enum Fault { NONE, WRONG_FINGERPRINT, OVERLAPPING_SERIES }
    record ByteRow(String route, String site, String topic, long produceFrameBytes, long produceRecords, long produceErrors,
                   long httpBodyBytes, long httpRecords, long tlsClientBytes, long elapsedNanos) { }
    record WindowRow(Map<String, String> columns) { }
    record Result(String route, List<AggregateRecord> partials, List<AggregateRecord> merged, List<SeriesStats> driverOutput,
                  List<ByteRow> bytes, List<WindowRow> windows, long elapsedNanos, List<JsonNode> reports) { }
    record CentralResult(List<SeriesStats> statistics, long elapsedNanos, long cpuNanos,
                         long firstFetchNanos, long firstStatisticsNanos) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private final List<String> sites;
    private final boolean mixed;
    private static final String JOB = "w3";
    private static final String ABI = "tsx-java/1";
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private final Path directory;
    private final FederationBrokers brokers;
    private final EphemeralSiteTlsFixture tls;
    private final TopicLayout layout = new TopicLayout("wind");
    private final KeyPair driver;
    private final Map<String, KeyPair> siteKeys = new TreeMap<>();
    private final Map<String, List<ConsumerRecord<byte[], byte[]>>> inputRecords = new TreeMap<>();
    private final FederatedWindparkData data;
    private final String digest;
    private final String version;
    private final String dagText;
    private final Map<String, Result> results = new HashMap<>();
    private int sessions;

    FederationFixture(Path directory) throws Exception { this(directory, Fault.NONE, 3); }

    FederationFixture(Path directory, Fault fault) throws Exception { this(directory, fault, 3); }

    FederationFixture(Path directory, int siteCount) throws Exception { this(directory, Fault.NONE, siteCount); }

    static FederationFixture mixed(Path directory) throws Exception { return new FederationFixture(directory, Fault.NONE, 3, true); }

    private FederationFixture(Path directory, Fault fault, int siteCount) throws Exception { this(directory, fault, siteCount, false); }

    private FederationFixture(Path directory, Fault fault, int siteCount, boolean mixed) throws Exception {
        this.mixed = mixed;
        sites = FederatedWindparkData.siteNames(siteCount);
        this.directory = directory;
        Files.createDirectories(directory);
        FederatedWindparkData generated = FederatedWindparkData.load(siteCount);
        List<String> clusters = java.util.stream.Stream.concat(sites.stream(), java.util.stream.Stream.of("signal")).toList();
        brokers = new FederationBrokers(directory.resolve("brokers"), clusters);
        EphemeralSiteTlsFixture created = null;
        try {
            created = EphemeralSiteTlsFixture.create(sites.toArray(String[]::new));
            tls = created;
            driver = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            for (String site : sites) siteKeys.put(site, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
            digest = SiteConformanceChecks.artifactDigest();
            version = SiteConformanceChecks.version(ROOT);
            dagText = dag().toString();
            for (String cluster : clusters) {
                try (Admin admin = KafScaleClients.admin(brokers.bootstrap(cluster))) {
                    layout.provision(admin, 3, (short) 1);
                    layout.provisionTopic(admin, layout.aggregates(), (short) 1);
                    layout.provisionTopic(admin, layout.signals(), (short) 1);
                    if (cluster.equals("signal") || mixed && cluster.equals("s3")) layout.provisionTopic(admin, layout.dagStatus(), (short) 1);
                }
            }
            for (String site : sites) writeInput(site, generated.sites().get(site));
            if (fault == Fault.WRONG_FINGERPRINT) writeInput("s1", List.of(generated.centralized().values().iterator().next()));
            if (fault == Fault.OVERLAPPING_SERIES) writeInput("s2", List.of(generated.sites().get("s1").get(0)));
            for (String site : sites) inputRecords.put(site, readAll(brokers.bootstrap(site), layout.episodes()));
            if (fault == Fault.NONE) {
                Map<String, Episode> received = new HashMap<>();
                OpenTsxAvro avro = new OpenTsxAvro();
                for (var records : inputRecords.values()) for (var record : records) {
                    Episode episode = avro.decode(record.value(), Episode.class);
                    assertNull(received.put(episode.getEpisodeId(), episode), "duplicate source episode ID");
                }
                List<Episode> ordered = new ArrayList<>();
                for (Episode original : generated.episodes()) {
                    Episode actual = received.remove(original.getEpisodeId());
                    assertNotNull(actual, "source episode was not received");
                    assertArrayEquals(JavaTsxTopicSink.toRecord(avro, layout.episodes(), original).value(),
                            JavaTsxTopicSink.toRecord(avro, layout.episodes(), actual).value());
                    ordered.add(actual);
                }
                assertTrue(received.isEmpty());
                data = FederatedWindparkData.fromEpisodes(ordered, siteCount);
            } else data = generated;
        } catch (Exception | AssertionError error) {
            if (created != null) try { created.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            try { brokers.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    FederatedWindparkData data() { return data; }
    String specHash() { return ExecutionAssignments.sha256(dagText); }

    static JsonNode submitConformance(String site, String route, java.util.function.Supplier<JsonNode> request) {
        try { return request.get(); }
        catch (TsxPlanService.HttpFailure error) {
            throw new IllegalStateException("conformance request failed: site=" + site + ", route=" + route + ", HTTP=" + error.status(), error);
        }
    }

    private ObjectNode dag() {
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "w3-stats");
        ObjectNode source = dag.putObject("source");
        if (mixed) source.put("kind", "kafscale-broker");
        source.putArray("topics").add(layout.episodes());
        var nodes = dag.putArray("nodes");
        nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        ObjectNode statistics = nodes.addObject().put("id", "stats").put("op", "stats.welford-ddsketch");
        statistics.putArray("in").add("decode");
        statistics.putObject("params").put("alpha", 0.01).putArray("windows").add("PT10M").add("PT1H");
        dag.putArray("sinks").addObject().put("from", "stats").put("topic", layout.aggregates())
                .put("schema", "org.opentsx.model.v2.SeriesStats");
        return dag;
    }

    private void writeInput(String site, List<? extends SpecificRecord> values) throws Exception {
        List<Future<?>> sent = new ArrayList<>();
        OpenTsxAvro avro = new OpenTsxAvro();
        try (var producer = KafScaleClients.producer(brokers.bootstrap(site))) {
            for (var value : values) sent.add(producer.send(JavaTsxTopicSink.toRecord(avro, layout.episodes(), value)));
            producer.flush();
            for (Future<?> future : sent) future.get();
        }
    }

    private static Map<TopicPartition, long[]> cuts(String bootstrap, String topic) {
        try (var consumer = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, TIMEOUT).stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            var beginnings = consumer.beginningOffsets(partitions, TIMEOUT);
            var ends = consumer.endOffsets(partitions, TIMEOUT);
            Map<TopicPartition, long[]> ranges = new TreeMap<>(java.util.Comparator.comparingInt(TopicPartition::partition));
            partitions.forEach(p -> ranges.put(p, new long[]{beginnings.get(p), ends.get(p)}));
            return ranges;
        }
    }

    static List<ConsumerRecord<byte[], byte[]>> readAll(String bootstrap, String topic) {
        return read(bootstrap, cuts(bootstrap, topic));
    }

    private static List<ConsumerRecord<byte[], byte[]>> read(String bootstrap, Map<TopicPartition, long[]> cuts) {
        List<ConsumerRecord<byte[], byte[]>> output = new ArrayList<>();
        try (var consumer = KafScaleClients.consumer(bootstrap)) { LogReader.read(consumer, cuts, output::add, TIMEOUT); }
        return output;
    }

    Result routeS() throws Exception { return route("signal-cluster"); }
    Result routeJ() throws Exception { return route("job-state"); }

    private Result route(String route) throws Exception {
        if (mixed && !route.equals("signal-cluster")) throw new IllegalArgumentException("mixed qualification requires the signal-cluster route");
        Result previous = results.get(route);
        if (previous != null) return previous;
        try (Session session = session(route)) {
            for (String site : sites) assertEquals("accepted", session.conform(site).path("result").asText());
            if (mixed) session.admitMixed();
            long started = System.nanoTime();
            session.execute(sites);
            long elapsed = System.nanoTime() - started;
            Result result = session.result(elapsed);
            assertEquals(data.centralized().size(), result.merged().size());
            assertEquals(Math.multiplyExact(data.centralized().size(), sites.size()), result.partials().size());
            assertEquals(data.centralized().size(), result.driverOutput().size());
            results.put(route, result);
            return result;
        }
    }

    List<ByteRow> centralizedTransfer() throws Exception {
        List<ByteRow> rows = new ArrayList<>();
        long started = System.nanoTime();
        for (String site : sites) {
            try (LatencyProtocolObserver observer = new LatencyProtocolObserver(brokers.port("signal"), false);
                 var producer = KafScaleClients.producer(observer.bootstrap())) {
                List<Future<?>> sent = new ArrayList<>();
                for (var record : inputRecords.get(site)) {
                    sent.add(producer.send(new ProducerRecord<>(layout.episodes(), null, record.timestamp(),
                            new String(record.key(), StandardCharsets.UTF_8), record.value(), record.headers())));
                }
                producer.flush();
                for (Future<?> future : sent) future.get();
                observer.check();
                assertEquals(inputRecords.get(site).size(), observer.produceRecords.get());
                assertEquals(0, observer.produceErrors.get());
                rows.add(new ByteRow("centralized", site, layout.episodes(), observer.produceFrameBytes.get(),
                        observer.produceRecords.get(), observer.produceErrors.get(), -1, -1, -1, -1));
            }
        }
        long elapsed = System.nanoTime() - started;
        Map<String, byte[]> expected = new HashMap<>();
        for (var records : inputRecords.values()) for (var record : records) {
            Episode episode = new OpenTsxAvro().decode(record.value(), Episode.class);
            assertNull(expected.put(episode.getEpisodeId(), record.value()));
        }
        for (var record : readAll(brokers.bootstrap("signal"), layout.episodes())) {
            Episode episode = new OpenTsxAvro().decode(record.value(), Episode.class);
            assertArrayEquals(expected.remove(episode.getEpisodeId()), record.value());
        }
        assertTrue(expected.isEmpty());
        return rows.stream().map(r -> withElapsed(r, elapsed)).toList();
    }

    private static ByteRow withElapsed(ByteRow r, long elapsed) {
        return new ByteRow(r.route(), r.site(), r.topic(), r.produceFrameBytes(), r.produceRecords(), r.produceErrors(),
                r.httpBodyBytes(), r.httpRecords(), r.tlsClientBytes(), elapsed);
    }

    CentralResult centralizedPlan() throws Exception {
        Map<Integer, long[]> ranges = new TreeMap<>();
        cuts(brokers.bootstrap("signal"), layout.episodes()).forEach((partition, range) -> ranges.put(partition.partition(), range));
        assertEquals(data.episodes().size(), ranges.values().stream().mapToLong(range -> range[1] - range[0]).sum());
        try (LatencyProtocolObserver observer = new LatencyProtocolObserver(brokers.port("signal"), false)) {
            TsxRead read = new TsxRead(observer.bootstrap(), layout.episodes(), ranges, KafScaleFixture.EPISODE_ONLY, JOB, 1, true);
            List<SeriesStats> raw = new ArrayList<>();
            long[] firstStatistics = {-1};
            observer.begin(layout.episodes(), 0);
            long started = System.nanoTime();
            long cpu = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
            TsxCompileContext context = new TsxCompileContext(Map.of(), Map.of(layout.aggregates(),
                    new TsxCompileContext.Boundary(Set.of("PT10M", "PT1H"), false)), OptionalLong.empty(),
                    Map.of(), Map.of(), Map.of(), "central", OptionalLong.empty(), true);
            DagToWayang compiled = DagToWayang.compile(dagText, layout, ROOT.resolve("systems/processors"), context);
            Operator output = compiled.outputs(Map.of(layout.episodes(), new TsxTopicSource(read)), new TsxState(Map.of())).get("stats");
            LocalCallbackSink<SpecificRecord> sink = new LocalCallbackSink<>((java.util.function.Consumer<SpecificRecord>) record -> {
                if (!(record instanceof SeriesStats statistics)) throw new IllegalStateException("central baseline received a non-statistics record");
                if (firstStatistics[0] < 0) firstStatistics[0] = System.nanoTime();
                raw.add(statistics);
            }, SpecificRecord.class);
            output.connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
            List<SeriesStats> grouped = SiteStats.group(raw, data.rawGroups(), 0.01);
            long elapsed = System.nanoTime() - started;
            long cpuElapsed = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos() - cpu;
            long firstFetch = observer.end() - started;
            assertEquals(data.centralized().size(), grouped.size());
            assertTrue(firstFetch >= 0 && firstStatistics[0] >= started);
            return new CentralResult(grouped, elapsed, cpuElapsed, firstFetch, firstStatistics[0] - started);
        }
    }

    Session conformanceSession() throws Exception { return session("signal-cluster"); }
    Session session(String route) throws Exception { return new Session(route, directory.resolve("session-" + sessions++)); }

    final class Session implements AutoCloseable {
        private final String route;
        private final Path state;
        private final MutableClock clock = new MutableClock();
        private final Clock serviceClock = mixed ? Clock.systemUTC() : clock;
        private final RustMixedSite rust;
        private final Map<String, RunnerPlan> plans = new TreeMap<>();
        private final Map<String, String> signedPlans = new TreeMap<>();
        private final Map<String, JsonNode> rustOutcomes = new TreeMap<>();
        private final Map<String, TsxSiteAgent> agents = new TreeMap<>();
        private final Map<String, TsxPlanClient> clients = new TreeMap<>();
        private final Map<String, SiteTrafficProxy> proxies = new TreeMap<>();
        private final Map<String, LatencyProtocolObserver> observers = new TreeMap<>();
        private final Map<String, Long> tlsBefore = new TreeMap<>();
        private final Map<String, Long> bodies = new TreeMap<>();
        private final Map<String, Long> httpRecords = new TreeMap<>();
        private final Map<String, AggregateRecord> accepted = new TreeMap<>();
        private final List<AggregateRecord> emitted = new ArrayList<>();
        private final List<SeriesStats> driverOutput = new ArrayList<>();
        private final List<JsonNode> reports = new ArrayList<>();
        private final Map<String, List<JsonNode>> statuses = new TreeMap<>();
        private final AggregateInventory inventory;
        private CompletenessCoordinator coordinator;
        private final TsxPlanService service;
        private final KafkaProducer<String, byte[]> mergedProducer;
        private final Map<Integer, Long> centralNext = new TreeMap<>();

        Session(String route, Path state) throws Exception {
            this.route = route;
            this.state = state;
            Files.createDirectories(state);
            rust = mixed ? new RustMixedSite(state.resolve("rust-s3"), brokers.bootstrap("s3"), JOB) : null;
            List<String> assignments = new ArrayList<>();
            for (String site : sites) assignments.add(assignment(site).toString());
            ArrayNode members = JSON.createArrayNode();
            for (String text : assignments) {
                JsonNode assignment = JSON.readTree(text);
                ObjectNode member = members.addObject();
                for (String field : List.of("assignment", "site", "node", "generation", "fence", "fragmentHash")) member.set(field, assignment.get(field));
            }
            ArrayNode scopes = JSON.createArrayNode();
            data.centralized().keySet().forEach(w -> scopes.addObject().put("groupId", w.groupId()).put("windowType", w.windowType())
                    .put("startMicros", Long.toString(w.startMicros())).put("endMicros", Long.toString(w.endMicros())));
            inventory = new AggregateInventory(JOB, 1, specHash(), members, scopes);
            if (!mixed) {
                assignments.forEach(inventory::admit);
                coordinator = new CompletenessCoordinator(inventory, Duration.ofMinutes(1), 0.01, clock, state.resolve("merge.json"));
            }
            mergedProducer = KafScaleClients.producer(brokers.bootstrap("signal"));
            Map<String, java.security.PublicKey> publicKeys = new TreeMap<>();
            siteKeys.forEach((site, key) -> publicKeys.put(site, key.getPublic()));
            service = new TsxPlanService(driver.getPublic(), publicKeys, tls.certificateSites(), state.resolve("driver"), serviceClock,
                    this::status, (plan, delivery) -> accept(AggregateRecord.fromDelivery(delivery)));
            int index = 0;
            for (String site : sites) {
                ObjectNode document = document(site, assignments.get(index++), members, scopes);
                String compact = SignedJws.sign(JSON.writeValueAsBytes(document), driver.getPrivate());
                plans.put(site, RunnerPlan.verify(compact, driver.getPublic()));
                signedPlans.put(site, compact);
                var pin = pin(site);
                service.registerQualifiedRuntime(site, pin.node(), pin.version(), pin.digest(), pin.stateAbi(), pin.runtime());
                service.publish(compact);
                if (!isRust(site)) agents.put(site, agent(site));
                observers.put(site, new LatencyProtocolObserver(brokers.port("signal"), false));
            }
            int port = service.start(new InetSocketAddress("127.0.0.1", 0), tls.serverContext()).getAddress().getPort();
            for (String site : sites) {
                SiteTrafficProxy proxy = new SiteTrafficProxy(port);
                proxies.put(site, proxy);
                clients.put(site, new TsxPlanClient(URI.create("https://localhost:" + proxy.port()), site, tls.clientContext(site)));
            }
            cuts(brokers.bootstrap("signal"), layout.aggregates()).forEach((partition, range) -> centralNext.put(partition.partition(), range[1]));
            if (mixed) {
                try {
                    rust.configure(endpoint("s3"), layout, observers.get("s3").bootstrap(), tls.clientPem("s3"), driver,
                            siteKeys.get("s3"), signedPlans.get("s3"));
                } catch (Exception | AssertionError error) {
                    try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
            }
        }

        private boolean isRust(String site) { return mixed && site.equals("s3"); }

        private ConformanceHandshake.QualifiedRuntime pin(String site) {
            return isRust(site) ? rust.pin() : new ConformanceHandshake.QualifiedRuntime(site, "node-" + site, version, digest, ABI);
        }

        private void admitMixed() {
            for (String site : sites) service.admitAggregateInventory(site, JOB, inventory);
            coordinator = new CompletenessCoordinator(inventory, Duration.ofMinutes(1), 0.01, clock, state.resolve("merge.json"));
        }

        private ObjectNode assignment(String site) {
            var pin = pin(site);
            ObjectNode a = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1").put("deployment", JOB)
                    .put("generation", "1").put("assignment", "assignment-" + site).put("attempt", "attempt-1").put("fence", "1")
                    .put("fragment", "fragment-" + site).put("site", site).put("node", "node-" + site).put("runtime", pin.runtime())
                    .put("catalog", "tsx-catalog/1").put("artifactDigest", pin.digest()).put("specHash", specHash()).put("sourceDagText", dagText)
                    .put("fragmentHash", ExecutionAssignments.sha256(dagText)).put("fragmentText", dagText).put("mode", "bounded");
            var positions = a.putArray("inputs").addObject().put("binding", "site-" + site).put("topic", layout.episodes())
                    .put("partitionCount", "3").putArray("positions");
            cuts(brokers.bootstrap(site), layout.episodes()).forEach((partition, range) -> positions.addObject()
                    .put("partition", Integer.toString(partition.partition())).put("from", Long.toString(range[0])).put("to", Long.toString(range[1])));
            return a;
        }

        private ObjectNode document(String site, String assignment, ArrayNode members, ArrayNode scopes) {
            ObjectNode document = JSON.createObjectNode().put("schema", "opentsx.runner-plan.v1").put("job", JOB).put("planVersion", 1)
                    .put("issued", mixed ? serviceClock.instant().minusSeconds(60).toString() : "2026-10-01T00:00:00Z")
                    .put("expires", mixed ? serviceClock.instant().plusSeconds(3600).toString() : "2026-10-02T00:00:00Z").put("site", site)
                    .put("catalog", "tsx-catalog/1").put("specHash", specHash()).put("assignmentText", assignment);
            document.putArray("inputContracts").addObject().put("binding", "site-" + site).put("topic", layout.episodes())
                    .putArray("fingerprints").add(KafScaleFixture.EPISODE_ONLY.iterator().next());
            document.putArray("outputs").addObject().put("sink", layout.aggregates()).put("cluster", route.equals("signal-cluster") ? "site" : "signal")
                    .put("topic", layout.aggregates()).put("partitionCount", "1").putArray("fingerprints")
                    .add(String.format(java.util.Locale.ROOT, "%016x", OpenTsxAvro.fingerprint(SeriesStats.getClassSchema())));
            ObjectNode federation = document.putObject("federation").put("route", route).put("maxDelay", "PT1M");
            federation.putArray("groupBy").add("park");
            federation.set("expectedAssignments", members.deepCopy());
            federation.set("scopes", scopes.deepCopy());
            return document;
        }

        private TsxSiteAgent agent(String site) {
            ObjectNode policy = JSON.createObjectNode();
            policy.putArray("domains").add(layout.domain());
            policy.putObject("inputs").putArray("site-" + site).add(layout.episodes());
            var outputs = policy.putObject("outputs");
            outputs.putArray("site").add(layout.aggregates());
            outputs.putArray("signal").add(layout.aggregates());
            policy.putArray("catalogs").add("tsx-catalog/1");
            var runtime = new ConformanceHandshake.QualifiedRuntime(site, "node-" + site, version, digest, ABI);
            return new TsxSiteAgent(runtime, driver.getPublic(), policy, state.resolve(site), serviceClock);
        }

        private synchronized void status(RunnerPlan plan, JsonNode value) {
            var pin = pin(plan.site());
            if (!value.path("schema").asText().equals("opentsx.dag-status.v1") || !value.path("dag").asText().equals(plan.job())
                    || !value.path("job").asText().equals(plan.job()) || !value.path("site").asText().equals(plan.site())
                    || !value.path("planVersion").isIntegralNumber() || !value.path("planVersion").canConvertToInt()
                    || value.path("planVersion").intValue() != plan.planVersion() || !value.path("track").asText().equals(pin.track())
                    || !value.path("specHash").equals(plan.document().path("specHash"))
                    || !value.path("artifact").isObject() || value.path("artifact").size() != 2
                    || !value.path("artifact").path("version").asText().equals(pin.version())
                    || !value.path("artifact").path("digest").asText().equals(pin.digest())) {
                throw new IllegalArgumentException("received site status identity does not match the qualified plan");
            }
            String state = value.path("state").asText();
            if (!Set.of("running", "stopped", "failed").contains(state) || !value.has("watermark")
                    || !value.path("watermark").isNull() || !value.path("waitingFor").isArray() || !value.path("waitingFor").isEmpty()) {
                throw new IllegalArgumentException("received bounded site status has invalid state or progress");
            }
            for (String field : List.of("records", "skippedBlocks")) {
                JsonNode counter = value.path(field);
                if (!counter.isIntegralNumber() || !counter.canConvertToLong() || counter.longValue() < 0) {
                    throw new IllegalArgumentException("received site status counter must be a nonnegative int64");
                }
            }
            if (state.equals("failed") != value.has("error") || value.has("error")
                    && (!value.path("error").isTextual() || value.path("error").asText().isBlank())) {
                throw new IllegalArgumentException("received site status error does not match its state");
            }
            try {
                long at = Instant.parse(value.path("at").asText()).toEpochMilli();
                var record = new ProducerRecord<String, byte[]>(layout.dagStatus(), null, at, plan.job() + "|" + plan.site(), JSON.writeValueAsBytes(value));
                record.headers().add(TopicLayout.HEADER_SCHEMA, "opentsx.dag-status.v1".getBytes(StandardCharsets.UTF_8));
                mergedProducer.send(record).get();
                statuses.computeIfAbsent(plan.site(), ignored -> new ArrayList<>()).add(value.deepCopy());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("site status publication was interrupted", error);
            } catch (java.util.concurrent.ExecutionException error) {
                throw new IllegalStateException("site status publication was not acknowledged", error.getCause());
            } catch (java.io.IOException | java.time.DateTimeException | ArithmeticException error) {
                throw new IllegalArgumentException("site status cannot be encoded", error);
            }
        }

        synchronized List<JsonNode> statuses(String site) {
            return statuses.getOrDefault(site, List.of()).stream().<JsonNode>map(JsonNode::deepCopy).toList();
        }

        List<ConsumerRecord<byte[], byte[]>> storedStatuses() { return readAll(brokers.bootstrap("signal"), layout.dagStatus()); }

        JsonNode conform(String site) throws Exception {
            if (isRust(site)) {
                String signed = rust.conform();
                JsonNode report = JSON.readTree(SignedJws.verify(signed, siteKeys.get(site).getPublic()));
                reports.add(report.deepCopy());
                return submitConformance(site, route, () -> clients.get(site).conformance(signed));
            }
            JsonNode challenge = clients.get(site).challenge(JOB);
            Map<String, String> bindings = Map.of("site-" + site, brokers.bootstrap(site), "site", brokers.bootstrap(site), "signal", brokers.bootstrap("signal"));
            var runtime = new ConformanceHandshake.QualifiedRuntime(site, "node-" + site, version, digest, ABI);
            JsonNode report = SiteConformanceChecks.report(challenge, brokers.bootstrap(site), bindings, ROOT, state.resolve("conformance-" + site), runtime, serviceClock);
            reports.add(report.deepCopy());
            String signed = SignedJws.sign(JSON.writeValueAsBytes(report), siteKeys.get(site).getPrivate());
            System.out.println("Conformance request: site=" + site + ", route=" + route
                    + ", usable_bytes=" + Files.getFileStore(state).getUsableSpace());
            return submitConformance(site, route, () -> clients.get(site).conformance(signed));
        }

        JsonNode poll(String site, Function<RunnerPlan, JsonNode> execution) {
            return agents.get(site).pollOnce(clients.get(site), execution).orElseThrow();
        }

        void execute(List<String> sites) {
            for (String site : sites) tlsBefore.putIfAbsent(site, proxies.get(site).clientBytes.get());
            TsxSiteStage stage = new TsxSiteStage(data.centralized().size(), () -> {
                for (String site : sites) {
                    if (isRust(site)) {
                        executeRust();
                        continue;
                    }
                    TsxSiteAgent agent = agents.get(site);
                    if (agent == null) throw new IllegalStateException("site is stopped");
                    TsxCompileContext context = new TsxCompileContext(Map.of(), Map.of(layout.aggregates(),
                            new TsxCompileContext.Boundary(Set.of("PT10M", "PT1H"), false)), OptionalLong.empty(), Map.of(), Map.of(), Map.of(), site, OptionalLong.empty(), true);
                    TsxBoundedSiteExecution execution = new TsxBoundedSiteExecution(agent, Map.of("site-" + site, brokers.bootstrap(site)),
                            ROOT.resolve("systems/processors"), this::publish);
                    JsonNode outcome = agent.pollOnce(clients.get(site), plan -> execution.run(plan, context)).orElseThrow();
                    assertEquals("completed", outcome.path("state").asText());
                    if (route.equals("signal-cluster")) forward(site);
                }
                return finalStatistics();
            });
            LocalCallbackSink<SeriesStats> sink = LocalCallbackSink.createCollectingSink(driverOutput, SeriesStats.class);
            stage.connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxSitePlugin()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        }

        private void executeRust() {
            try {
                JsonNode outcome = rust.run("run");
                assertEquals("completed", outcome.path("state").asText());
                assertTrue(outcome.path("completed").asBoolean());
                assertEquals(data.centralized().size(), outcome.path("forwardedRecords").asLong());
                List<JsonNode> sourceStatuses = statuses("s3");
                assertTrue(sourceStatuses.stream().anyMatch(s -> s.path("state").asText().equals("running")));
                JsonNode terminal = sourceStatuses.get(sourceStatuses.size() - 1);
                assertEquals("stopped", terminal.path("state").asText());
                assertEquals("B", terminal.path("track").asText());
                assertEquals(inputRecords.get("s3").size(), terminal.path("records").asInt());
                assertEquals(rust.pin().digest(), terminal.path("artifact").path("digest").asText());
                JsonNode replay = rust.run("run-replay");
                assertEquals(outcome, replay);
                rustOutcomes.put("run", outcome.deepCopy());
                rustOutcomes.put("replay", replay.deepCopy());
                acceptForwarded();
            } catch (Exception error) { throw new IllegalStateException("mixed Rust site execution failed", error); }
        }

        private void publish(RunnerPlan plan, List<AggregateRecord> records) {
            String site = plan.site();
            if (route.equals("signal-cluster")) {
                try (var producer = KafScaleClients.producer(brokers.bootstrap(site))) {
                    List<Future<?>> sent = new ArrayList<>();
                    for (var record : records) sent.add(producer.send(record.producerRecord(layout.aggregates())));
                    producer.flush();
                    for (Future<?> future : sent) future.get();
                } catch (Exception error) { throw new IllegalStateException("local partial publication failed", error); }
            } else {
                for (int from = 0; from < records.size(); from += AggregateRecord.MAX_DELIVERY_RECORDS) {
                    List<AggregateRecord> batch = records.subList(from, Math.min(records.size(), from + AggregateRecord.MAX_DELIVERY_RECORDS));
                    JsonNode delivery = AggregateRecord.toDelivery(batch);
                    bodies.merge(site, (long) delivery.toString().getBytes(StandardCharsets.UTF_8).length, Math::addExact);
                    httpRecords.merge(site, (long) batch.size(), Math::addExact);
                    clients.get(site).aggregates(JOB, delivery);
                }
            }
        }

        private void forward(String site) {
            AggregateForwarder.copy(brokers.bootstrap(site), observers.get(site).bootstrap(), layout.aggregates(), state.resolve("forward-" + site + ".json"));
            acceptForwarded();
        }

        private void acceptForwarded() {
            Map<TopicPartition, long[]> ranges = cuts(brokers.bootstrap("signal"), layout.aggregates());
            ranges.forEach((partition, range) -> range[0] = centralNext.get(partition.partition()));
            List<AggregateRecord> records = read(brokers.bootstrap("signal"), ranges).stream().map(AggregateRecord::fromConsumerRecord).toList();
            accept(records);
            ranges.forEach((partition, range) -> centralNext.put(partition.partition(), range[1]));
        }

        private void accept(List<AggregateRecord> records) {
            records.forEach(inventory::validate);
            for (var record : records) {
                SeriesStats statistics = record.statistics();
                var window = new FederatedWindparkData.Window(statistics.getSeriesId(), statistics.getWindowType(),
                        Micros.of(statistics.getWindowStart()), Micros.of(statistics.getWindowEnd()));
                if (!isRust(record.header("tsx-site"))) {
                    assertArrayEquals(data.uncollapsedSites().get(record.header("tsx-site")).get(window), sketch(statistics), "site sketch did not collapse");
                }
                publishMerged(coordinator.accept(record));
                AggregateRecord previous = accepted.putIfAbsent(record.key() + "|" + record.revision(), record);
                if (previous != null) assertEquals(previous, record);
            }
        }

        private void publishMerged(List<AggregateRecord> records) {
            try {
                List<Future<?>> sent = new ArrayList<>();
                records.forEach(record -> sent.add(mergedProducer.send(record.producerRecord(layout.seriesStats()))));
                mergedProducer.flush();
                for (Future<?> future : sent) future.get();
                for (var record : records) {
                    emitted.add(record);
                    coordinator.acknowledge(record);
                }
            } catch (Exception error) { throw new IllegalStateException("central merged publication failed", error); }
        }

        void stop(String site) { agents.remove(site).close(); }
        void restart(String site) { assertNull(agents.putIfAbsent(site, agent(site))); }
        void expireMissing() {
            long end = data.centralized().keySet().stream().mapToLong(FederatedWindparkData.Window::endMicros).max().orElseThrow();
            clock.now = Micros.toInstant(end).plus(Duration.ofMinutes(1)).plusNanos(1000);
            publishMerged(coordinator.advance());
        }
        void restoreCoordinator() {
            coordinator = new CompletenessCoordinator(inventory, Duration.ofMinutes(1), 0.01, clock, state.resolve("merge.json"));
            publishMerged(coordinator.pending());
        }
        List<AggregateRecord> merged() { return List.copyOf(emitted); }
        List<ConsumerRecord<byte[], byte[]>> storedMerged() { return readAll(brokers.bootstrap("signal"), layout.seriesStats()); }
        URI endpoint(String site) { return URI.create("https://localhost:" + proxies.get(site).port()); }

        private List<SeriesStats> finalStatistics() {
            Map<String, AggregateRecord> latest = new TreeMap<>();
            for (var record : emitted) latest.merge(record.key(), record, (a, b) -> a.revision() > b.revision() ? a : b);
            return latest.values().stream().filter(r -> r.header("tsx-final").equals("true")).map(AggregateRecord::statistics).toList();
        }

        Result result(long elapsed) throws Exception {
            List<ByteRow> bytes = new ArrayList<>();
            for (String site : sites) {
                if (route.equals("signal-cluster")) {
                    var observer = observers.get(site);
                    observer.check();
                    assertEquals(data.centralized().size(), observer.produceRecords.get());
                    assertEquals(0, observer.produceErrors.get());
                    bytes.add(new ByteRow(route, site, layout.aggregates(), observer.produceFrameBytes.get(), observer.produceRecords.get(),
                            observer.produceErrors.get(), -1, -1, -1, elapsed));
                } else {
                    proxies.get(site).check();
                    bytes.add(new ByteRow(route, site, layout.aggregates(), -1, -1, -1, bodies.get(site), httpRecords.get(site),
                            proxies.get(site).clientBytes.get() - tlsBefore.get(site), elapsed));
                }
            }
            if (mixed) {
                Path output = Path.of("target", "rust-b4-mixed");
                Files.createDirectories(output);
                ObjectNode evidence = JSON.createObjectNode().put("schema", "opentsx.rust-b4-mixed-execution.v1")
                        .put("route", route).put("job", JOB);
                ObjectNode commands = evidence.putObject("sites");
                for (String site : sites) {
                    RunnerPlan plan = plans.get(site);
                    var pin = pin(site);
                    ObjectNode command = commands.putObject(site).put("runtime", pin.runtime()).put("track", pin.track())
                            .put("version", pin.version()).put("digest", pin.digest()).put("stateAbi", pin.stateAbi())
                            .put("commandHash", plan.commandHash()).put("specHash", specHash())
                            .put("inputRecords", inputRecords.get(site).size());
                    command.put("fragmentHash", JSON.readTree(plan.document().path("assignmentText").asText()).path("fragmentHash").asText());
                    command.set("statuses", JSON.valueToTree(statuses(site)));
                }
                evidence.set("rustOutcomes", JSON.valueToTree(rustOutcomes));
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("execution.json").toFile(), evidence);
            }
            return new Result(route, List.copyOf(accepted.values()), List.copyOf(emitted), List.copyOf(driverOutput), List.copyOf(bytes),
                    windows(route, emitted), elapsed, List.copyOf(reports));
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            for (AutoCloseable resource : java.util.stream.Stream.of(agents.values().stream(), java.util.stream.Stream.of(mergedProducer),
                    observers.values().stream(), proxies.values().stream(), java.util.stream.Stream.of(service)).flatMap(s -> s).toList()) {
                try { resource.close(); } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failure != null) throw failure;
        }
    }

    private List<WindowRow> windows(String route, List<AggregateRecord> records) throws java.security.NoSuchAlgorithmException {
        List<WindowRow> rows = new ArrayList<>();
        Map<FederatedWindparkData.Window, double[]> raw = mixed ? rawWindows() : Map.of();
        for (AggregateRecord record : records) {
            if (!record.header("tsx-final").equals("true")) continue;
            SeriesStats actual = record.statistics();
            var window = new FederatedWindparkData.Window(actual.getSeriesId(), actual.getWindowType(), Micros.of(actual.getWindowStart()), Micros.of(actual.getWindowEnd()));
            SeriesStats expected = data.centralized().get(window);
            assertNotNull(expected);
            assertArrayEquals(data.uncollapsedCentral().get(window), sketch(expected), "central sketch did not collapse");
            boolean exact = expected.getValidCount() == actual.getValidCount() && expected.getNanCount() == actual.getNanCount()
                    && data.gaps().get(window) == actual.getGapCount() && Double.compare(expected.getMin(), actual.getMin()) == 0
                    && Double.compare(expected.getMax(), actual.getMax()) == 0 && expected.getLastSeen().equals(actual.getLastSeen());
            boolean sketchEqual = Arrays.equals(sketch(expected), sketch(actual));
            double meanError = relative(expected.getMean(), actual.getMean());
            double m2Error = relative(expected.getM2(), actual.getM2());
            assertTrue(exact, window.toString());
            if (!mixed) assertTrue(sketchEqual, window.toString());
            if (!mixed) {
                assertTrue(meanError <= 3e-12, window + " mean=" + meanError);
                assertTrue(m2Error <= 3e-12, window + " m2=" + m2Error);
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("route", route); row.put("group_id", window.groupId()); row.put("window_type", window.windowType());
            row.put("window_start_micros", Long.toString(window.startMicros())); row.put("window_end_micros", Long.toString(window.endMicros()));
            row.put("valid_count", Long.toString(actual.getValidCount())); row.put("nan_count", Long.toString(actual.getNanCount())); row.put("gap_count", Long.toString(actual.getGapCount()));
            row.put("min", Double.toString(actual.getMin())); row.put("max", Double.toString(actual.getMax())); row.put("mean", Double.toString(actual.getMean())); row.put("m2", Double.toString(actual.getM2()));
            row.put("expected_valid_count", Long.toString(expected.getValidCount())); row.put("expected_nan_count", Long.toString(expected.getNanCount())); row.put("expected_gap_count", Long.toString(data.gaps().get(window)));
            row.put("expected_min", Double.toString(expected.getMin())); row.put("expected_max", Double.toString(expected.getMax())); row.put("expected_mean", Double.toString(expected.getMean())); row.put("expected_m2", Double.toString(expected.getM2()));
            row.put("mean_relative_error", Double.toString(meanError)); row.put("m2_relative_error", Double.toString(m2Error));
            row.put("sketch_equal", Boolean.toString(sketchEqual)); row.put("exact_fields_equal", Boolean.toString(exact));
            if (mixed) {
                row.put("key", record.key()); row.put("revision", Long.toString(record.revision()));
                row.put("final", record.header("tsx-final")); row.put("missing_sites", record.header("tsx-missing-sites"));
                row.put("spec_hash", record.header("tsx-spec-hash")); row.put("generation", record.header("tsx-generation"));
                row.put("payload_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(record.value())));
                row.put("last_seen_micros", Long.toString(Micros.of(actual.getLastSeen())));
                row.put("expected_last_seen_micros", Long.toString(Micros.of(expected.getLastSeen())));
                sketchShape(row, "actual_", actual.getQuantileSketch());
                sketchShape(row, "expected_", expected.getQuantileSketch());
                double[] sorted = raw.get(window);
                assertEquals(actual.getValidCount(), sorted.length, window.toString());
                QuantileSketch actualSketch = QuantileSketch.fromBytes(actual.getQuantileSketch());
                QuantileSketch expectedSketch = QuantileSketch.fromBytes(expected.getQuantileSketch());
                for (double q : new double[]{0.01, 0.5, 0.99}) {
                    String name = q == 0.01 ? "q01" : q == 0.5 ? "q50" : "q99";
                    double exactQuantile = sorted[(int) Math.floor(q * (sorted.length - 1))];
                    row.put(name + "_exact", Double.toString(exactQuantile));
                    quantile(row, name + "_actual", exactQuantile, actualSketch.quantile(q));
                    quantile(row, name + "_expected", exactQuantile, expectedSketch.quantile(q));
                }
            }
            rows.add(new WindowRow(Collections.unmodifiableMap(row)));
        }
        return List.copyOf(rows);
    }

    private Map<FederatedWindparkData.Window, double[]> rawWindows() {
        Map<FederatedWindparkData.Window, double[]> raw = new TreeMap<>();
        Map<FederatedWindparkData.Window, int[]> positions = new TreeMap<>();
        data.centralized().forEach((window, statistics) -> {
            raw.put(window, new double[Math.toIntExact(statistics.getValidCount())]);
            positions.put(window, new int[1]);
        });
        for (Episode episode : data.episodes()) {
            String group = data.rawGroups().get(episode.getSeries().getSeriesId());
            long[] times = Episodes.timestampsMicros(episode);
            double[] values = Episodes.values(episode);
            for (Duration duration : FederatedWindparkData.WINDOWS) {
                long width = duration.toNanos() / 1000;
                for (int index = 0; index < values.length; index++) {
                    if (!Double.isFinite(values[index])) continue;
                    long start = Math.floorDiv(times[index], width) * width;
                    var window = new FederatedWindparkData.Window(group, duration.toString(), start, Math.addExact(start, width));
                    double[] samples = raw.get(window);
                    assertNotNull(samples, window.toString());
                    samples[positions.get(window)[0]++] = values[index];
                }
            }
        }
        raw.forEach((window, samples) -> {
            assertEquals(samples.length, positions.get(window)[0], window.toString());
            Arrays.sort(samples);
        });
        return raw;
    }

    static void sketchShape(Map<String, String> row, String prefix, ByteBuffer bytes) {
        var stores = MixedSketchQualificationTest.inspect(bytes);
        row.put(prefix + "zero_count", Long.toString(stores.zeros()));
        for (String sign : List.of("positive", "negative")) {
            Map<Integer, Long> bins = sign.equals("positive") ? stores.positive() : stores.negative();
            row.put(prefix + sign + "_bins", Integer.toString(bins.size()));
            long span = bins.isEmpty() ? 0 : (long) Collections.max(bins.keySet()) - Collections.min(bins.keySet()) + 1;
            row.put(prefix + sign + "_span", Long.toString(span));
            row.put(prefix + sign + "_min_index", bins.isEmpty() ? "" : Integer.toString(Collections.min(bins.keySet())));
            row.put(prefix + sign + "_max_index", bins.isEmpty() ? "" : Integer.toString(Collections.max(bins.keySet())));
        }
    }

    private static void quantile(Map<String, String> row, String name, double exact, double estimate) {
        boolean zero = Math.abs(exact) < QuantileSketch.MIN_INDEXABLE;
        double lower = Math.min(exact * 0.99, exact * 1.01);
        double upper = Math.max(exact * 0.99, exact * 1.01);
        row.put(name, Double.toString(estimate));
        row.put(name + "_domain", zero ? "zero-bucket" : "outside-zero-without-collapse");
        row.put(name + "_absolute_error", Double.toString(Math.abs(estimate - exact)));
        row.put(name + "_relative_error", zero ? "" : Double.toString(relative(exact, estimate)));
        row.put(name + "_within_alpha", Boolean.toString(zero ? estimate == 0 : estimate >= lower && estimate <= upper));
    }

    private static byte[] sketch(SeriesStats value) {
        ByteBuffer buffer = value.getQuantileSketch().duplicate();
        byte[] result = new byte[buffer.remaining()]; buffer.get(result); return result;
    }
    private static double relative(double expected, double actual) { return expected == 0 ? Math.abs(actual) : Math.abs((actual - expected) / expected); }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("fixture requires UTC"); return this; }
        @Override public Instant instant() { return now; }
    }

    @Override public void close() throws Exception {
        try { tls.close(); } finally { brokers.close(); }
    }
}
