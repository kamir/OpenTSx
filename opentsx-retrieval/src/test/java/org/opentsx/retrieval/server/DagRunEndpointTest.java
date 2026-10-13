package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.store.InMemorySeriesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

            HttpResponse<String> broken = post(server, "/v1/dags/deploy", "dag: [unclosed\n");
            assertEquals(400, broken.statusCode());
            assertTrue(broken.body().contains("no valid YAML"), broken.body());
            HttpResponse<String> large = post(server, "/v1/dags/deploy", "x: " + "a".repeat(300 * 1024) + "\n");
            assertEquals(400, large.statusCode());
            assertTrue(large.body().contains("larger than"), large.body());
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
}
