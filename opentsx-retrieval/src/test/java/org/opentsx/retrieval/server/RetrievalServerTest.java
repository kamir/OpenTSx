package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.system.Systems;
import org.opentsx.store.InMemorySeriesStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertRefused(400, "topK: \"ten\" is not a whole number; expected a whole number from 1 to 1000 without quotes",
                send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\",\"topK\":\"ten\"}"));
        assertRefused(400, "pattern[1]: \"a\" is not a number; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/patterns/search", "{\"pattern\":[1,\"a\",3,4,5],\"metric\":\"demo.signal\"}"));
        assertRefused(400, "pattern[1]: missing; expected a number without quotes, such as 0.01 or 1e-3",
                send("/v1/patterns/search", "{\"pattern\":[1,null,3,4,5],\"metric\":\"demo.signal\"}"));
        assertRefused(400, "topK: \"10\" is not a whole number; expected a whole number from 1 to 1000 without quotes",
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
    void anUnknownQueryParameterAndAggWithoutResampleAnswer400() throws Exception {
        assertError(400, "bad_request", "metrc: unknown query parameter; expected one of metric, tag.<key>, series, from, to,"
                + " limit, clip, resample, agg", get("/v1/episodes?metrc=demo.signal&limit=1", null));
        assertError(400, "bad_request", "Metric: unknown query parameter; expected one of metric, tag.<key>, series, from,"
                + " to, limit, clip, resample, agg", get("/v1/episodes?Metric=demo.signal&limit=1", null));
        assertError(400, "bad_request", "metrc: unknown query parameter; expected one of metric, tag.<key>, series, from,"
                + " to, limit, clip, resample, agg", get("/v1/episodes?limit=1&metrc", null));
        assertError(400, "bad_request", "agg: \"MAX\" needs resample; expected resample with agg, such as resample=PT1M&agg=MAX",
                get("/v1/episodes?metric=demo.signal&limit=1&agg=MAX", null));
        assertEquals(200, get("/v1/episodes?metric=demo.signal&limit=1&tag.n=1&resample=PT1M&agg=MAX", null).statusCode());
    }

    @Test
    void theRunRoutesAnswer400ForAQueryParameterTheyDoNotTake() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        InMemorySeriesStore store = new InMemorySeriesStore();
        SeriesInventory inventory = new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1));
        try (RetrievalServer runs = new RetrievalServer(new EpisodeService(store), 0, inventory, null, false, null,
                Systems.load(root.resolve("systems"), root))) {
            String base = "http://127.0.0.1:" + runs.port() + "/v1/systems/h2-chain/runs";
            HttpResponse<byte[]> made = http.send(HttpRequest.newBuilder(URI.create(base)).POST(HttpRequest.BodyPublishers.ofString(
                    "{\"interventions\":[{\"at\":\"02:00\",\"atom\":\"handover\",\"control\":\"valve\",\"value\":1}]}")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, made.statusCode(), new String(made.body(), StandardCharsets.UTF_8));
            String run = base + "/" + EpisodeJson.JSON.readTree(made.body()).path("run").asText();
            assertError(400, "bad_request", "kyes: unknown query parameter; expected one of keys",
                    http.send(HttpRequest.newBuilder(URI.create(run + "/series?keys=buffer.p_bar&kyes=x")).build(),
                            HttpResponse.BodyHandlers.ofByteArray()));
            assertError(400, "bad_request", "bsae: unknown query parameter; expected one of base",
                    http.send(HttpRequest.newBuilder(URI.create(run + "/compare?bsae=x")).build(), HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(run + "/series?keys=buffer.p_bar")).build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        }
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

    static HttpResponse<byte[]> call(RetrievalServer server, String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static void assertError(int status, String type, String error, HttpResponse<byte[]> r) throws Exception {
        assertEquals(status, r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElse(null));
        JsonNode body = EpisodeJson.JSON.readTree(r.body());
        if (error != null) {
            assertEquals(error, body.path("error").asText());
        }
        assertEquals(type, body.path("type").asText());
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("error", "type"), fields);
    }

    /** A server whose DAG list fails with a programming error, and whose DAG writes need a run token. */
    static RetrievalServer faulty() throws Exception {
        InMemorySeriesStore store = new InMemorySeriesStore();
        RetrievalServer.Dags dags = new RetrievalServer.Dags() {
            @Override
            public JsonNode deploy(JsonNode dag, String text, String by) {
                throw new AssertionError("not reached");
            }

            @Override
            public JsonNode stop(String name, String by) {
                throw new AssertionError("not reached");
            }

            @Override
            public JsonNode list() {
                throw new NullPointerException("Cannot invoke \"com.fasterxml.jackson.databind.JsonNode.asText()\"");
            }
        };
        return new RetrievalServer(new EpisodeService(store), 0,
                new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1)), dags, true, "secret");
    }

    @Test
    void aBodyThatIsNotJsonAnswers400() throws Exception {
        assertError(400, "bad_request", "the body is not valid JSON: Unexpected end-of-input: expected close marker for Array"
                        + " (start marker at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 12])",
                send("/v1/patterns/search", "{\"pattern\":[1,2"));
        assertError(400, "bad_request", "the body is not a JSON object; expected {...} with the fields of the route",
                send("/v1/segments", "[1,2]"));
    }

    @Test
    void aServerFaultAnswersAFixedTextAndLogsOneLine() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        HttpResponse<byte[]> r;
        try (RetrievalServer s = faulty()) {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            try {
                r = call(s, "GET", "/v1/dags", null);
            } finally {
                System.setErr(stderr);
            }
        }
        assertError(500, "internal", "internal error", r);
        List<String> lines = err.toString(StandardCharsets.UTF_8).lines().toList();
        List<String> errors = lines.stream().filter(l -> l.contains(" ERROR ")).toList();
        assertEquals(1, errors.size(), String.join("\n", lines));
        assertTrue(errors.get(0).endsWith("request failed method=GET path=/v1/dags status=500 cause=java.lang.NullPointerException:"
                + " Cannot invoke \"com.fasterxml.jackson.databind.JsonNode.asText()\""), errors.get(0));
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("\tat ")), "the stack trace is logged at DEBUG only");
        assertError(400, "bad_request", "pattern: \"{\"x\":1}\" is not an array; expected an array of 4 to 100000 numbers",
                send("/v1/patterns/search", "{\"pattern\":{\"x\":1}}"));
    }

    @Test
    void everyErrorBodyHasAType() throws Exception {
        String pad = "{\"pad\":\"" + "a".repeat(300 * 1024) + "\"}";
        try (RetrievalServer s = faulty()) {
            PrintStream stderr = System.err;
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            try {
                for (Object[] c : new Object[][]{
                        {"GET", "/v1/episodes?limit=abc", null, 400, "bad_request"},
                        {"POST", "/v1/dags/deploy", "dag: x", 401, "unauthorized"},
                        {"GET", "/v1/episodes/nope", null, 404, "not_found"},
                        {"DELETE", "/v1/episodes", null, 405, "method_not_allowed"},
                        {"POST", "/v1/segments", pad, 413, "too_large"},
                        {"GET", "/v1/dags", null, 500, "internal"}}) {
                    assertError((int) c[3], (String) c[4], null, call(s, (String) c[0], (String) c[1], (String) c[2]));
                }
            } finally {
                System.setErr(stderr);
            }
        }
    }

    @Test
    void aWrongMethodAnswers405WithAllowAndAnUnknownPathAJson404() throws Exception {
        HttpResponse<byte[]> r = call(server, "DELETE", "/v1/episodes", null);
        assertError(405, "method_not_allowed", "DELETE /v1/episodes: method not allowed; expected GET", r);
        assertEquals("GET", r.headers().firstValue("Allow").orElse(null));
        for (Map.Entry<String, String> e : Map.of("/v1/patterns/search", "POST", "/v1/health", "GET",
                "/v1/episodes/similar", "GET, POST", "/v1/systems/x/generate", "POST", "/v1/systems/x/plan", "GET, POST").entrySet()) {
            HttpResponse<byte[]> put = call(server, "PUT", e.getKey(), "{}");
            assertError(405, "method_not_allowed", "PUT " + e.getKey() + ": method not allowed; expected " + e.getValue(), put);
            assertEquals(e.getValue(), put.headers().firstValue("Allow").orElse(null));
        }
        assertError(405, "method_not_allowed", null, call(server, "GET", "/v1/segments", null));
        assertError(404, "not_found", "no such endpoint: /x", call(server, "GET", "/x", null));
        assertError(404, "not_found", "no such endpoint: /v1/nothing", call(server, "GET", "/v1/nothing", null));

        java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        InMemorySeriesStore store = new InMemorySeriesStore();
        try (RetrievalServer withSystems = new RetrievalServer(new EpisodeService(store), 0,
                new SeriesInventory(store, SeriesInventory.Source.of("demo"), null, null, Duration.ofMinutes(1)), null, false, null,
                org.opentsx.retrieval.system.Systems.load(root.resolve("systems"), root))) {
            for (String[] c : new String[][]{
                    {"GET", "/v1/systems/h2-chain/generate", null, "POST"},
                    {"POST", "/v1/systems/h2-chain/scenario", "{}", "GET"},
                    {"POST", "/v1/systems/h2-chain/runs/r", "{}", "GET"},
                    {"POST", "/v1/dags", "{}", "GET"}}) {
                HttpResponse<byte[]> wrong = call(withSystems, c[0], c[1], c[2]);
                assertError(405, "method_not_allowed", c[0] + " " + c[1] + ": method not allowed; expected " + c[3], wrong);
                assertEquals(c[3], wrong.headers().firstValue("Allow").orElse(null));
            }
        }
    }

    @Test
    void anUnknownEpisodeAnswers404OnEveryRoute() throws Exception {
        assertError(404, "not_found", "unknown episode nope", send("/v1/episodes/similar", "{\"episodeId\":\"nope\"}"));
        assertError(404, "not_found", "unknown episode nope", get("/v1/episodes/nope", null));
        assertError(404, "not_found", "unknown episode nope",
                send("/v1/patterns/search", "{\"episodeId\":\"nope\",\"offset\":0,\"length\":10}"));
        assertError(400, "bad_request", "episode id: \"a/b\" holds a slash; expected the id of one episode",
                get("/v1/episodes/a%2Fb", null));
        assertError(400, "bad_request", "episodeId: \"a/b\" holds a slash; expected the id of one episode",
                send("/v1/episodes/similar", "{\"episodeId\":\"a/b\"}"));
        assertError(400, "bad_request", "episodeId: missing; expected the id of one episode",
                send("/v1/episodes/similar", "{}"));
    }

    @Test
    void aSegmentParameterOutOfRangeAnswers400NamingTheFieldAndTheRange() throws Exception {
        assertError(400, "bad_request", "params.duration: PT0S is out of range; expected an ISO-8601 duration of at least PT0.000001S,"
                        + " such as PT10M",
                send("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{\"duration\":\"PT0S\"},\"metric\":\"demo.signal\"}"));
        // below the microsecond of the time axis, which would make every window zero long
        assertError(400, "bad_request", "params.duration: PT0.0000001S is out of range; expected an ISO-8601 duration of at least"
                        + " PT0.000001S, such as PT10M",
                send("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{\"duration\":\"PT0.0000001S\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.duration: PT23999999976H is out of range; expected an ISO-8601 duration of at most"
                        + " PT2562047788H54.775807S, such as PT1M",
                send("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{\"duration\":\"P999999999D\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.post: PT23999999976H is out of range; expected an ISO-8601 duration of at most"
                        + " PT2562047788H54.775807S, such as PT1M",
                send("/v1/segments", "{\"strategy\":\"FEATURE_EXTREMUM\",\"params\":{\"prominence\":1,\"window\":\"PT1M\","
                        + "\"distance\":\"PT1M\",\"post\":\"P999999999D\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.n: 0 is out of range; expected a whole number from 1 to 2147483647",
                send("/v1/segments", "{\"strategy\":\"FIXED_COUNT\",\"params\":{\"n\":0},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.overlap: 10 is out of range; expected a whole number from 0 to 9, below params.n",
                send("/v1/segments", "{\"strategy\":\"FIXED_COUNT\",\"params\":{\"n\":10,\"overlap\":10},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.prominence: -1.0 is below zero; expected a number of 0 or more",
                send("/v1/segments", "{\"strategy\":\"FEATURE_EXTREMUM\",\"params\":{\"prominence\":-1,\"window\":\"PT1M\","
                        + "\"distance\":\"PT1M\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.pre: PT-1S is negative; expected an ISO-8601 duration of zero or more, such as PT1M",
                send("/v1/segments", "{\"strategy\":\"FEATURE_EXTREMUM\",\"params\":{\"prominence\":1,\"window\":\"PT1M\","
                        + "\"distance\":\"PT1M\",\"pre\":\"PT-1S\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.exit: 25.0 is above params.enter 20.0; expected a number of at most 20.0 for direction ABOVE",
                send("/v1/segments", "{\"strategy\":\"FEATURE_THRESHOLD\",\"params\":{\"enter\":20,\"exit\":25},\"metric\":\"demo.signal\"}"));
    }

    @Test
    void aPatternOutsideItsEpisodeAnswers400NamingTheFieldAndTheRange() throws Exception {
        JsonNode episode = EpisodeJson.JSON.readTree(get("/v1/episodes?metric=demo.signal&limit=1", null).body()).get(0);
        String id = episode.path("episodeId").asText();
        int n = episode.path("values").size();
        assertError(400, "bad_request", "offset: " + (n - 5) + " is out of range; expected a whole number from 0 to " + (n - 10)
                        + ", so that 10 values fit into the " + n + " of episode " + id,
                send("/v1/patterns/search", "{\"episodeId\":\"" + id + "\",\"offset\":" + (n - 5) + ",\"length\":10}"));
        assertError(400, "bad_request", "offset: -1 is out of range; expected a whole number from 0 to " + (n - 10)
                        + ", so that 10 values fit into the " + n + " of episode " + id,
                send("/v1/patterns/search", "{\"episodeId\":\"" + id + "\",\"offset\":-1,\"length\":10}"));
        assertError(400, "bad_request", "length: 3 is out of range; expected a whole number from 4 to " + n + ", the values of episode " + id,
                send("/v1/patterns/search", "{\"episodeId\":\"" + id + "\",\"offset\":0,\"length\":3}"));
        assertError(400, "bad_request", "length: " + (n + 1) + " is out of range; expected a whole number from 4 to " + n
                        + ", the values of episode " + id,
                send("/v1/patterns/search", "{\"episodeId\":\"" + id + "\",\"offset\":0,\"length\":" + (n + 1) + "}"));
        assertError(400, "bad_request", "offset: 2147483647 is out of range; expected a whole number from 0 to " + (n - 10)
                        + ", so that 10 values fit into the " + n + " of episode " + id,
                send("/v1/patterns/search", "{\"episodeId\":\"" + id + "\",\"offset\":2147483647,\"length\":10}"));
    }

    @Test
    void requestsOverALimitAnswer400NamingTheLimit() throws Exception {
        assertError(400, "bad_request", "topK: 1000000 is out of range; expected a whole number from 1 to 1000",
                send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\",\"topK\":1000000}"));
        assertError(400, "bad_request", "topK: 0 is out of range; expected a whole number from 1 to 1000",
                send("/v1/episodes/similar", "{\"episodeId\":\"" + firstEpisodeId() + "\",\"topK\":0}"));
        String[] values = new String[100_001];
        Arrays.fill(values, "0");
        assertError(400, "bad_request", "pattern: 100001 values; expected 4 to 100000 values",
                send("/v1/patterns/search", "{\"pattern\":[" + String.join(",", values) + "],\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "limit: 20001 is out of range; expected a whole number from 1 to 20000",
                get("/v1/episodes?metric=demo.signal&limit=20001", null));
    }

    static String firstEpisodeId() throws Exception {
        return EpisodeJson.JSON.readTree(get("/v1/episodes?metric=demo.signal&limit=1", null).body()).get(0).path("episodeId").asText();
    }

    @Test
    void aParameterErrorNamesTheParameterAndWhatItTakes() throws Exception {
        assertError(400, "bad_request", "limit: \"abc\" is not an int; expected a whole number from 1 to 20000",
                get("/v1/episodes?limit=abc", null));
        assertError(400, "bad_request", "agg: \"mean\" is not allowed; expected one of MEAN, MIN, MAX, FIRST, LAST, SUM, COUNT",
                get("/v1/episodes?metric=demo.signal&limit=1&resample=PT1M&agg=mean", null));
        assertError(400, "bad_request", "resample: \"1m\" is not a duration; expected an ISO-8601 duration such as PT1M",
                get("/v1/episodes?metric=demo.signal&limit=1&resample=1m", null));
        assertError(400, "bad_request", "resample: \"PT0S\" is shorter than one microsecond; expected a positive duration such as PT1M",
                get("/v1/episodes?metric=demo.signal&limit=1&resample=PT0S", null));
        assertError(400, "bad_request", "from: \"yesterday\" is not an instant; expected an ISO-8601 instant such as 2026-01-01T00:00:00Z",
                get("/v1/episodes?from=yesterday", null));
        assertError(400, "bad_request", "from: \"2026-01-02T00:00:00Z\" is not before to \"2026-01-01T00:00:00Z\"; expected from"
                + " earlier than to", get("/v1/episodes?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z", null));
        assertError(400, "bad_request", "to: \"1960-01-01T00:00:00Z\" is not after from, which defaults to 1970-01-01T00:00:00Z;"
                + " expected a later to or an earlier from", get("/v1/episodes?to=1960-01-01T00:00:00Z", null));
        assertError(400, "bad_request", "from: \"9999-06-01T00:00:00Z\" is not before to, which defaults to 9999-01-01T00:00:00Z;"
                + " expected an earlier from or a later to", get("/v1/episodes?from=9999-06-01T00:00:00Z", null));
        assertError(400, "bad_request", "pattern: missing; expected an array of 4 to 100000 numbers, or episodeId with offset and length",
                send("/v1/patterns/search", "{}"));
        assertError(400, "bad_request", "clip: \"yes\" is not allowed; expected one of true, false",
                get("/v1/episodes?metric=demo.signal&clip=yes", null));
        assertError(400, "bad_request", "params.feature: \"max\" is not allowed; expected one of MAX, MIN",
                send("/v1/segments", "{\"strategy\":\"FEATURE_EXTREMUM\",\"params\":{\"feature\":\"max\",\"prominence\":1,"
                        + "\"window\":\"PT1M\",\"distance\":\"PT1M\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.direction: \"up\" is not allowed; expected one of ABOVE, BELOW",
                send("/v1/segments", "{\"strategy\":\"FEATURE_THRESHOLD\",\"params\":{\"direction\":\"up\",\"enter\":1},"
                        + "\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.duration: \"1h\" is not a duration; expected an ISO-8601 duration such as PT1M",
                send("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{\"duration\":\"1h\"},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "params.duration: missing; expected an ISO-8601 duration such as PT1M",
                send("/v1/segments", "{\"strategy\":\"FIXED_DURATION\",\"params\":{},\"metric\":\"demo.signal\"}"));
        assertError(400, "bad_request", "strategy: \"FIXED\" is not allowed; expected one of FIXED_DURATION, FIXED_COUNT,"
                        + " FEATURE_EXTREMUM, FEATURE_THRESHOLD",
                send("/v1/segments", "{\"strategy\":\"FIXED\"}"));
        assertError(400, "bad_request", "distance: \"euclid\" is not allowed; expected one of zED, DTW",
                send("/v1/patterns/search", "{\"pattern\":[1,2,3,4,5],\"metric\":\"demo.signal\",\"distance\":\"euclid\"}"));
    }
}
