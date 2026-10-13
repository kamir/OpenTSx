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
 * GET  /v1/inventory          every series with extent, labels and ground-truth marks, see {@link SeriesInventory}
 * POST /v1/dags/run           opentsx.dag.v1 as YAML: runs its vector sinks once and writes their topics (only with
 *                             {@code --kafscale} and {@code --dag-runs true})
 * GET  /v1/health
 * </pre>
 * Episode responses are JSON by default; with {@code Accept: application/x-opentsx-avro-stream} they are a stream of
 * {@code [uint32 big-endian length][Avro single-object encoded Episode]} - what the Python client uses.
 */
public final class RetrievalServer implements AutoCloseable {

    public static final String AVRO_STREAM = "application/x-opentsx-avro-stream";

    private final EpisodeService service;
    private final SeriesInventory inventory;
    private final DagRunner dagRunner;
    private final byte[] dagRunToken;
    private final HttpServer http;
    private final OpenTsxAvro avro = new OpenTsxAvro();

    public RetrievalServer(EpisodeService service, int port) throws IOException {
        this(service, port, new SeriesInventory(service.primary(), SeriesInventory.Source.of("store"), null, null,
                Duration.ofSeconds(60)));
    }

    public RetrievalServer(EpisodeService service, int port, SeriesInventory inventory) throws IOException {
        this(service, port, inventory, null);
    }

    /** Runs the sinks of a DAG that this server can execute and says what it wrote. */
    @FunctionalInterface
    public interface DagRunner {
        JsonNode run(JsonNode dag, String text);
    }

    /** Header a caller of {@code POST /v1/dags/run} proves itself with when the server has a run token. */
    public static final String RUN_TOKEN_HEADER = "X-OpenTSx-Run-Token";

    /** @param dagRunner behind {@code POST /v1/dags/run}; null when the server does not run DAGs */
    public RetrievalServer(EpisodeService service, int port, SeriesInventory inventory, DagRunner dagRunner) throws IOException {
        this(service, port, inventory, dagRunner, null);
    }

