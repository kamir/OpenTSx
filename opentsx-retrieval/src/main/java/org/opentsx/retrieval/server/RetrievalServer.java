package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.opentsx.kafka.TopicLayout;
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
import org.opentsx.retrieval.system.Systems;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
 * GET  /v1/dags               the DAGs of the domain: what was deployed or stopped, and what the vector processor
 *                             reports per sink (only with {@code --kafscale})
 * POST /v1/dags/deploy        opentsx.dag.v1 as YAML: hands the DAG to the vector processor (control topic)
 * POST /v1/dags/stop?name=    stops the DAG of that name
 *                             (both only with {@code --kafscale} and {@code --dag-runs true}, and with the run token
 *                             when the server has one)
 * GET  /v1/health
 * </pre>
 * Episode responses are JSON by default; with {@code Accept: application/x-opentsx-avro-stream} they are a stream of
 * {@code [uint32 big-endian length][Avro single-object encoded Episode]} - what the Python client uses.
 */
public final class RetrievalServer implements AutoCloseable {

    public static final String AVRO_STREAM = "application/x-opentsx-avro-stream";

    private static final Logger LOG = LoggerFactory.getLogger(RetrievalServer.class);

    private final EpisodeService service;
    private final SeriesInventory inventory;
    private final Dags dags;
    private final boolean dagWrites;
    private final byte[] dagRunToken;
    private final Systems systems;
    /** Runs of the twin on request, for the systems that have a scenario. */
    private final org.opentsx.retrieval.runs.Runs runs = org.opentsx.retrieval.runs.Runs.standard();
    private final HttpServer http;
    private final OpenTsxAvro avro = new OpenTsxAvro();

    public RetrievalServer(EpisodeService service, int port) throws IOException {
        this(service, port, new SeriesInventory(service.primary(), SeriesInventory.Source.of("store"), null, null,
                Duration.ofSeconds(60)));
    }

    public RetrievalServer(EpisodeService service, int port, SeriesInventory inventory) throws IOException {
        this(service, port, inventory, null, false, null);
    }

    /** The DAGs of the domain: deploying and stopping them for the vector processor, and their state. */
    public interface Dags {
        /** @param by who asks, for the record in the control topic */
        JsonNode deploy(JsonNode dag, String text, String by);

        JsonNode stop(String name, String by);

        JsonNode list();
    }

    /** Header a caller of the DAG write routes proves itself with when the server has a run token. */
    public static final String RUN_TOKEN_HEADER = "X-OpenTSx-Run-Token";
    /** Header that names who asked for a deployment or a stop; kept in the control record. */
    public static final String BY_HEADER = "X-OpenTSx-By";

    /**
     * @param dags        behind {@code /v1/dags}; null when the server has no DAGs (no KafScale)
     * @param dagWrites   whether {@code POST /v1/dags/deploy} and {@code /stop} are offered
     * @param dagRunToken when not blank, the write routes need this value in the header {@link #RUN_TOKEN_HEADER}
     */
    public RetrievalServer(EpisodeService service, int port, SeriesInventory inventory, Dags dags, boolean dagWrites,
                           String dagRunToken) throws IOException {
        this(service, port, inventory, dags, dagWrites, dagRunToken, null);
    }

