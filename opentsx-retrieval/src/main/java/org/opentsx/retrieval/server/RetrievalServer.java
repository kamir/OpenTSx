package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.Resampling;
import org.opentsx.retrieval.pattern.Distance;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * Thin REST layer over {@link EpisodeService} (JDK HTTP server, no framework).
 *
 * <pre>
 * GET  /v1/episodes?metric=&amp;tag.&lt;k&gt;=&lt;v&gt;&amp;series=id,id&amp;from=ISO&amp;to=ISO&amp;limit=&amp;clip=true&amp;resample=PT1M&amp;agg=MEAN
 * GET  /v1/episodes/{id}
 * POST /v1/patterns/search   {"pattern":[..] | "episodeId","offset","length", "metric","tags",{..},"from","to",
 *                             "distance":"zED"|"DTW","band":0.1,"topK":10}
 * POST /v1/episodes/similar  {"episodeId", "metric","tags","from","to","distance","band","topK"}
 * POST /v1/segments          {"strategy":"FIXED_DURATION|FIXED_COUNT|FEATURE_EXTREMUM|FEATURE_THRESHOLD", "params":{..},
 *                             "metric","tags","from","to"}
 * GET  /v1/health
 * </pre>
 * Episode responses are JSON by default; with {@code Accept: application/x-opentsx-avro-stream} they are a stream of
 * {@code [uint32 big-endian length][Avro single-object encoded Episode]} - what the Python client uses.
 */
public final class RetrievalServer implements AutoCloseable {

    public static final String AVRO_STREAM = "application/x-opentsx-avro-stream";

    private final EpisodeService service;
    private final HttpServer http;
    private final OpenTsxAvro avro = new OpenTsxAvro();

