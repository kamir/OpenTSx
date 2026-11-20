package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.vector.VectorProcessorTest;
import org.opentsx.retrieval.vector.VectorProcessorTest.FakeBroker;
import org.opentsx.store.InMemorySeriesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DAG routes of the retrieval server: who may deploy and stop, what reaches the control topic, what is listed. */
class DagRunEndpointTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Stands in for the feed: remembers what was asked. */
    static final class Recorder implements RetrievalServer.Dags {
        final List<String> calls = new ArrayList<>();

        @Override
        public JsonNode deploy(JsonNode dag, String text, String by) {
            if (dag.path("dag").asText().equals("bad")) {
                throw new IllegalArgumentException("node n1 (norm.zscore) is not available in this runtime");
            }
            calls.add("deploy " + dag.path("dag").asText() + " by " + by + " (" + text.length() + " bytes)");
            return EpisodeJson.JSON.createObjectNode().put("dag", dag.path("dag").asText()).put("state", "deployed");
        }

        @Override
        public JsonNode stop(String name, String by) {
            if (name == null) {
                throw new IllegalArgumentException("name the DAG to stop");
            }
            calls.add("stop " + name + " by " + by);
            return EpisodeJson.JSON.createObjectNode().put("dag", name).put("state", "stopped");
        }

        @Override
        public JsonNode list() {
            ObjectNode n = EpisodeJson.JSON.createObjectNode();
            n.putArray("dags").addObject().put("dag", "wind-exp2-smart").put("desired", "deployed");
            return n;
        }
    }

    static RetrievalServer server(RetrievalServer.Dags dags, boolean writes, String token) throws Exception {
        InMemorySeriesStore store = new InMemorySeriesStore();
        return new RetrievalServer(new EpisodeService(store), 0,
                new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1)), dags, writes, token);
    }

    static HttpResponse<String> post(RetrievalServer server, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/yaml").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(RetrievalServer server, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static ObjectNode record(String... fields) {
        ObjectNode n = EpisodeJson.JSON.createObjectNode();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            n.put(fields[i], fields[i + 1]);
        }
        return n;
    }

    @Test
    void theViewOfTheDagsTellsAReportOfBeforeFromOneOfNow() {
        java.util.Map<String, JsonNode> deployments = new java.util.TreeMap<>();
        java.util.Map<String, JsonNode> statuses = new java.util.TreeMap<>();
        deployments.put("a", record("state", "deployed", "by", "admin", "at", "2026-10-13T10:00:00Z"));
        deployments.put("b", record("state", "stopped", "by", "admin", "at", "2026-10-13T10:00:00Z"));
        deployments.put("c", record("state", "deployed", "by", "admin", "at", "2026-10-13T10:00:00Z"));
        statuses.put("a/v", record("vector", "v", "state", "running", "at", "2026-10-13T10:00:30Z"));
        statuses.put("a/old", record("vector", "old", "state", "running", "at", "2026-10-13T09:00:00Z"));   // of an earlier deployment
        statuses.put("b/v", record("vector", "v", "state", "running", "at", "2026-10-13T09:59:00Z"));       // never reported the stop
        statuses.put("c", record("state", "failed", "error", "node n1 (norm.zscore) is not available", "at", "2026-10-13T10:00:05Z"));
        statuses.put("ab/v", record("vector", "v", "state", "running", "at", "2026-10-13T10:00:30Z"));      // another DAG

        JsonNode dags = KafScaleFeed.dagView(deployments, statuses, "2026-10-13T10:01:00Z").path("dags");
        assertEquals(3, dags.size());
        assertEquals("[{\"vector\":\"v\",\"state\":\"running\",\"at\":\"2026-10-13T10:00:30Z\"}]", dags.get(0).path("sinks").toString());
        assertEquals("stopped", dags.get(1).path("sinks").get(0).path("state").asText(), "a stopped DAG does not show as running");
        assertEquals("node n1 (norm.zscore) is not available", dags.get(2).path("refused").asText());
        assertEquals(0, dags.get(2).path("sinks").size());
    }

    @Test
    void aServerWithoutKafScaleHasNoDags() throws Exception {
        try (RetrievalServer server = server(null, false, null)) {
            assertEquals(404, get(server, "/v1/dags").statusCode());
            HttpResponse<String> r = post(server, "/v1/dags/deploy", "dag: x\n");
            assertEquals(404, r.statusCode());
            assertTrue(r.body().contains("--dag-runs true"), r.body());
        }
    }

    @Test
    void listingIsOpenAlsoWhereWritingIsSwitchedOff() throws Exception {
        Recorder dags = new Recorder();
        try (RetrievalServer server = server(dags, false, null)) {
            HttpResponse<String> list = get(server, "/v1/dags");
            assertEquals(200, list.statusCode());
            JsonNode n = EpisodeJson.JSON.readTree(list.body());
            assertEquals("wind-exp2-smart", n.path("dags").get(0).path("dag").asText());
            assertEquals(false, n.path("writes").asBoolean());
            assertEquals(404, post(server, "/v1/dags/deploy", "dag: x\n").statusCode());
            assertEquals(404, post(server, "/v1/dags/stop?name=x", "").statusCode());
            assertEquals(List.of(), dags.calls);
        }
    }

    @Test
    void deploysAndStopsAndReportsARefusal() throws Exception {
        Recorder dags = new Recorder();
        try (RetrievalServer server = server(dags, true, null)) {
            HttpResponse<String> ok = post(server, "/v1/dags/deploy", "dag: wind-exp2-smart\nnodes: []\n",
                    RetrievalServer.BY_HEADER, "admin@example.com");
            assertEquals(200, ok.statusCode());
            assertEquals("deployed", EpisodeJson.JSON.readTree(ok.body()).path("state").asText());
            assertEquals(200, post(server, "/v1/dags/stop?name=wind-exp2-smart", "").statusCode());
            assertEquals(List.of("deploy wind-exp2-smart by admin@example.com (31 bytes)", "stop wind-exp2-smart by unknown"), dags.calls);
            assertEquals(true, EpisodeJson.JSON.readTree(get(server, "/v1/dags").body()).path("writes").asBoolean());

            HttpResponse<String> refused = post(server, "/v1/dags/deploy", "dag: bad\n");
            assertEquals(400, refused.statusCode());
            assertTrue(refused.body().contains("norm.zscore"), refused.body());
            assertEquals(400, post(server, "/v1/dags/stop", "").statusCode(), "no name");

        }
    }

    @Test
    void aDagOverTheLimitAnswers413AndBrokenYamlNamesTheParserMessage() throws Exception {
        Recorder dags = new Recorder();
        try (RetrievalServer server = server(dags, true, null)) {
            HttpResponse<String> large = post(server, "/v1/dags/deploy", "x: " + "a".repeat(262145 - 4) + "\n");
            assertEquals(413, large.statusCode(), large.body());
            assertEquals("{\"error\":\"request body: larger than 262144 bytes; send at most 262144 bytes\",\"type\":\"too_large\"}",
                    large.body());
            assertEquals(200, post(server, "/v1/dags/deploy", "dag: " + "a".repeat(262144 - 6) + "\n").statusCode());

            HttpResponse<String> broken = post(server, "/v1/dags/deploy", "dag: [unclosed\n");
            assertEquals(400, broken.statusCode());
            String error = EpisodeJson.JSON.readTree(broken.body()).path("error").asText();
            assertTrue(error.startsWith("the DAG is not valid YAML: while parsing a flow sequence"), error);
            assertEquals(1, dags.calls.size());
        }
    }

    @Test
    void anUnknownQueryParameterOfStopAnswers400() throws Exception {
        Recorder dags = new Recorder();
        try (RetrievalServer server = server(dags, true, null)) {
            HttpResponse<String> r = post(server, "/v1/dags/stop?nmae=wind-exp2-smart", "");
            assertEquals(400, r.statusCode(), r.body());
            assertEquals("nmae: unknown query parameter; expected one of name", EpisodeJson.JSON.readTree(r.body()).path("error").asText());
            assertEquals(List.of(), dags.calls);
        }
    }

    @Test
    void withARunTokenOnlyCallersThatKnowItMayWrite() throws Exception {
        Recorder dags = new Recorder();
        try (RetrievalServer server = server(dags, true, "s3cret-run-token")) {
            assertEquals(401, post(server, "/v1/dags/deploy", "dag: x\n").statusCode(), "no token");
            assertEquals(401, post(server, "/v1/dags/deploy", "dag: x\n", RetrievalServer.RUN_TOKEN_HEADER, "wrong").statusCode());
            assertEquals(401, post(server, "/v1/dags/stop?name=x", "").statusCode());
            assertEquals(List.of(), dags.calls);
            assertEquals(200, post(server, "/v1/dags/deploy", "dag: x\n", RetrievalServer.RUN_TOKEN_HEADER, "s3cret-run-token").statusCode());
            assertEquals(200, post(server, "/v1/dags/stop?name=x", "", RetrievalServer.RUN_TOKEN_HEADER, "s3cret-run-token").statusCode());
            assertEquals(2, dags.calls.size());
            // reading stays open
            assertEquals(200, get(server, "/v1/dags").statusCode());
            assertEquals(200, get(server, "/v1/inventory").statusCode());
        }
    }

    @Test
    void healthAnswersWhileEveryRequestThreadWaitsForTheBroker() throws Exception {
        int threads = RetrievalServer.REQUEST_THREADS;
        CountDownLatch entered = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        RetrievalServer.Dags waiting = new RetrievalServer.Dags() {
            @Override
            public JsonNode deploy(JsonNode dag, String text, String by) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return EpisodeJson.JSON.createObjectNode().put("dag", dag.path("dag").asText()).put("state", "deployed");
            }

            @Override
            public JsonNode stop(String name, String by) {
                throw new AssertionError("no stop expected");
            }

            @Override
            public JsonNode list() {
                return EpisodeJson.JSON.createObjectNode().putArray("dags");
            }
        };
        try (RetrievalServer server = server(waiting, true, null)) {
            List<CompletableFuture<HttpResponse<String>>> held = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    held.add(HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/dags/deploy"))
                            .POST(HttpRequest.BodyPublishers.ofString("dag: d" + i + "\n")).build(), HttpResponse.BodyHandlers.ofString()));
                }
                assertTrue(entered.await(30, TimeUnit.SECONDS), "every request thread holds a deployment");
                for (String path : List.of("/v1/health", "/v1/ready")) {
                    HttpResponse<String> probe = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, probe.statusCode(), path + ": " + probe.body());
                }
            } finally {
                release.countDown();
            }
            for (CompletableFuture<HttpResponse<String>> f : held) {
                assertEquals(200, f.get(30, TimeUnit.SECONDS).statusCode());
            }
        }
    }

    @Test
    void aServerWithoutAFeedIsReadyOnceItListens() throws Exception {
        try (RetrievalServer server = server(null, false, null)) {
            HttpResponse<String> ready = get(server, "/v1/ready");
            assertEquals(200, ready.statusCode(), ready.body());
            assertEquals("{\"status\":\"ready\"}", ready.body());
        }
    }

    /** A feed whose refreshes the test decides. */
    static final class Refreshes implements RetrievalServer.Feed {
        Instant last;
        int failures;

        Refreshes(Instant loaded) {
            last = loaded;
        }

        void failed() {
            failures++;
        }

        void worked(Instant at) {
            last = at;
            failures = 0;
        }

        @Override
        public Instant lastRefresh() {
            return last;
        }

        @Override
        public int refreshFailures() {
            return failures;
        }

        @Override
        public Duration refreshInterval() {
            return Duration.ofSeconds(30);
        }

        @Override
        public boolean truncated() {
            return true;
        }
    }

    @Test
    void aServerIsNotReadyAfterThreeFailedRefreshesAndReadyAgainAfterOneThatWorked() throws Exception {
        Instant t0 = Instant.parse("2026-11-20T10:00:00Z");
        Refreshes feed = new Refreshes(t0);
        try (RetrievalServer server = server(null, false, null)) {
            server.follows(feed, Clock.fixed(t0.plusSeconds(30), ZoneOffset.UTC));
            HttpResponse<String> ready = get(server, "/v1/ready");
            assertEquals(200, ready.statusCode(), ready.body());
            assertEquals("{\"status\":\"ready\",\"feed\":{\"lastRefresh\":\"2026-11-20T10:00:00Z\",\"ageSeconds\":30,"
                    + "\"refreshFailures\":0,\"truncated\":true}}", ready.body());

            feed.failed();
            feed.failed();
            assertEquals(200, get(server, "/v1/ready").statusCode(), "two failed refreshes");
            feed.failed();
            HttpResponse<String> down = get(server, "/v1/ready");
            assertEquals(503, down.statusCode());
            assertEquals("{\"error\":\"feed: 3 refreshes failed in a row, the last one that worked ended at 2026-11-20T10:00:00Z;"
                    + " check that the broker is reachable and read the log of the server\",\"type\":\"unavailable\"}", down.body());
            assertEquals(200, get(server, "/v1/health").statusCode(), "liveness does not follow the feed");

            feed.worked(t0.plusSeconds(30));
            assertEquals(200, get(server, "/v1/ready").statusCode(), "one refresh that worked");
        }
    }

    @Test
    void aServerIsNotReadyWhenNoRefreshWorkedForThreePeriods() throws Exception {
        Instant t0 = Instant.parse("2026-11-20T10:00:00Z");
        Refreshes feed = new Refreshes(t0);
        try (RetrievalServer server = server(null, false, null)) {
            server.follows(feed, Clock.fixed(t0.plusSeconds(90), ZoneOffset.UTC));
            assertEquals(200, get(server, "/v1/ready").statusCode(), "three periods of 30 s");
            server.follows(feed, Clock.fixed(t0.plusSeconds(91), ZoneOffset.UTC));
            HttpResponse<String> late = get(server, "/v1/ready");
            assertEquals(503, late.statusCode());
            assertEquals("{\"error\":\"feed: no refresh worked for 91 s, more than 3 refresh periods of PT30S; check that the"
                    + " broker is reachable and read the log of the server\",\"type\":\"unavailable\"}", late.body());
        }
    }

    static final Node NODE = new Node(0, "127.0.0.1", 1);

    /** A feed over a broker in memory whose producers append to its log, as a broker does. */
    static KafScaleFeed feed(FakeBroker broker) {
        return new KafScaleFeed("ks:9092", "wind", broker.consumer(), b -> new MockAdminClient(List.of(NODE), NODE),
                b -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
                    @Override
                    public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> r, Callback callback) {
                        broker.append(r.topic(), 0, r.value());
                        return super.send(r, callback);
                    }
                }, new InMemorySeriesStore(), 100, () -> { });
    }

    @Test
    void aServerStartedOnKafScaleIsNotReadyAfterThreeFailedRefreshesOfItsFeed() throws Exception {
        FakeBroker broker = new FakeBroker();
        RetrievalServer.Plan plan = RetrievalServer.check(OpenTsxConf.load(List.of("retrieval-server", "--kafscale", "ks:9092",
                "--domain", "wind", "--port", "0", "--bind", "127.0.0.1", "--refresh", "PT1H"), Map.of(), null).conf());
        KafScaleFeed[] opened = {null};
        RetrievalServer.Feeds overTheBrokerInMemory = (bootstrap, domain, refresh, store, maxEpisodes, onChange) ->
                opened[0] = KafScaleFeed.start(new KafScaleFeed(bootstrap, domain, broker.consumer(),
                        b -> new MockAdminClient(List.of(NODE), NODE),
                        b -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), store, maxEpisodes,
                        onChange), refresh);
        try (RetrievalServer server = RetrievalServer.start(plan, org.slf4j.LoggerFactory.getLogger(RetrievalServer.class),
                overTheBrokerInMemory)) {
            HttpResponse<String> ready = get(server, "/v1/ready");
            assertEquals(200, ready.statusCode(), ready.body());
            assertTrue(ready.body().contains("\"refreshFailures\":0"), ready.body());

            broker.unreadable.put(VectorProcessorTest.LAYOUT.episodes(),
                    new org.apache.kafka.common.errors.TimeoutException("the broker does not answer"));
            for (int i = 0; i < 3; i++) {
                opened[0].refresh();
            }
            HttpResponse<String> down = get(server, "/v1/ready");
            assertEquals(503, down.statusCode(), down.body());
            assertEquals("{\"error\":\"feed: 3 refreshes failed in a row, the last one that worked ended at " + opened[0].lastRefresh()
                    + "; check that the broker is reachable and read the log of the server\",\"type\":\"unavailable\"}", down.body());
        }
    }

    /** The DAG routes of a server on KafScale, as {@link RetrievalServer#main} wires them. */
    static RetrievalServer.Dags kafscale(KafScaleFeed feed, FakeBroker broker) {
        return RetrievalServer.kafscaleDags(feed, broker::consumer, VectorProcessorTest.LAYOUT);
    }

    @Test
    void aSecondDagThatWritesTheVectorTopicOfAnotherAnswers409AndWritesNothing() throws Exception {
        FakeBroker broker = new FakeBroker();
        TopicPartition control = new TopicPartition(VectorProcessorTest.LAYOUT.dags(), 0);
        String first = VectorProcessorTest.goodDag();
        String copy = first.replace("dag: wind-exp2-smart", "dag: wind-copy");
        try (KafScaleFeed feed = feed(broker); RetrievalServer server = server(kafscale(feed, broker), true, null)) {
            assertEquals(200, post(server, "/v1/dags/deploy", first).statusCode());
            HttpResponse<String> refused = post(server, "/v1/dags/deploy", copy);
            assertEquals(409, refused.statusCode(), refused.body());
            assertEquals("{\"error\":\"wind-copy: sink topic tsx.wind.smart.turbine-state.vectors.v1 is already written by DAG"
                    + " wind-exp2-smart; stop that DAG first or choose another sink\",\"type\":\"conflict\"}", refused.body());
            assertEquals(1, broker.log.get(control).size(), "nothing reaches the control topic");

            HttpResponse<String> again = post(server, "/v1/dags/deploy", first.replace("every: PT60S", "every: PT30S"));
            assertEquals(200, again.statusCode(), "a DAG deployed again under its name: " + again.body());
            assertEquals(200, post(server, "/v1/dags/stop?name=wind-exp2-smart", "").statusCode());
            assertEquals(200, post(server, "/v1/dags/deploy", copy).statusCode(), "the topic has no writer after the stop");
            assertEquals(4, broker.log.get(control).size());
        }
    }

    @Test
    void aSecondDagThatWritesTheSignalOfASeriesSinkOfAnotherAnswers409AndWritesNothing() throws Exception {
        FakeBroker broker = new FakeBroker();
        TopicPartition control = new TopicPartition(VectorProcessorTest.LAYOUT.dags(), 0);
        String first = VectorProcessorTest.cleanDag("dq.checks", "");
        String copy = first.replace("dag: wind-flow-7", "dag: wind-flow-8");
        try (KafScaleFeed feed = feed(broker); RetrievalServer server = server(kafscale(feed, broker), true, null)) {
            assertEquals(200, post(server, "/v1/dags/deploy", first).statusCode());
            HttpResponse<String> refused = post(server, "/v1/dags/deploy", copy);
            assertEquals(409, refused.statusCode(), refused.body());
            assertEquals("{\"error\":\"wind-flow-8: sink tsx.wind.clean.v2 signal park/T01/WTUR.W of system h2-chain is already"
                    + " written by DAG wind-flow-7; stop that DAG first or choose another signal\",\"type\":\"conflict\"}", refused.body());
            assertEquals(1, broker.log.get(control).size(), "nothing reaches the control topic");

            assertEquals(200, post(server, "/v1/dags/deploy", copy.replace("id: park/T01/WTUR.W", "id: park/T02/WTUR.W")).statusCode(),
                    "another signal on the shared clean topic");
            HttpResponse<String> again = post(server, "/v1/dags/deploy", first.replace("every: PT1M", "every: PT5M"));
            assertEquals(200, again.statusCode(), "a DAG deployed again under its name: " + again.body());
            assertEquals(3, broker.log.get(control).size());
        }
    }
}