    /**
     * @param dagRunner   behind {@code POST /v1/dags/run}; null when the server does not run DAGs
     * @param dagRunToken when not blank, a run needs this value in the header {@link #RUN_TOKEN_HEADER}
     */
    public RetrievalServer(EpisodeService service, int port, SeriesInventory inventory, DagRunner dagRunner,
                           String dagRunToken) throws IOException {
        this.service = service;
        this.inventory = inventory;
        this.dagRunner = dagRunner;
        this.dagRunToken = dagRunToken == null || dagRunToken.isBlank() ? null : dagRunToken.getBytes(StandardCharsets.UTF_8);
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
            } else if (method.equals("GET") && path.equals("/v1/inventory")) {
                json(ex, 200, inventory.get());
            } else if (method.equals("POST") && path.equals("/v1/dags/run")) {
                if (dagRunner == null) {
                    json(ex, 404, EpisodeJson.JSON.createObjectNode().put("error",
                            "this server does not run DAGs (it needs --kafscale and --dag-runs true)"));
                } else if (!mayRun(ex)) {
                    json(ex, 401, EpisodeJson.JSON.createObjectNode().put("error",
                            "this server runs DAGs for callers with its run token only (header " + RUN_TOKEN_HEADER + ")"));
                } else {
                    json(ex, 200, runDag(ex));
                }
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

    private boolean mayRun(HttpExchange ex) {
        if (dagRunToken == null) {
            return true;
        }
        String given = ex.getRequestHeaders().getFirst(RUN_TOKEN_HEADER);
        // constant time, so the answer does not tell how much of a guess was right
        return given != null && java.security.MessageDigest.isEqual(dagRunToken, given.getBytes(StandardCharsets.UTF_8));
    }

    /** A DAG document is small; anything larger is not one. */
    private static final int MAX_DAG_BYTES = 256 * 1024;

    private JsonNode runDag(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_DAG_BYTES + 1);
        if (body.length > MAX_DAG_BYTES) {
            throw new IllegalArgumentException("the DAG is larger than " + MAX_DAG_BYTES + " bytes");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        JsonNode dag;
        try {
            dag = YAML.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the DAG is no valid YAML: " + e.getOriginalMessage());
        }
        return dagRunner.run(dag, text);
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
     * {@code RetrievalServer --port 8080 (--demo true | --iceberg k=v,... [--namespace tsx] | --kafscale host:port
     * [--domain wind] [--refresh PT30S] [--max-episodes 20000] [--dag-runs true]) [--scenario file.yaml]
     * [--catalog file.yaml]}.
     *
     * <p>The demo store holds three deterministic synthetic series (6 h at 1 Hz, 10-minute episodes) with a planted
     * pattern; used by the Python client tests in CI. With {@code --kafscale} the server reads the episodes topic
     * {@code tsx.<domain>.episodes.v2} into memory and follows it, and it reads the vector topics of the domain.
     * {@code --dag-runs true} lets {@code POST /v1/dags/run} write vector topics. With a run token
     * ({@code --dag-run-token} or the environment variable {@code OPENTSX_DAG_RUN_TOKEN}) a run needs it in the header
     * {@code X-OpenTSx-Run-Token}; without one, anyone who reaches the port can write. {@code --scenario} and {@code --catalog} are handed out unchanged in
     * {@code GET /v1/inventory}.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        SeriesStore store;
        SeriesInventory.Source source;
        if (a.containsKey("iceberg")) {
            Map<String, String> props = new LinkedHashMap<>();
            for (String kv : a.get("iceberg").split(",")) {
                int i = kv.indexOf('=');
                props.put(kv.substring(0, i), kv.substring(i + 1));
            }
            store = (SeriesStore) Class.forName("org.opentsx.store.iceberg.IcebergSeriesStore")
                    .getMethod("open", Map.class, String.class).invoke(null, props, a.getOrDefault("namespace", "tsx"));
            source = SeriesInventory.Source.of("iceberg");
        } else if (a.containsKey("kafscale")) {
            source = null;      // built below, with the topic names of the domain
            store = new InMemorySeriesStore();
        } else {
            store = demoStore();
            source = SeriesInventory.Source.of("demo");
        }
        JsonNode scenario = yaml(a.get("scenario"));
        JsonNode catalog = yaml(a.get("catalog"));
        SeriesInventory inventory;
        DagRunner runner = null;
        String envToken = System.getenv("OPENTSX_DAG_RUN_TOKEN");
        String runToken = a.getOrDefault("dag-run-token", envToken == null ? "" : envToken);
        if (a.containsKey("kafscale")) {
            String domain = a.getOrDefault("domain", "wind");
            boolean dagRuns = Boolean.parseBoolean(a.getOrDefault("dag-runs", "false"));
            Runnable[] invalidate = {() -> { }};
            KafScaleFeed feed = KafScaleFeed.start(a.get("kafscale"), domain, Duration.parse(a.getOrDefault("refresh", "PT30S")),
                    (InMemorySeriesStore) store, Integer.parseInt(a.getOrDefault("max-episodes", "20000")), () -> invalidate[0].run());
            // episodes of the domain plus the components of its vector topics
            store = new CompositeSeriesStore(store, feed::vectorStore);
            // the feed invalidates the inventory whenever something was appended, so the cache may live long
            inventory = new SeriesInventory(store, new SeriesInventory.Source("kafscale", domain, KafScaleFeed.topics(domain)),
                    scenario, catalog, Duration.ofMinutes(10));
            invalidate[0] = inventory::invalidate;
            inventory.truncatedWhen(feed::truncated);
            inventory.sourceDetails(src -> {
                src.put("dagRuns", dagRuns);
                if (dagRuns) {
                    src.put("dagRunAuth", runToken.isBlank() ? "none" : "token");
                }
                ArrayNode vt = src.putArray("vectorTopics");
                feed.vectorTopics().forEach(vt::add);
                if (!feed.unreadableVectorTopics().isEmpty()) {
                    ArrayNode bad = src.putArray("unreadableVectorTopics");
                    feed.unreadableVectorTopics().forEach(bad::add);
                }
            });
            if (dagRuns) {
                runner = feed::run;
                System.out.println(runToken.isBlank()
                        ? "dag runs: OPEN, anyone who reaches this port can write vector topics (set OPENTSX_DAG_RUN_TOKEN)"
                        : "dag runs: for callers with the run token");
            }
        } else {
            inventory = new SeriesInventory(store, source, scenario, catalog, Duration.ofSeconds(60));
        }
        RetrievalServer s = new RetrievalServer(new EpisodeService(store), Integer.parseInt(a.getOrDefault("port", "8080")),
                inventory, runner, runToken);
        System.out.println("opentsx retrieval server on port " + s.port());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper YAML =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    /** Reads a YAML (or JSON) file as a tree; null when no file is given. */
    private static JsonNode yaml(String file) throws IOException {
        if (file == null || file.isBlank()) {
            return null;
        }
        return YAML.readTree(java.nio.file.Path.of(file).toFile());
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