    public RetrievalServer(EpisodeService service, int port) throws IOException {
        this.service = service;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.createContext("/v1/", this::handle);
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(0);
        ((java.util.concurrent.ExecutorService) http.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (method.equals("GET") && path.equals("/v1/health")) {
                json(ex, 200, EpisodeJson.JSON.createObjectNode().put("status", "ok"));
            } else if (method.equals("GET") && path.equals("/v1/episodes")) {
                episodes(ex, listEpisodes(query(ex)));
            } else if (method.equals("GET") && path.startsWith("/v1/episodes/")) {
                String id = path.substring("/v1/episodes/".length());
                var e = service.episode(id);
                if (e.isEmpty()) {
                    json(ex, 404, EpisodeJson.JSON.createObjectNode().put("error", "unknown episode " + id));
                } else {
                    episodes(ex, List.of(e.get()));
                }
            } else if (method.equals("POST") && path.equals("/v1/patterns/search")) {
                matches(ex, search(body(ex)));
            } else if (method.equals("POST") && path.equals("/v1/episodes/similar")) {
                matches(ex, similar(body(ex)));
            } else if (method.equals("POST") && path.equals("/v1/segments")) {
                episodes(ex, segments(body(ex)));
            } else {
                json(ex, 404, EpisodeJson.JSON.createObjectNode().put("error", "no such endpoint"));
            }
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            json(ex, 400, EpisodeJson.JSON.createObjectNode().put("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            json(ex, 500, EpisodeJson.JSON.createObjectNode().put("error", e.toString()));
        }
    }

    // ---- endpoints -------------------------------------------------------------------------------------------------

    private List<Episode> listEpisodes(Map<String, String> q) {
        EpisodeService.EpisodesQuery eq = select(service.episodes(), q.get("metric"), tags(q), q.get("series"),
                q.get("from"), q.get("to"));
        if ("true".equals(q.get("clip"))) {
            eq.clip();
        }
        if (q.containsKey("limit")) {
            eq.limit(Integer.parseInt(q.get("limit")));
        }
        if (q.containsKey("resample")) {
            eq.resample(Duration.parse(q.get("resample")), Resampling.Agg.valueOf(q.getOrDefault("agg", "MEAN")));
        }
        return eq.fetch();
    }

    private List<PatternMatch> search(JsonNode b) {
        EpisodeService.SubsequenceQuery sq;
        if (b.has("pattern")) {
            double[] p = new double[b.get("pattern").size()];
            for (int i = 0; i < p.length; i++) {
                p[i] = b.get("pattern").get(i).asDouble();
            }
            sq = service.similarTo(p);
        } else {
            sq = service.similarTo(b.path("episodeId").asText(), b.path("offset").asInt(), b.path("length").asInt());
        }
        select(sq, text(b, "metric"), tags(b), text(b, "series"), text(b, "from"), text(b, "to"));
        return sq.distance(distance(b)).topK(b.path("topK").asInt(10)).fetch();
    }

    private List<PatternMatch> similar(JsonNode b) {
        EpisodeService.SimilarEpisodesQuery q = service.similarEpisodes(b.path("episodeId").asText());
        select(q, text(b, "metric"), tags(b), text(b, "series"), text(b, "from"), text(b, "to"));
        return q.distance(distance(b)).topK(b.path("topK").asInt(10)).fetch();
    }

    private List<Episode> segments(JsonNode b) {
        JsonNode p = b.path("params");
        Segmenter s = switch (b.path("strategy").asText()) {
            case "FIXED_DURATION" -> Segmenter.fixedDuration(dur(p, "duration", null), dur(p, "offset", "PT0S"));
            case "FIXED_COUNT" -> Segmenter.fixedCount(p.path("n").asInt(), p.path("overlap").asInt(0));
            case "FEATURE_EXTREMUM" -> Segmenter.extremum(Segmenter.Feature.valueOf(p.path("feature").asText("MAX")),
                    p.path("prominence").asDouble(), dur(p, "window", null), dur(p, "distance", null),
                    dur(p, "pre", "PT0S"), dur(p, "post", "PT0S"));
            case "FEATURE_THRESHOLD" -> Segmenter.threshold(Segmenter.Direction.valueOf(p.path("direction").asText("ABOVE")),
                    p.path("enter").asDouble(), p.path("exit").asDouble(p.path("enter").asDouble()), dur(p, "pre", "PT0S"),
                    dur(p, "post", "PT0S"), dur(p, "minDuration", "PT0S"), dur(p, "maxDuration", "PT0S"));
            default -> throw new IllegalArgumentException("unknown strategy " + b.path("strategy").asText());
        };
        EpisodeService.SegmentQuery q = service.segments(s);
        select(q, text(b, "metric"), tags(b), text(b, "series"), text(b, "from"), text(b, "to"));
        return q.fetch();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static <T extends EpisodeService.Selection<T>> T select(T sel, String metric, Map<String, String> tags,
                                                                    String series, String from, String to) {
        if (metric != null) {
            sel.metric(metric);
        }
        tags.forEach(sel::tag);
        if (series != null && !series.isBlank()) {
            sel.series(series.split(","));
        }
        if (from != null || to != null) {
            sel.between(from == null ? Instant.parse("1970-01-01T00:00:00Z") : Instant.parse(from),
                    to == null ? Instant.parse("9999-01-01T00:00:00Z") : Instant.parse(to));
        }
        return sel;
    }

    private static Distance distance(JsonNode b) {
        return "DTW".equalsIgnoreCase(b.path("distance").asText("zED")) ? Distance.dtw(b.path("band").asDouble(0.1))
                : Distance.zNormalizedEuclidean();
    }

    private static Duration dur(JsonNode p, String name, String dflt) {
        String v = p.path(name).asText(dflt);
        if (v == null) {
            throw new IllegalArgumentException("parameter " + name + " is required");
        }
        return Duration.parse(v);
    }

    private static String text(JsonNode b, String f) {
        return b.hasNonNull(f) ? b.get(f).asText() : null;
    }

    private static Map<String, String> tags(Map<String, String> q) {
        Map<String, String> t = new LinkedHashMap<>();
        q.forEach((k, v) -> {
            if (k.startsWith("tag.")) {
                t.put(k.substring(4), v);
            }
        });
        return t;
    }

    private static Map<String, String> tags(JsonNode b) {
        Map<String, String> t = new LinkedHashMap<>();
        b.path("tags").fields().forEachRemaining(e -> t.put(e.getKey(), e.getValue().asText()));
        return t;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return q;
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        return EpisodeJson.JSON.readTree(ex.getRequestBody());
    }

    private void episodes(HttpExchange ex, List<Episode> eps) throws IOException {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept != null && accept.contains(AVRO_STREAM)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Episode e : eps) {
                byte[] b = avro.encode(e);
                out.write(ByteBuffer.allocate(4).putInt(b.length).array());
                out.write(b);
            }
            send(ex, 200, AVRO_STREAM, out.toByteArray());
        } else {
            ArrayNode a = EpisodeJson.JSON.createArrayNode();
            eps.forEach(e -> a.add(EpisodeJson.episode(e)));
            json(ex, 200, a);
        }
    }

    private void matches(HttpExchange ex, List<PatternMatch> ms) throws IOException {
        ArrayNode a = EpisodeJson.JSON.createArrayNode();
        ms.forEach(m -> a.add(EpisodeJson.match(m)));
        json(ex, 200, a);
    }

    private static void json(HttpExchange ex, int status, JsonNode n) throws IOException {
        send(ex, status, "application/json", EpisodeJson.JSON.writeValueAsBytes(n));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    // ---- main ------------------------------------------------------------------------------------------------------

    /**
     * {@code RetrievalServer --port 8080 (--demo | --iceberg k=v,... [--namespace tsx])}. The demo store holds three
     * deterministic synthetic series (6 h at 1 Hz, 10-minute episodes) with a planted pattern; used by the Python
     * client tests in CI.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        SeriesStore store;
        if (a.containsKey("iceberg")) {
            Map<String, String> props = new LinkedHashMap<>();
            for (String kv : a.get("iceberg").split(",")) {
                int i = kv.indexOf('=');
                props.put(kv.substring(0, i), kv.substring(i + 1));
            }
            store = (SeriesStore) Class.forName("org.opentsx.store.iceberg.IcebergSeriesStore")
                    .getMethod("open", Map.class, String.class).invoke(null, props, a.getOrDefault("namespace", "tsx"));
        } else {
            store = demoStore();
        }
        RetrievalServer s = new RetrievalServer(new EpisodeService(store), Integer.parseInt(a.getOrDefault("port", "8080")));
        System.out.println("opentsx retrieval server on port " + s.port());
    }

    /** Planted pattern of the demo store: 120 samples starting at sample 5000 of series "demo.signal{n=1}". */
    public static final int DEMO_PATTERN_AT = 5000;
    public static final int DEMO_PATTERN_LENGTH = 120;

    public static InMemorySeriesStore demoStore() {
        InMemorySeriesStore store = new InMemorySeriesStore();
        long t0 = 1_790_000_000_000_000L;
        List<Episode> eps = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            SeriesKey key = SeriesKeys.of("demo.signal", Map.of("n", Integer.toString(n)), "1");
            Random r = new Random(100 + n);
            double[] x = new double[6 * 3600];
            double v = 0;
            for (int i = 0; i < x.length; i++) {
                v += r.nextGaussian() * 0.3 - v * 0.01;
                x[i] = v;
            }
            if (n == 1) {
                for (int i = 0; i < DEMO_PATTERN_LENGTH; i++) {
                    double t = (double) i / (DEMO_PATTERN_LENGTH - 1);
                    x[DEMO_PATTERN_AT + i] = 3 * Math.sin(3 * Math.PI * t) * Math.exp(-3 * t) + 1.5 * t;
                }
            }
            for (int s = 0; s < x.length; s += 600) {
                double[] chunk = java.util.Arrays.copyOfRange(x, s, s + 600);
                String id = org.opentsx.model.Ulid.deterministic((t0 + s * 1_000_000L) / 1000, n * 1_000_003L + s);
                eps.add(Episodes.regular(key, t0 + s * 1_000_000L, 1_000_000L, chunk).episodeId(id).source("demo")
                        .createdAt(t0).build());
            }
        }
        store.write(eps);
        return store;
    }
}