    /** @param systems the system documents behind {@code /v1/systems}; null when the server has none */
    public RetrievalServer(EpisodeService service, int port, SeriesInventory inventory, Dags dags, boolean dagWrites,
                           String dagRunToken, Systems systems) throws IOException {
        this.service = service;
        this.systems = systems;
        this.inventory = inventory;
        this.dags = dags;
        this.dagWrites = dagWrites && dags != null;
        if (systems != null) {
            // runs on the series of the signals read what this server holds
            runs.signals(new org.opentsx.retrieval.runs.SignalInputs.Source() {
                @Override
                public JsonNode signals(String system, JsonNode bindings) {
                    return systems.signals(system, inventory.get(), Systems.bindings(EpisodeJson.JSON.createObjectNode().set("bindings", bindings)));
                }

                @Override
                public List<org.opentsx.model.v2.Episode> read(List<String> seriesIds, long fromMicros, long toMicros) {
                    return service.episodes().series(seriesIds.toArray(String[]::new))
                            .between(org.opentsx.model.Micros.toInstant(fromMicros), org.opentsx.model.Micros.toInstant(toMicros)).fetch();
                }
            });
        }
        if (systems != null && dags != null) {
            // which flows run, so that a signal written by several flows shows the one that runs (D84)
            systems.runningFlows(() -> {
                java.util.Set<String> names = new java.util.TreeSet<>();
                dags.list().path("dags").forEach(x -> {
                    if ("deployed".equals(x.path("desired").asText())) {
                        names.add(x.path("dag").asText());
                    }
                });
                return names;
            });
        }
        this.dagRunToken = dagRunToken == null || dagRunToken.isBlank() ? null : dagRunToken.getBytes(StandardCharsets.UTF_8);
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.createContext("/", this::handle);
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
            String allow = allowed(path);
            if (allow != null && !Arrays.asList(allow.split(", ")).contains(method)) {
                ex.getResponseHeaders().set("Allow", allow);
                error(ex, 405, method + " " + path + ": method not allowed; expected " + allow);
            } else if (method.equals("GET") && path.equals("/v1/health")) {
                json(ex, 200, EpisodeJson.JSON.createObjectNode().put("status", "ok"));
            } else if (method.equals("GET") && path.equals("/v1/inventory")) {
                json(ex, 200, inventory.get());
            } else if (method.equals("GET") && (path.equals("/v1/systems") || path.startsWith("/v1/systems/"))) {
                systems(ex, path);
            } else if (method.equals("POST") && path.startsWith("/v1/systems/")) {
                systemsPost(ex, path);
            } else if (method.equals("GET") && path.equals("/v1/dags")) {
                if (dags == null) {
                    error(ex, 404, "this server has no DAGs (it needs --kafscale)");
                } else {
                    ObjectNode list = (ObjectNode) dags.list();
                    list.put("writes", dagWrites);
                    json(ex, 200, list);
                }
            } else if (method.equals("POST") && (path.equals("/v1/dags/deploy") || path.equals("/v1/dags/stop"))) {
                if (!dagWrites) {
                    error(ex, 404, "this server does not deploy DAGs (it needs --kafscale and --dag-runs true)");
                } else if (!mayRun(ex)) {
                    error(ex, 401, "this server deploys DAGs for callers with its run token only (header " + RUN_TOKEN_HEADER + ")");
                } else if (path.equals("/v1/dags/stop")) {
                    json(ex, 200, dags.stop(query(ex).get("name"), by(ex)));
                } else {
                    json(ex, 200, deployDag(ex));
                }
            } else if (method.equals("GET") && path.equals("/v1/episodes")) {
                episodes(ex, listEpisodes(query(ex)));
            } else if (method.equals("GET") && path.startsWith("/v1/episodes/")) {
                String id = episodeId(path.substring("/v1/episodes/".length()), "episode id");
                var e = service.episode(id);
                if (e.isEmpty()) {
                    error(ex, 404, "unknown episode " + id);
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
                error(ex, 404, "no such endpoint: " + path);
            }
        } catch (TooLarge e) {
            error(ex, 413, e.getMessage());
        } catch (java.util.NoSuchElementException e) {
            error(ex, 404, String.valueOf(e.getMessage()));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            error(ex, 400, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            LOG.error("request failed method={} path={} status=500 cause={}", oneLine(method), oneLine(path), causes(e));
            LOG.debug("stack trace of the failed request method={} path={}", oneLine(method), oneLine(path), e);
            error(ex, 500, "internal error");
        }
    }

    /** The methods a known path takes, as the Allow header lists them; null for a path the server does not know. */
    private static String allowed(String path) {
        return switch (path) {
            case "/v1/health", "/v1/inventory", "/v1/systems", "/v1/dags", "/v1/episodes" -> "GET";
            case "/v1/dags/deploy", "/v1/dags/stop", "/v1/patterns/search", "/v1/segments" -> "POST";
            case "/v1/episodes/similar" -> "GET, POST";
            default -> {
                if (path.matches("/v1/systems/[^/]+/(signals|plan|decisions|runs)")) {
                    yield "GET, POST";
                } else if (path.matches("/v1/systems/[^/]+/(scenario|runs/.+)") || path.matches("/v1/episodes/[^/]+")) {
                    yield "GET";
                } else if (path.matches("/v1/systems/[^/]+/generate")) {
                    yield "POST";
                }
                yield null;
            }
        };
    }

    /** The exception and its causes on one line, for the log. */
    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            s.append(s.length() == 0 ? "" : "; caused by ").append(c);
        }
        return oneLine(s.toString());
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}", " ");
    }

    // ---- endpoints -------------------------------------------------------------------------------------------------

    private List<Episode> listEpisodes(Map<String, String> q) {
        EpisodeService.EpisodesQuery eq = select(service.episodes(), q.get("metric"), tags(q), q.get("series"),
                q.get("from"), q.get("to"));
        String clip = q.getOrDefault("clip", "false");
        if (!clip.equals("true") && !clip.equals("false")) {
            throw notAllowed("clip", clip, "true, false");
        }
        if (clip.equals("true")) {
            eq.clip();
        }
        if (q.containsKey("limit")) {
            String limit = q.get("limit");
            try {
                eq.limit(Integer.parseInt(limit));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("limit: \"" + limit + "\" is not an int; expected a whole number from 1 to "
                        + EpisodeService.MAX_EPISODES, e);
            }
        }
        if (q.containsKey("resample")) {
            eq.resample(duration("resample", q.get("resample")), oneOf("agg", q.getOrDefault("agg", "MEAN"), Resampling.Agg.class));
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

    /** A JSON body of a POST route; a larger one is answered 413 after reading at most one byte more. */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super("request body: larger than " + MAX_BODY_BYTES + " bytes; send at most " + MAX_BODY_BYTES + " bytes");
        }
    }

    /** Who asked, as far as the caller says; a name for the record, not a proof. */
    private static String by(HttpExchange ex) {
        String by = ex.getRequestHeaders().getFirst(BY_HEADER);
        return by == null || by.isBlank() ? "unknown" : by.replaceAll("[^\\x20-\\x7E]", "?").trim();
    }

    private JsonNode deployDag(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_DAG_BYTES + 1);
        if (body.length > MAX_DAG_BYTES) {
            throw new IllegalArgumentException("the DAG is larger than " + MAX_DAG_BYTES + " bytes");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        JsonNode dag;
        try {
            dag = YAML.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the DAG is no valid YAML: " + e.getOriginalMessage(), e);
        }
        return dags.deploy(dag, text, by(ex));
    }

    private List<PatternMatch> search(JsonNode b) {
        EpisodeService.SubsequenceQuery sq;
        if (b.has("pattern")) {
            JsonNode pattern = b.get("pattern");
            if (!pattern.isArray()) {
                throw new IllegalArgumentException("pattern: " + shown(pattern) + " is not an array; expected an array of 4 to "
                        + EpisodeService.MAX_PATTERN_LENGTH + " numbers");
            }
            double[] p = new double[pattern.size()];
            for (int i = 0; i < p.length; i++) {
                p[i] = number(pattern.path(i), "pattern[" + i + "]");
            }
            sq = service.similarTo(p);
        } else if (!b.hasNonNull("episodeId")) {
            throw new IllegalArgumentException("pattern: missing; expected an array of 4 to " + EpisodeService.MAX_PATTERN_LENGTH
                    + " numbers, or episodeId with offset and length");
        } else {
            sq = service.similarTo(episodeId(text(b, "episodeId"), "episodeId"), integer(b.path("offset"), "offset", 0),
                    integer(b.path("length"), "length", 0));
        }
        select(sq, text(b, "metric"), tags(b), text(b, "series"), text(b, "from"), text(b, "to"));
        return sq.distance(distance(b)).topK(integer(b.path("topK"), "topK", 10, 1, EpisodeService.MAX_TOP_K)).fetch();
    }

    private List<PatternMatch> similar(JsonNode b) {
        EpisodeService.SimilarEpisodesQuery q = service.similarEpisodes(episodeId(text(b, "episodeId"), "episodeId"));
        select(q, text(b, "metric"), tags(b), text(b, "series"), text(b, "from"), text(b, "to"));
        return q.distance(distance(b)).topK(integer(b.path("topK"), "topK", 10, 1, EpisodeService.MAX_TOP_K)).fetch();
    }

    private List<Episode> segments(JsonNode b) {
        JsonNode p = b.path("params");
        String strategy = b.path("strategy").asText();
        Segmenter s = switch (strategy) {
            case "FIXED_DURATION" -> Segmenter.fixedDuration(dur(p, "duration", null), dur(p, "offset", "PT0S"));
            case "FIXED_COUNT" -> Segmenter.fixedCount(integer(p.path("n"), "params.n", 0), integer(p.path("overlap"), "params.overlap", 0));
            case "FEATURE_EXTREMUM" -> Segmenter.extremum(oneOf("params.feature", p.path("feature").asText("MAX"), Segmenter.Feature.class),
                    number(p.path("prominence"), "params.prominence"), dur(p, "window", null), dur(p, "distance", null),
                    dur(p, "pre", "PT0S"), dur(p, "post", "PT0S"));
            case "FEATURE_THRESHOLD" -> {
                double enter = number(p.path("enter"), "params.enter");
                yield Segmenter.threshold(oneOf("params.direction", p.path("direction").asText("ABOVE"), Segmenter.Direction.class), enter,
                        number(p.path("exit"), "params.exit", enter), dur(p, "pre", "PT0S"), dur(p, "post", "PT0S"),
                        dur(p, "minDuration", "PT0S"), dur(p, "maxDuration", "PT0S"));
            }
            default -> throw notAllowed("strategy", strategy, "FIXED_DURATION, FIXED_COUNT, FEATURE_EXTREMUM, FEATURE_THRESHOLD");
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
            Instant f = from == null ? Instant.parse("1970-01-01T00:00:00Z") : instant("from", from);
            Instant t = to == null ? Instant.parse("9999-01-01T00:00:00Z") : instant("to", to);
            if (!f.isBefore(t)) {
                throw new IllegalArgumentException(from == null
                        ? "to: \"" + to + "\" is not after from, which defaults to " + f + "; expected a later to or an earlier from"
                        : to == null
                        ? "from: \"" + from + "\" is not before to, which defaults to " + t + "; expected an earlier from or a later to"
                        : "from: \"" + from + "\" is not before to \"" + to + "\"; expected from earlier than to");
            }
            sel.between(f, t);
        }
        return sel;
    }

    private static Instant instant(String field, String v) {
        try {
            return Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(field + ": \"" + v + "\" is not an instant; expected an ISO-8601 instant such as"
                    + " 2026-01-01T00:00:00Z", e);
        }
    }

    private static Duration duration(String field, String v) {
        try {
            return Duration.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(field + ": \"" + v + "\" is not a duration; expected an ISO-8601 duration such as PT1M", e);
        }
    }

    private static <E extends Enum<E>> E oneOf(String field, String v, Class<E> type) {
        for (E c : type.getEnumConstants()) {
            if (c.name().equals(v)) {
                return c;
            }
        }
        throw notAllowed(field, v, Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")));
    }

    private static IllegalArgumentException notAllowed(String field, String v, String allowed) {
        return new IllegalArgumentException(field + ": \"" + v + "\" is not allowed; expected one of " + allowed);
    }

    /** An episode id from a path or a body: present, and without a slash, which no route takes inside an id. */
    private static String episodeId(String id, String field) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException(field + ": missing; expected the id of one episode");
        }
        if (id.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + ": \"" + id + "\" holds a slash; expected the id of one episode");
        }
        return id;
    }

    private static Distance distance(JsonNode b) {
        String d = b.path("distance").asText("zED");
        if (d.equalsIgnoreCase("DTW")) {
            return Distance.dtw(number(b.path("band"), "band", 0.1));
        }
        if (d.equalsIgnoreCase("zED")) {
            return Distance.zNormalizedEuclidean();
        }
        throw notAllowed("distance", d, "zED, DTW");
    }

    private static final String NUMBER = "expected a number without quotes, such as 0.01 or 1e-3";

    private static double number(JsonNode v, String field) {
        if (v.isMissingNode() || v.isNull()) {
            throw new IllegalArgumentException(field + ": missing; " + NUMBER);
        }
        return number(v, field, 0);
    }

    private static double number(JsonNode v, String field, double dflt) {
        if (v.isMissingNode() || v.isNull()) {
            return dflt;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException(field + ": " + shown(v) + " is not a number; " + NUMBER);
        }
        return v.asDouble();
    }

    private static int integer(JsonNode v, String field, int dflt) {
        return integer(v, field, dflt, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** {@code min} and {@code max} name the range in the message; the service that takes the value checks it. */
    private static int integer(JsonNode v, String field, int dflt, int min, int max) {
        if (v.isMissingNode() || v.isNull()) {
            return dflt;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new IllegalArgumentException(field + ": " + shown(v) + " is not a whole number; expected a whole number from " + min
                    + " to " + max + " without quotes");
        }
        return v.asInt();
    }

    private static String shown(JsonNode v) {
        return "\"" + (v.isTextual() ? v.asText().trim() : v.toString()) + "\"";
    }

    private static Duration dur(JsonNode p, String name, String dflt) {
        String v = p.path(name).asText(dflt);
        if (v == null) {
            throw new IllegalArgumentException("params." + name + ": missing; expected an ISO-8601 duration such as PT1M");
        }
        return duration("params." + name, v);
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
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            throw new TooLarge();
        }
        if (raw.length == 0) {
            return EpisodeJson.JSON.createObjectNode();
        }
        JsonNode b;
        try {
            b = EpisodeJson.JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the body is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (!b.isObject()) {
            throw new IllegalArgumentException("the body is not a JSON object; expected {...} with the fields of the route");
        }
        return b;
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

    /** An error answer: {@code {"error": text, "type": t}}, with the type a client can branch on. */
    private static void error(HttpExchange ex, int status, String text) throws IOException {
        json(ex, status, EpisodeJson.JSON.createObjectNode().put("error", text).put("type", type(status)));
    }

    private static String type(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 413 -> "too_large";
            case 500 -> "internal";
            default -> throw new IllegalStateException("no error type for status " + status);
        };
    }

    /**
     * {@code /v1/systems}: the list; {@code /v1/systems/<id>/signals}: every signal of one system with its state;
     * {@code /v1/systems/<id>/plan}: the atoms of its plan with their ports and what feeds each input.
     */
    private void systems(HttpExchange ex, String path) throws IOException {
        if (systems == null) {
            error(ex, 404, "this server has no system documents (it needs --systems)");
            return;
        }
        String[] p = path.substring("/v1/systems".length()).split("/");
        if (p.length <= 1) {
            json(ex, 200, systems.list(inventory.get()));
        } else if (p.length == 3 && p[2].equals("signals") && systems.has(p[1])) {
            json(ex, 200, systems.signals(p[1], inventory.get()));
        } else if (p.length == 3 && p[2].equals("plan") && systems.has(p[1])) {
            json(ex, 200, systems.plan(p[1], inventory.get()));
        } else if (p.length == 3 && p[2].equals("decisions") && systems.has(p[1])) {
            json(ex, 200, systems.decisions(p[1], inventory.get(), List.of()));
        } else if (p.length >= 3 && (p[2].equals("scenario") || p[2].equals("runs")) && systems.has(p[1])) {
            runs(ex, p);
        } else {
            error(ex, 404, "no such system or route: " + path);
        }
    }

    /**
     * {@code GET /v1/systems/<id>/scenario}: what can be asked of the twin of the system. {@code /runs}: the runs kept.
     * {@code /runs/<run>}: one run. {@code /runs/<run>/series?keys=a,b}: its series. {@code /runs/<run>/compare?base=<run>}:
     * the run next to another one. Runs compute and keep nothing outside this process; they write to no topic.
     */
    private void runs(HttpExchange ex, String[] p) throws IOException {
        String system = p[1];
        if (!runs.has(system)) {
            error(ex, 404, "the system " + system + " has no scenario to run");
        } else if (p.length == 3 && p[2].equals("scenario")) {
            json(ex, 200, runs.scenario(system));
        } else if (p.length == 3) {
            json(ex, 200, runs.list(system));
        } else if (p.length == 4) {
            json(ex, 200, runs.doc(system, p[3]));
        } else if (p.length == 5 && p[4].equals("series")) {
            String keys = query(ex).getOrDefault("keys", "");
            if (keys.isBlank()) {
                throw new IllegalArgumentException("name the series: ?keys=atom.port,atom.port");
            }
            json(ex, 200, runs.series(system, p[3], List.of(keys.split(","))));
        } else if (p.length == 5 && p[4].equals("compare")) {
            json(ex, 200, runs.compare(system, p[3], query(ex).getOrDefault("base", runs.reference(system))));
        } else {
            error(ex, 404, "no such route for runs");
        }
    }

    /**
     * {@code POST /v1/systems/<id>/signals}: the state of the signals with the bindings of a working copy laid over
     * the document (body {@code {bindings: [...]}}); reads only. {@code POST /v1/systems/<id>/generate}: runs the demo
     * generator of a signal and writes the series; a write, so it needs the run token.
     */
    private void systemsPost(HttpExchange ex, String path) throws IOException {
        String[] p = path.substring("/v1/systems".length()).split("/");
        if (systems == null || p.length != 3 || !systems.has(p[1])) {
            error(ex, 404, "no such system or route: " + path);
            return;
        }
        JsonNode body = body(ex);
        if (p[2].equals("runs")) {
            if (!runs.has(p[1])) {
                error(ex, 404, "the system " + p[1] + " has no scenario to run");
            } else {
                json(ex, 200, runs.run(p[1], body));
            }
        } else if (p[2].equals("signals")) {
            json(ex, 200, systems.signals(p[1], inventory.get(), Systems.bindings(body)));
        } else if (p[2].equals("decisions")) {
            json(ex, 200, systems.decisions(p[1], inventory.get(), Systems.bindings(body)));
        } else if (p[2].equals("plan")) {
            json(ex, 200, systems.plan(p[1], inventory.get(), Systems.bindings(body)));
        } else if (p[2].equals("generate")) {
            if (signalWriter == null) {
                error(ex, 404, "this server cannot write generated series (it needs --kafscale and --dag-runs true)");
            } else if (!mayRun(ex)) {
                error(ex, 401, "this server writes for callers with its run token only (header " + RUN_TOKEN_HEADER + ")");
            } else {
                json(ex, 200, systems.generate(p[1], body, syntheticTopic, signalWriter));
            }
        } else {
            error(ex, 404, "no such route: " + path);
        }
    }

    private volatile Systems.Writer signalWriter;
    private volatile String syntheticTopic;

    /** Lets the server write generated series: only to this topic, and only for callers the write routes admit. */
    public void generatedSeries(String topic, Systems.Writer writer) {
        this.syntheticTopic = topic;
        this.signalWriter = writer;
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
     * [--catalog file.yaml] [--systems dir [--systems-root dir]]}. {@code --systems} names a directory of system documents
     * ({@code *.system.yaml}, {@code opentsx.system.v1}); their references are relative to {@code --systems-root}.
     *
     * <p>The demo store holds three deterministic synthetic series (6 h at 1 Hz, 10-minute episodes) with a planted
     * pattern; used by the Python client tests in CI. With {@code --kafscale} the server reads the episodes topic
     * {@code tsx.<domain>.episodes.v2} into memory and follows it, and it reads the vector topics of the domain.
     * {@code --dag-runs true} offers {@code POST /v1/dags/deploy} and {@code /stop}: they write to the control topic
     * of the vector processor, which then writes the vector topics. With a run token
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
                    .getMethod("openExisting", Map.class, String.class).invoke(null, props, a.getOrDefault("namespace", "tsx"));
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
        String[] generated = {null};
        Systems.Writer[] writer = {null};
        Dags dags = null;
        boolean dagWrites = false;
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
            dags = new Dags() {
                @Override
                public JsonNode deploy(JsonNode dag, String text, String by) {
                    return feed.deploy(dag, text, by);
                }

                @Override
                public JsonNode stop(String name, String by) {
                    return feed.stop(name, by);
                }

                @Override
                public JsonNode list() {
                    return feed.dags();
                }
            };
            dagWrites = dagRuns;
            if (dagRuns) {
                generated[0] = new TopicLayout(domain).synthetic();
                writer[0] = feed::writeGenerated;
                System.out.println(runToken.isBlank()
                        ? "dag deployments: OPEN, anyone who reaches this port can deploy and stop DAGs (set OPENTSX_DAG_RUN_TOKEN)"
                        : "dag deployments: for callers with the run token");
            }
        } else {
            inventory = new SeriesInventory(store, source, scenario, catalog, Duration.ofSeconds(60));
        }
        Systems systems = null;
        if (a.containsKey("systems")) {
            java.nio.file.Path dir = java.nio.file.Path.of(a.get("systems"));
            systems = Systems.load(dir, java.nio.file.Path.of(a.getOrDefault("systems-root", ".")));
            for (org.opentsx.retrieval.system.SystemDoc d : systems.docs()) {
                System.out.println("system " + d.system() + ": " + d.signals().size() + " signals, " + d.problems().size() + " problems");
                d.problems().forEach(pr -> System.out.println("  problem: " + pr));
            }
        }
        RetrievalServer s = new RetrievalServer(new EpisodeService(store), Integer.parseInt(a.getOrDefault("port", "8080")),
                inventory, dags, dagWrites, runToken, systems);
        if (writer[0] != null) {
            s.generatedSeries(generated[0], writer[0]);
        }
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
