package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.KafkaException;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.KafScaleClients;
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
import org.opentsx.retrieval.vector.DagPlan;
import org.opentsx.retrieval.vector.VectorProcessor;
import org.opentsx.store.InMemorySeriesStore;
import org.opentsx.store.SeriesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
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
 *                             when the server has one; {@link #main} starts no such server without one unless
 *                             {@code --open-dag-runs} binds it to 127.0.0.1); a DAG whose vector topic, or whose
 *                             signal on the clean or features topic, another DAG that the vector processor runs
 *                             writes is answered 409 {@code conflict}
 * GET  /v1/health             liveness: 200 while the process answers, also while every request thread waits
 * GET  /v1/ready              readiness: 200 once the server listens; with {@code --kafscale} 503 {@code unavailable}
 *                             after {@value #READY_PERIODS} failed refreshes of the feed in a row or as many refresh
 *                             periods without one that worked
 * </pre>
 * Episode responses are JSON by default; with {@code Accept: application/x-opentsx-avro-stream} they are a stream of
 * {@code [uint32 big-endian length][Avro single-object encoded Episode]}, which the Python client reads.
 */
public final class RetrievalServer implements AutoCloseable {

    public static final String AVRO_STREAM = "application/x-opentsx-avro-stream";

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
    private final Logger log = LoggerFactory.getLogger(RetrievalServer.class);

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

    /** A service the server depends on, such as the broker, cannot be reached; answered 503 {@code unavailable}. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The request conflicts with what the server holds, such as a vector topic or a signal another DAG writes; answered 409 {@code conflict}. */
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    /** A log the server follows, as {@code GET /v1/ready} reports it. */
    public interface Feed {
        /** When the last load or refresh of the log that worked ended. */
        Instant lastRefresh();

        /** Refreshes that failed since the last one that worked. */
        int refreshFailures();

        /** The time between two refreshes. */
        Duration refreshInterval();

        /** Whether the store stopped loading at its limit and holds part of the log only. */
        boolean truncated();
    }

    /** Refresh periods without a refresh that worked, and failed refreshes in a row, after which the server is not ready. */
    static final int READY_PERIODS = 3;

    private volatile Feed followed;
    private volatile Clock clock = Clock.systemUTC();

    /** Lets {@code GET /v1/ready} report this feed, its age read on {@code clock}. */
    void follows(Feed feed, Clock clock) {
        this.clock = clock;
        this.followed = feed;
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
        this(service, new InetSocketAddress(port), inventory, dags, dagWrites, dagRunToken, systems);
    }

    /** @param address where the server listens; the wildcard address is every interface */
    public RetrievalServer(EpisodeService service, InetSocketAddress address, SeriesInventory inventory, Dags dags,
                           boolean dagWrites, String dagRunToken, Systems systems) throws IOException {
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
        this.http = HttpServer.create(address, 0);
        // two threads take every request: they answer health and ready themselves and hand the rest to the request
        // threads, which can wait minutes for the broker
        http.setExecutor(Executors.newFixedThreadPool(2));
        http.createContext("/", this::dispatch);
        http.start();
    }

    /** Threads that answer the routes other than health and ready. */
    static final int REQUEST_THREADS = 4;
    private final ExecutorService requests = Executors.newFixedThreadPool(REQUEST_THREADS);

    private void dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/v1/health") || path.equals("/v1/ready")) {
            handle(ex);
            return;
        }
        try {
            requests.execute(() -> {
                try {
                    handle(ex);
                } catch (IOException | RuntimeException e) {
                    log.debug("request ended without an answer path={}", oneLine(path), e);
                    ex.close();
                }
            });
        } catch (RejectedExecutionException closing) {
            ex.close();
        }
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public InetSocketAddress address() {
        return http.getAddress();
    }

    /** Closes the port, then the KafScale feed when the server has one. */
    @Override
    public void close() {
        http.stop(0);
        ((ExecutorService) http.getExecutor()).shutdownNow();
        requests.shutdownNow();
        KafScaleFeed f = feed;
        if (f != null) {
            f.close();
        }
    }

    private volatile KafScaleFeed feed;

    /** The feed the server reads from; {@link #close()} closes it. */
    void closes(KafScaleFeed feed) {
        this.feed = feed;
    }

    /** The shutdown hook of {@link #main}: closes the server, so the port and the feed end in order, and logs it. */
    static Thread shutdownHook(RetrievalServer server) {
        return new Thread(() -> {
            Logger log = LoggerFactory.getLogger(RetrievalServer.class);
            log.info("stopping port={}", server.port());
            server.close();
            log.info("stopped");
        }, "retrieval-server-stop");
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
            } else if (method.equals("GET") && path.equals("/v1/ready")) {
                ready(ex);
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
                    json(ex, 200, dags.stop(query(ex, "name").get("name"), by(ex)));
                } else {
                    json(ex, 200, deployDag(ex));
                }
            } else if (method.equals("GET") && path.equals("/v1/episodes")) {
                episodes(ex, listEpisodes(query(ex, EPISODE_PARAMETERS)));
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
        } catch (Conflict e) {
            error(ex, 409, e.getMessage());
        } catch (Unavailable e) {
            log.error("request failed method={} path={} status=503 cause={}", oneLine(method), oneLine(path), causes(e));
            log.debug("stack trace of the failed request method={} path={}", oneLine(method), oneLine(path), e);
            error(ex, 503, e.getMessage());
        } catch (java.util.NoSuchElementException e) {
            error(ex, 404, String.valueOf(e.getMessage()));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            error(ex, 400, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("request failed method={} path={} status=500 cause={}", oneLine(method), oneLine(path), causes(e));
            log.debug("stack trace of the failed request method={} path={}", oneLine(method), oneLine(path), e);
            error(ex, 500, "internal error");
        }
    }

    /** The methods a known path takes, as the Allow header lists them; null for a path the server does not know. */
    private static String allowed(String path) {
        return switch (path) {
            case "/v1/health", "/v1/ready", "/v1/inventory", "/v1/systems", "/v1/dags", "/v1/episodes" -> "GET";
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

    /**
     * 200 with the age of the feed, its failed refreshes in a row and whether it is truncated; 503 {@code unavailable}
     * naming the reason after {@link #READY_PERIODS} failed refreshes in a row or as many refresh periods without one
     * that worked. A server without a feed holds what it loaded and is ready once it listens.
     */
    private void ready(HttpExchange ex) throws IOException {
        Feed feed = followed;
        ObjectNode answer = EpisodeJson.JSON.createObjectNode().put("status", "ready");
        if (feed == null) {
            json(ex, 200, answer);
            return;
        }
        Instant last = feed.lastRefresh();
        Duration age = Duration.between(last, clock.instant());
        Duration limit = feed.refreshInterval().multipliedBy(READY_PERIODS);
        int failures = feed.refreshFailures();
        if (failures >= READY_PERIODS) {
            error(ex, 503, "feed: " + failures + " refreshes failed in a row, the last one that worked ended at " + last
                    + "; check that the broker is reachable and read the log of the server");
        } else if (age.compareTo(limit) > 0) {
            error(ex, 503, "feed: no refresh worked for " + age.toSeconds() + " s, more than " + READY_PERIODS
                    + " refresh periods of " + feed.refreshInterval() + "; check that the broker is reachable and read the"
                    + " log of the server");
        } else {
            answer.putObject("feed").put("lastRefresh", last.toString()).put("ageSeconds", age.toSeconds())
                    .put("refreshFailures", failures).put("truncated", feed.truncated());
            json(ex, 200, answer);
        }
    }

    /** The exception and its causes on one line, for the log. */
    static String causes(Throwable t) {
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
        } else if (q.containsKey("agg")) {
            throw new IllegalArgumentException("agg: \"" + q.get("agg") + "\" needs resample; expected resample with agg, such as"
                    + " resample=PT1M&agg=MAX");
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
        TooLarge(int max) {
            super("request body: larger than " + max + " bytes; send at most " + max + " bytes");
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
            throw new TooLarge(MAX_DAG_BYTES);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        JsonNode dag;
        try {
            dag = YAML.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the DAG is not valid YAML: " + e.getOriginalMessage(), e);
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

    /** The query parameters of {@code GET /v1/episodes}; {@code tag.<key>} stands for every parameter with that prefix. */
    private static final String[] EPISODE_PARAMETERS =
            {"metric", "tag.<key>", "series", "from", "to", "limit", "clip", "resample", "agg"};

    /** The query parameters; one the route does not take, also one without {@code =}, is a 400 that names it. */
    private static Map<String, String> query(HttpExchange ex, String... allowed) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                if (kv.isEmpty()) {
                    continue;
                }
                int i = kv.indexOf('=');
                String name = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                boolean tag = name.startsWith("tag.") && Arrays.asList(allowed).contains("tag.<key>");
                if (!tag && !Arrays.asList(allowed).contains(name)) {
                    throw new IllegalArgumentException(name + ": unknown query parameter; expected one of " + String.join(", ", allowed));
                }
                q.put(name, i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            throw new TooLarge(MAX_BODY_BYTES);
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
            case 409 -> "conflict";
            case 413 -> "too_large";
            case 500 -> "internal";
            case 503 -> "unavailable";
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
            String keys = query(ex, "keys").getOrDefault("keys", "");
            if (keys.isBlank()) {
                throw new IllegalArgumentException("name the series: ?keys=atom.port,atom.port");
            }
            json(ex, 200, runs.series(system, p[3], List.of(keys.split(","))));
        } else if (p.length == 5 && p[4].equals("compare")) {
            json(ex, 200, runs.compare(system, p[3], query(ex, "base").getOrDefault("base", runs.reference(system))));
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

    static final String COMMAND = "retrieval-server";
    static final String SOURCE = "opentsx.retrieval.source";
    static final String ICEBERG = "opentsx.iceberg.properties";
    static final String NAMESPACE = "opentsx.iceberg.namespace";
    static final String BOOTSTRAP = "opentsx.kafscale.bootstrap";
    static final String DOMAIN = "opentsx.domain";
    static final String PORT = "opentsx.retrieval.port";
    static final String BIND = "opentsx.retrieval.bind";
    static final String DAG_RUNS = "opentsx.retrieval.dagRuns";
    static final String OPEN_DAG_RUNS = "opentsx.retrieval.openDagRuns";
    static final String RUN_TOKEN = "opentsx.retrieval.dagRunToken";
    static final String REFRESH = "opentsx.retrieval.refreshInterval";
    static final String MAX_EPISODES = "opentsx.retrieval.maxEpisodes";
    static final String SYSTEMS = "opentsx.retrieval.systems";
    static final String SYSTEMS_ROOT = "opentsx.retrieval.systemsRoot";
    static final String SCENARIO = "opentsx.windpark.scenario";
    static final String CATALOG = "opentsx.windpark.catalog";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /**
     * A checked start: the store, what the inventory hands out and the address to listen on; nothing is open yet.
     * {@code iceberg} is null unless the source is iceberg, {@code bootstrap} null unless it is kafscale, and
     * {@code runToken} null when there is none.
     */
    record Plan(String source, Map<String, String> iceberg, String namespace, String bootstrap, String domain,
                Duration refresh, int maxEpisodes, boolean dagRuns, String runToken, JsonNode scenario, JsonNode catalog,
                Systems systems, InetSocketAddress address) {
    }

    /**
     * The command {@code retrieval-server}. It reads the keys of {@code retrieval-server} in the registry
     * {@code opentsx.config.v1} through {@link OpenTsxConf#load}; {@code bin/opentsx retrieval-server --help} lists
     * them. {@code opentsx.retrieval.source} chooses the store: the demo store holds three deterministic synthetic
     * series (6 h at 1 Hz, 10-minute episodes) with a planted pattern, used by the Python client tests in CI; with
     * kafscale the server reads the episodes topic {@code tsx.<domain>.episodes.v2} into memory and follows it, and it
     * reads the vector topics of the domain. {@code opentsx.retrieval.dagRuns} offers {@code POST /v1/dags/deploy} and
     * {@code /stop}, which write to the control topic of the vector processor; they need a run token in the header
     * {@code X-OpenTSx-Run-Token}, or {@code opentsx.retrieval.openDagRuns}, which accepts runs without a token and
     * listens on 127.0.0.1 only. Exit codes: 0 when the server listens or help was printed, 1 runtime failure, 2 usage
     * or configuration error.
     */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(List.of(args), System.getenv(), out, err,
                server -> Runtime.getRuntime().addShutdownHook(shutdownHook(server)));
        if (code != 0) {
            System.exit(code);
        }
    }

    /** {@code args} without the command word, as bin/opentsx passes them; {@code started} gets the listening server. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                   java.util.function.Consumer<RetrievalServer> started) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        OpenTsxConf.Loaded loaded;
        Plan plan;
        try {
            loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                out.print(Registry.shipped().help(COMMAND));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            plan = check(loaded.conf());
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(RetrievalServer.class).debug("stack trace of the failed start", e);
            err.print("opentsx: " + failure(e) + "\n");
            err.flush();
            return 1;
        }
        Logger log = LoggerFactory.getLogger(RetrievalServer.class);
        log.info(loaded.startLine());
        try {
            RetrievalServer server = start(plan, log, KafScaleFeed::start);
            String host = server.address().getAddress().getHostAddress();
            int port = server.port();
            // main adds the shutdown hook here, so a SIGTERM after the line below always finds it
            started.accept(server);
            log.info("listening on {}:{}, source {}", host, port, plan.source());
            return 0;
        } catch (IOException | RuntimeException e) {
            log.debug("stack trace of the failed start", e);
            err.print("opentsx: " + failure(e) + "\n");
            err.flush();
            return 1;
        }
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        // PrintStream never throws; without this check a full disk or a closed stdout would exit 0.
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /**
     * Checks every value and reads the scenario, the catalog and the system documents; opens no store, no client and
     * no port. With {@code auto}, a source is chosen by what is set on the command line, so a value from the
     * environment or a file does not switch it.
     */
    static Plan check(OpenTsxConf conf) {
        String source = conf.get(SOURCE);
        if (source.equals("auto")) {
            boolean iceberg = "commandLine".equals(conf.source(ICEBERG));
            boolean kafscale = "commandLine".equals(conf.source(BOOTSTRAP));
            if (iceberg && kafscale) {
                throw new OpenTsxConfException("--iceberg", "given together with --kafscale",
                        "give one of them, or choose with --source iceberg or --source kafscale");
            }
            source = iceberg ? "iceberg" : kafscale ? "kafscale" : "demo";
        }
        Map<String, String> iceberg = source.equals("iceberg") ? icebergProperties(conf) : null;
        String bootstrap = source.equals("kafscale") ? conf.get(BOOTSTRAP) : null;
        String domain = conf.get(DOMAIN);
        if (bootstrap != null) {
            new TopicLayout(domain);    // refuses a domain that cannot be part of a topic name
        }
        int maxEpisodes = conf.getInt(MAX_EPISODES);
        if (maxEpisodes < 1) {
            throw new OpenTsxConfException(MAX_EPISODES, "\"" + maxEpisodes + "\" is not allowed", "expected 1 or more");
        }
        boolean dagRuns = conf.getBoolean(DAG_RUNS);
        boolean open = conf.getBoolean(OPEN_DAG_RUNS);
        String runToken = conf.getOption(RUN_TOKEN).orElse(null);
        if (dagRuns && bootstrap == null) {
            throw new OpenTsxConfException(DAG_RUNS, "true needs the kafscale source",
                    "give --kafscale <host:port> with --source auto or --source kafscale, or leave --dag-runs off");
        }
        if (dagRuns && runToken == null && !open) {
            throw new OpenTsxConfException(DAG_RUNS, "true needs a run token", "set "
                    + String.join(" or ", conf.registry().entry(RUN_TOKEN, RUN_TOKEN).envNames())
                    + ", or give --open-dag-runs to accept runs without a token on 127.0.0.1 only");
        }
        InetSocketAddress address = address(conf, open);
        JsonNode scenario = yaml(conf.getOption(SCENARIO).orElse(null));
        JsonNode catalog = yaml(conf.getOption(CATALOG).orElse(null));
        Systems systems = conf.getOption(SYSTEMS)
                .map(dir -> Systems.load(java.nio.file.Path.of(dir), java.nio.file.Path.of(conf.get(SYSTEMS_ROOT))))
                .orElse(null);
        return new Plan(source, iceberg, conf.get(NAMESPACE), bootstrap, domain, conf.getDuration(REFRESH), maxEpisodes,
                dagRuns, runToken, scenario, catalog, systems, address);
    }

    /** Every interface by default; 127.0.0.1 with open DAG runs, which refuse any address that is not a loopback one. */
    private static InetSocketAddress address(OpenTsxConf conf, boolean open) {
        int port = conf.getInt(PORT);
        if (port < 0 || port > 65535) {
            throw new OpenTsxConfException(PORT, "\"" + port + "\" is not allowed", "expected 0 to 65535, 0 for any free port");
        }
        String bind = conf.getOption(BIND).orElse(null);
        if (bind == null) {
            return open ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port) : new InetSocketAddress(port);
        }
        InetAddress host;
        try {
            host = InetAddress.getByName(bind);
        } catch (UnknownHostException e) {
            throw new OpenTsxConfException(BIND, "\"" + bind + "\" is not an address", "expected an address of this host such as 127.0.0.1");
        }
        if (open && !host.isLoopbackAddress()) {
            throw new OpenTsxConfException(BIND, "\"" + bind + "\" is not a loopback address",
                    "with opentsx.retrieval.openDagRuns the server listens on 127.0.0.1 only; leave --bind unset or give 127.0.0.1");
        }
        return new InetSocketAddress(host, port);
    }

    /** Every item is key=value. */
    private static Map<String, String> icebergProperties(OpenTsxConf conf) {
        String subject = "commandLine".equals(conf.source(ICEBERG)) ? "--iceberg" : ICEBERG;
        Map<String, String> props = new LinkedHashMap<>();
        for (String item : conf.getList(ICEBERG)) {
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw new OpenTsxConfException(subject, "item \"" + item + "\" is not key=value",
                        "expected catalog properties such as type=rest,uri=http://localhost:8181,warehouse=s3://lake/");
            }
            String key = item.substring(0, eq).trim();
            if (props.put(key, item.substring(eq + 1).trim()) != null) {
                throw new OpenTsxConfException(subject, "sets " + key + " twice", "set each property once");
            }
        }
        return props;
    }

    /** Opens the feed of a server on KafScale: {@link KafScaleFeed#start} outside tests. */
    interface Feeds {
        KafScaleFeed open(String bootstrap, String domain, Duration refresh, InMemorySeriesStore store, int maxEpisodes,
                          Runnable onChange);
    }

    /** Opens the store of a checked plan and starts the server on it. */
    static RetrievalServer start(Plan plan, Logger log, Feeds feeds) throws IOException {
        if (plan.systems() != null) {
            List<String> withProblems = new ArrayList<>();
            int problems = 0;
            for (org.opentsx.retrieval.system.SystemDoc d : plan.systems().docs()) {
                log.info("system loaded system={} signals={} problems={}", d.system(), d.signals().size(), d.problems().size());
                d.problems().forEach(pr -> log.debug("system problem system={} problem={}", d.system(), pr));
                if (!d.problems().isEmpty()) {
                    withProblems.add(d.system());
                    problems += d.problems().size();
                }
            }
            if (problems > 0) {
                log.warn("system documents have problems; GET /v1/systems/<id>/signals lists them systems={} problems={}",
                        String.join(",", withProblems), problems);
            }
        }
        SeriesStore store;
        SeriesInventory inventory;
        Dags dags = null;
        String[] generated = {null};
        Systems.Writer[] writer = {null};
        KafScaleFeed[] opened = {null};
        if (plan.source().equals("kafscale")) {
            String domain = plan.domain();
            boolean dagRuns = plan.dagRuns();
            String runToken = plan.runToken();
            InMemorySeriesStore episodes = new InMemorySeriesStore();
            Runnable[] invalidate = {() -> { }};
            KafScaleFeed feed = feeds.open(plan.bootstrap(), domain, plan.refresh(), episodes, plan.maxEpisodes(),
                    () -> invalidate[0].run());
            opened[0] = feed;
            // episodes of the domain plus the components of its vector topics
            store = new CompositeSeriesStore(episodes, feed::vectorStore);
            // the feed invalidates the inventory whenever something was appended, so the cache may live long
            inventory = new SeriesInventory(store, new SeriesInventory.Source("kafscale", domain, KafScaleFeed.topics(domain)),
                    plan.scenario(), plan.catalog(), Duration.ofMinutes(10));
            invalidate[0] = inventory::invalidate;
            inventory.truncatedWhen(feed::truncated);
            inventory.sourceDetails(src -> {
                src.put("dagRuns", dagRuns);
                if (dagRuns) {
                    src.put("dagRunAuth", runToken == null ? "none" : "token");
                }
                feed.details(src);
            });
            dags = kafscaleDags(feed, () -> KafScaleClients.consumer(plan.bootstrap()), new TopicLayout(domain));
            if (dagRuns) {
                generated[0] = new TopicLayout(domain).synthetic();
                writer[0] = feed::writeGenerated;
            }
        } else {
            store = plan.source().equals("iceberg") ? openIceberg(plan.iceberg(), plan.namespace()) : demoStore();
            inventory = new SeriesInventory(store, SeriesInventory.Source.of(plan.source()), plan.scenario(), plan.catalog(),
                    Duration.ofSeconds(60));
        }
        RetrievalServer s;
        try {
            s = new RetrievalServer(new EpisodeService(store), plan.address(), inventory, dags, plan.dagRuns(),
                    plan.runToken(), plan.systems());
        } catch (IOException e) {
            throw new IOException("cannot listen on " + plan.address().getAddress().getHostAddress() + ":"
                    + plan.address().getPort(), e);
        }
        if (writer[0] != null) {
            s.generatedSeries(generated[0], writer[0]);
        }
        if (opened[0] != null) {
            s.closes(opened[0]);
            s.follows(opened[0], Clock.systemUTC());
        }
        if (plan.dagRuns() && plan.runToken() == null) {
            log.warn("dag deployments: OPEN on {}:{}, any process that reaches this address can deploy and stop DAGs"
                    + " (opentsx.retrieval.openDagRuns); set OPENTSX_DAG_RUN_TOKEN to require a token",
                    s.address().getAddress().getHostAddress(), s.port());
        } else if (plan.dagRuns()) {
            log.info("dag deployments: for callers with the run token");
        }
        return s;
    }

    /**
     * The DAG routes of a server on KafScale. A deployment is refused with {@link Conflict} when another DAG that the
     * vector processor runs writes one of its vector topics or one of the signals of its series sinks
     * ({@link VectorProcessor#sinkConflict}); the control topic is read to its end and decided as the processor decides
     * it ({@link VectorProcessor#deployedWrites}), so the refusal holds also for DAGs deployed from the command line.
     *
     * @param consumers a new consumer for each deployment, closed after it read the control topic
     */
    static Dags kafscaleDags(KafScaleFeed feed, Supplier<Consumer<byte[], byte[]>> consumers, TopicLayout layout) {
        return new Dags() {
            @Override
            public synchronized JsonNode deploy(JsonNode dag, String text, String by) {
                String name = VectorProcessor.dagName(text);
                DagPlan.of(dag, layout, "");
                Map<String, Set<String>> written;
                try (Consumer<byte[], byte[]> consumer = consumers.get()) {
                    written = VectorProcessor.deployedWrites(consumer, layout);
                } catch (KafkaException | IllegalStateException e) {
                    throw new Unavailable(KafScaleFeed.BROKER_UNAVAILABLE, new IllegalStateException("cannot read " + layout.dags(), e));
                }
                VectorProcessor.sinkConflict(name, dag, written).ifPresent(conflict -> {
                    throw new Conflict(conflict);
                });
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
    }

    /** {@code IcebergSeriesStore.openExisting}, called by name: opentsx-store-iceberg is on the class path of the launcher only. */
    private static SeriesStore openIceberg(Map<String, String> props, String namespace) {
        java.lang.reflect.Method open;
        try {
            open = Class.forName("org.opentsx.store.iceberg.IcebergSeriesStore").getMethod("openExisting", Map.class, String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException("org.opentsx.store.iceberg.IcebergSeriesStore.openExisting is not on the class path;"
                    + " start the server with bin/opentsx retrieval-server, which adds opentsx-store-iceberg", e);
        }
        try {
            return (SeriesStore) open.invoke(null, props, namespace);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException("cannot open the Iceberg catalog: " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot call " + open, e);
        }
    }

    /** The message and its causes on one line, for the command line. */
    static String failure(Throwable t) {
        String head = t.getMessage() == null ? t.toString() : t.getMessage();
        return oneLine(t.getCause() == null ? head : head + "; caused by " + causes(t.getCause()));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper YAML =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    /** Reads a YAML (or JSON) file as a tree; null when no file is given. */
    private static JsonNode yaml(String file) {
        if (file == null) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Path.of(file);
        if (!java.nio.file.Files.isRegularFile(p)) {
            throw new OpenTsxConfException(file, "no such file", "give an existing file");
        }
        try {
            return YAML.readTree(p.toFile());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new OpenTsxConfException(file, "not valid YAML", String.valueOf(e.getOriginalMessage()).lines().findFirst().orElse(""));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(file + ": cannot read the file", e);
        }
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
