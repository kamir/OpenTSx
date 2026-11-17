package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.EpisodeService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalServerTest {

    static RetrievalServer server;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        server = new RetrievalServer(new EpisodeService(RetrievalServer.demoStore()), 0);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    static HttpResponse<byte[]> get(String path, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (accept != null) {
            b.header("Accept", accept);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode post(String path, String json) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode(), new String(r.body()));
        return EpisodeJson.JSON.readTree(r.body());
    }

    @Test
    void episodesAsJsonAndAvroStream() throws Exception {
        java.time.Instant t0 = java.time.Instant.ofEpochSecond(1_790_000_000L);
        HttpResponse<byte[]> r = get("/v1/episodes?metric=demo.signal&tag.n=1&from=" + t0.plusSeconds(600) + "&to="
                + t0.plusSeconds(4200), null);
        assertEquals(200, r.statusCode());
        JsonNode eps = EpisodeJson.JSON.readTree(r.body());
        assertEquals(6, eps.size());
        assertEquals(600, eps.get(0).path("values").size());

        HttpResponse<byte[]> a = get("/v1/episodes?metric=demo.signal&limit=4", RetrievalServer.AVRO_STREAM);
        ByteBuffer buf = ByteBuffer.wrap(a.body());
        List<Episode> decoded = new ArrayList<>();
        OpenTsxAvro avro = new OpenTsxAvro();
        while (buf.hasRemaining()) {
            byte[] b = new byte[buf.getInt()];
            buf.get(b);
            decoded.add(avro.decode(b, Episode.class));
        }
        assertEquals(4, decoded.size());
        HttpResponse<byte[]> one = get("/v1/episodes/" + decoded.get(0).getEpisodeId(), null);
        assertEquals(decoded.get(0).getEpisodeId(), EpisodeJson.JSON.readTree(one.body()).get(0).path("episodeId").asText());
        assertEquals(404, get("/v1/episodes/nope", null).statusCode());
        assertEquals(400, get("/v1/episodes?from=yesterday", null).statusCode());

        JsonNode resampled = EpisodeJson.JSON.readTree(get("/v1/episodes?metric=demo.signal&limit=1&resample=PT1M&agg=MAX", null).body());
        assertEquals(11, resampled.get(0).path("values").size(), "episode starts at :20 s, buckets are minute-aligned");
    }

    static HttpResponse<byte[]> send(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    static void assertRefused(int status, String error, HttpResponse<byte[]> r) throws Exception {
        assertEquals(status, r.statusCode(), new String(r.body()));
        assertEquals(error, EpisodeJson.JSON.readTree(r.body()).path("error").asText());
    }

    @Test
    void aBodyFieldThatIsNoNumberAnswers400NamingTheField() throws Exception {
        assertRefused(400, "topK: \"ten\" is not a whole number; expected a whole number from -2147483648 to 2147483647 without quotes",
                send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\",\"topK\":\"ten\"}"));
        assertRefused(400, "pattern[1]: \"a\" is not a number; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/patterns/search", "{\"pattern\":[1,\"a\",3,4,5],\"metric\":\"demo.signal\"}"));
        assertRefused(400, "pattern[1]: missing; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/patterns/search", "{\"pattern\":[1,null,3,4,5],\"metric\":\"demo.signal\"}"));
        assertRefused(400, "topK: \"10\" is not a whole number; expected a whole number from -2147483648 to 2147483647 without quotes",
                send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\",\"topK\":\"10\"}"));
        assertRefused(400, "band: \"wide\" is not a number; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\",\"distance\":\"DTW\",\"band\":\"wide\"}"));
        assertRefused(400, "params.prominence: missing; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/segments", "{\"strategy\":\"FEATURE_EXTREMUM\",\"params\":{\"window\":\"PT1M\",\"distance\":\"PT1M\"},\"metric\":\"demo.signal\"}"));
        assertRefused(400, "params.enter: missing; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/segments", "{\"strategy\":\"FEATURE_THRESHOLD\",\"params\":{},\"metric\":\"demo.signal\"}"));
        assertRefused(400, "params.overlap: \"1.5\" is not a whole number; expected a whole number from -2147483648 to 2147483647 without quotes",
                send("/v1/segments", "{\"strategy\":\"FIXED_COUNT\",\"params\":{\"n\":10,\"overlap\":1.5},\"metric\":\"demo.signal\"}"));
    }

    @Test
    void aResampleStepThatGivesTooManyPointsAnswers400() throws Exception {
        // the first demo episode is 600 s long
        assertRefused(400, "resample: \"PT0.005S\" gives 120000 points for one episode; expected a longer step that gives at most 100000",
                get("/v1/episodes?metric=demo.signal&limit=1&resample=PT0.005S", null));
        assertRefused(400, "resample: \"PT0.000001S\" gives 600000000 points for one episode; expected a longer step that gives at most 100000",
                get("/v1/episodes?metric=demo.signal&limit=1&resample=PT0.000001S", null));
        HttpResponse<byte[]> under = get("/v1/episodes?metric=demo.signal&limit=1&resample=PT0.01S", null);
        assertEquals(200, under.statusCode());
        assertEquals(60000, EpisodeJson.JSON.readTree(under.body()).get(0).path("count").asInt());
    }

    @Test
    void aBodyOverTheLimitAnswers413() throws Exception {
        String pad = ",\"pad\":\"" + "a".repeat(300 * 1024) + "\"}";
        String error = "request body: larger than 262144 bytes; send at most 262144 bytes";
        assertRefused(413, error, send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\"" + pad));
        assertRefused(413, error, send("/v1/episodes/similar", "{\"episodeId\":\"x\"" + pad));
        assertRefused(413, error, send("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{\"duration\":\"PT1H\"}" + pad));
    }

    @Test
    void patternSearchFindsThePlantedPattern() throws Exception {
        StringBuilder p = new StringBuilder("[");
        for (int i = 0; i < RetrievalServer.DEMO_PATTERN_LENGTH; i++) {
            double t = (double) i / (RetrievalServer.DEMO_PATTERN_LENGTH - 1);
            p.append(i == 0 ? "" : ",").append(Math.sin(3 * Math.PI * t) * Math.exp(-3 * t) + 0.5 * t);
        }
        p.append("]");
        JsonNode hits = post("/v1/patterns/search", "{\"pattern\":" + p + ",\"metric\":\"demo.signal\",\"topK\":3}");
        assertEquals(3, hits.size());
        JsonNode best = hits.get(0);
        assertEquals(RetrievalServer.DEMO_PATTERN_AT % 600, best.path("offset").asInt());
        assertTrue(best.path("distance").asDouble() < 1.0);
        JsonNode dtw = post("/v1/patterns/search", "{\"pattern\":" + p + ",\"metric\":\"demo.signal\",\"topK\":1,\"distance\":\"DTW\",\"band\":0.1}");
        assertEquals(best.path("episodeId").asText(), dtw.get(0).path("episodeId").asText());

        JsonNode similar = post("/v1/episodes/similar", "{\"episodeId\":\"" + best.path("episodeId").asText() + "\",\"topK\":2}");
        assertEquals(2, similar.size());
        JsonNode segs = post("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{\"duration\":\"PT1H\"},"
                + "\"metric\":\"demo.signal\",\"tags\":{\"n\":\"0\"}}");
        assertEquals(7, segs.size(), "data starts at :13:20, windows are aligned to full hours");
        assertEquals(3600, segs.get(1).path("count").asInt());
    }
}
