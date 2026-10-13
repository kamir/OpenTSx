package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.store.InMemorySeriesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagRunEndpointTest {

    static HttpResponse<String> post(RetrievalServer server, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/dags/run"))
                .header("Content-Type", "application/yaml").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static RetrievalServer server(RetrievalServer.DagRunner runner) throws Exception {
        InMemorySeriesStore store = new InMemorySeriesStore();
        return new RetrievalServer(new EpisodeService(store), 0,
                new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1)), runner);
    }

    @Test
    void aServerWithoutRunnerSaysSo() throws Exception {
        try (RetrievalServer server = server(null)) {
            HttpResponse<String> r = post(server, "dag: x\n");
            assertEquals(404, r.statusCode());
            assertTrue(r.body().contains("--dag-runs true"), r.body());
        }
    }

    @Test
    void handsTheParsedDagToTheRunnerAndReportsItsRefusal() throws Exception {
        RetrievalServer.DagRunner runner = (dag, text) -> {
            if (dag.path("dag").asText().equals("bad")) {
                throw new IllegalArgumentException("node n1 (norm.zscore) is not available in this runtime");
            }
            return EpisodeJson.JSON.createObjectNode().put("dag", dag.path("dag").asText()).put("bytes", text.length());
        };
        try (RetrievalServer server = server(runner)) {
            HttpResponse<String> ok = post(server, "dag: wind-exp2-smart\nnodes: []\n");
            assertEquals(200, ok.statusCode());
            JsonNode n = EpisodeJson.JSON.readTree(ok.body());
            assertEquals("wind-exp2-smart", n.path("dag").asText());
            assertEquals(31, n.path("bytes").asInt());

            HttpResponse<String> refused = post(server, "dag: bad\n");
            assertEquals(400, refused.statusCode());
            assertTrue(refused.body().contains("norm.zscore"), refused.body());

            HttpResponse<String> broken = post(server, "dag: [unclosed\n");
            assertEquals(400, broken.statusCode());
            assertTrue(broken.body().contains("no valid YAML"), broken.body());

            HttpResponse<String> large = post(server, "x: " + "a".repeat(300 * 1024) + "\n");
            assertEquals(400, large.statusCode());
            assertTrue(large.body().contains("larger than"), large.body());
        }
    }
}
