package org.opentsx.twin.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.opentsx.kafka.TopicLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Hands an event topic to a browser as server-sent events (JDK HTTP server, no framework).
 *
 * <pre>
 * GET /v1/topics/{topic}/events?from=latest|earliest|&lt;offset&gt;     text/event-stream, one message per record
 * GET /v1/health
 * </pre>
 * A message carries the offset as its id and {@code {"topic","offset","timestamp","key","value"}} as data, where
 * {@code value} is the JSON of the record. A browser that reconnects sends {@code Last-Event-ID}; the stream then
 * continues behind that offset. Only event topics of twins ({@link TopicLayout#isTwinEvents}) are served, read-only.
 * Every stream holds one reader on the log, so their number is limited. {@link #close} stops the server and waits up to
 * {@link #CLOSE_WAIT} for the open streams to close their readers.
 */
public final class EventBridge implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBridge.class);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final long KEEPALIVE_MS = 15_000;
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final Topics topics;
    private final HttpServer http;
    private final String allowOrigin;
    private final int maxStreams;
    private final Semaphore streams;
    private volatile boolean closed;

    public EventBridge(Topics topics, int port, String allowOrigin, int maxStreams) throws IOException {
        this.topics = topics;
        this.allowOrigin = allowOrigin;
        this.maxStreams = maxStreams;
        this.streams = new Semaphore(maxStreams);
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "event-bridge");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", this::handle);
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        int port = port();
        http.stop(0);
        ((ExecutorService) http.getExecutor()).shutdownNow();
        boolean ended;
        try {
            ended = streams.tryAcquire(maxStreams, CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ended = false;
        }
        if (ended) {
            LOG.info("event bridge stopped port={}", port);
        } else {
            LOG.warn("event bridge stopped with streams still open port={} open={} wait={}", port,
                    maxStreams - streams.availablePermits(), CLOSE_WAIT);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", allowOrigin);
            boolean health = path.equals("/v1/health");
            if (!health && !path.matches("/v1/topics/[^/]+/events")) {
                error(ex, 404, "no such endpoint");
            } else if (!ex.getRequestMethod().equals("GET")) {
                ex.getResponseHeaders().set("Allow", "GET");
                error(ex, 405, ex.getRequestMethod() + ": method not allowed; expected GET");
            } else if (health) {
                json(ex, 200, EventTopic.JSON.createObjectNode().put("status", "ok"));
            } else {
                String topic = path.substring("/v1/topics/".length(), path.length() - "/events".length());
                if (!TopicLayout.isTwinEvents(topic)) {
                    error(ex, 400, "not an event topic of a twin: " + topic);
                } else {
                    stream(ex, topic);
                }
            }
        } catch (IllegalArgumentException e) {
            error(ex, 400, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            failed(ex, path, 500, "internal error", e);
        } finally {
            ex.close();
        }
    }

    /** Logs the failure once, with its causes, and answers it. */
    private static void failed(HttpExchange ex, String path, int status, String text, RuntimeException e) throws IOException {
        LOG.error("request failed method={} path={} status={} cause={}", oneLine(ex.getRequestMethod()), oneLine(path), status,
                causes(e));
        LOG.debug("stack trace of the failed request method={} path={}", oneLine(ex.getRequestMethod()), oneLine(path), e);
        error(ex, status, text);
    }

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

    private void stream(HttpExchange ex, String topic) throws IOException {
        long from = start(ex);
        if (!streams.tryAcquire()) {
            error(ex, 503, "too many open streams");
            return;
        }
        try {
            Topics.Cursor cursor;
            try {
                cursor = topics.open(topic, from);
            } catch (Topics.NoSuchTopicException e) {
                error(ex, 404, e.getMessage());
                return;
            } catch (RuntimeException e) {
                failed(ex, ex.getRequestURI().getPath(), 502, "cannot reach the broker", e);
                return;
            }
            long bytes = 0;
            try (cursor) {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.getResponseHeaders().set("X-Accel-Buffering", "no");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                bytes += write(out, "retry: 3000\n: connected to " + topic + "\n\n");
                long lastWrite = System.currentTimeMillis();
                while (!closed) {
                    var recs = cursor.poll(POLL);
                    for (Topics.Rec r : recs) {
                        String json;
                        try {
                            json = EventTopic.JSON.writeValueAsString(data(topic, r));
                        } catch (JsonProcessingException e) {
                            LOG.warn("record skipped, it cannot be written as JSON topic={} offset={} cause={}",
                                    oneLine(topic), r.offset(), causes(e));
                            continue;
                        }
                        bytes += write(out, "id: " + r.offset() + "\ndata: " + json + "\n\n");
                    }
                    if (!recs.isEmpty()) {
                        lastWrite = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - lastWrite > KEEPALIVE_MS) {
                        bytes += write(out, ": keepalive\n\n");
                        lastWrite = System.currentTimeMillis();
                    }
                }
            } catch (IOException gone) {
                // the browser closed the stream
            } catch (RuntimeException e) {
                if (ex.getResponseCode() == -1) {
                    throw e;
                }
                // once the stream has started, closing it is the only answer left; the browser then reconnects
                if (closed) {
                    LOG.debug("stream ended by the stop of the bridge topic={} bytes={} cause={}", oneLine(topic), bytes,
                            causes(e));
                } else {
                    LOG.error("stream failed topic={} bytes={} cause={}", oneLine(topic), bytes, causes(e));
                    LOG.debug("stack trace of the failed stream topic={}", oneLine(topic), e);
                }
            }
        } finally {
            streams.release();
        }
    }

    /** Offset to start at: {@code Last-Event-ID} of a reconnecting browser wins over the {@code from} parameter. */
    private static long start(HttpExchange ex) {
        String last = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (last != null && last.matches("\\d{1,18}")) {
            return Long.parseLong(last) + 1;
        }
        String from = query(ex).getOrDefault("from", "latest");
        return switch (from) {
            case "latest" -> Topics.LATEST;
            case "earliest" -> Topics.EARLIEST;
            default -> {
                if (!from.matches("\\d{1,18}")) {
                    throw new IllegalArgumentException("from must be latest, earliest or an offset");
                }
                yield Long.parseLong(from);
            }
        };
    }

    private static ObjectNode data(String topic, Topics.Rec r) {
        ObjectNode n = EventTopic.JSON.createObjectNode();
        n.put("topic", topic);
        n.put("offset", r.offset());
        n.put("timestamp", r.timestampMs());
        n.put("key", r.key());
        try {
            JsonNode value = EventTopic.JSON.readTree(r.value());
            n.set("value", value);
        } catch (IOException notJson) {
            n.put("value", new String(r.value(), StandardCharsets.UTF_8));
        }
        return n;
    }

    /** Writes and flushes {@code s}; returns the number of bytes written. */
    private static int write(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b);
        out.flush();
        return b.length;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int i = part.indexOf('=');
                if (i > 0) {
                    q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return q;
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, EventTopic.JSON.createObjectNode().put("error", message).put("type", type(status)));
    }

    private static String type(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 500 -> "internal";
            case 502, 503 -> "unavailable";
            default -> throw new IllegalStateException("no error type for status " + status);
        };
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = EventTopic.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}
