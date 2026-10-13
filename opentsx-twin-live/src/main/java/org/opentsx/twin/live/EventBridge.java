package org.opentsx.twin.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Hands an event topic to a browser as server-sent events (JDK HTTP server, no framework).
 *
 * <pre>
 * GET /v1/topics/{topic}/events?from=latest|earliest|&lt;offset&gt;     text/event-stream, one message per record
 * GET /v1/health
 * </pre>
 * A message carries the offset as its id and {@code {"topic","offset","timestamp","key","value"}} as data, where
 * {@code value} is the JSON of the record. A browser that reconnects sends {@code Last-Event-ID}; the stream then
 * continues behind that offset. Only event topics of twins ({@link EventTopic#isEventTopic}) are served, read-only.
 * Every stream holds one reader on the log, so their number is limited.
 */
public final class EventBridge implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(500);
    private static final long KEEPALIVE_MS = 15_000;

    private final Topics topics;
    private final HttpServer http;
    private final String allowOrigin;
    private final Semaphore streams;
    private volatile boolean closed;

    public EventBridge(Topics topics, int port, String allowOrigin, int maxStreams) throws IOException {
        this.topics = topics;
        this.allowOrigin = allowOrigin;
        this.streams = new Semaphore(maxStreams);
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "event-bridge");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/v1/", this::handle);
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        closed = true;
        http.stop(0);
        ((ExecutorService) http.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", allowOrigin);
            String path = ex.getRequestURI().getPath();
            if (!ex.getRequestMethod().equals("GET")) {
                error(ex, 405, "only GET");
            } else if (path.equals("/v1/health")) {
                json(ex, 200, EventTopic.JSON.createObjectNode().put("status", "ok"));
            } else if (path.startsWith("/v1/topics/") && path.endsWith("/events")) {
                String topic = path.substring("/v1/topics/".length(), path.length() - "/events".length());
                if (!EventTopic.isEventTopic(topic)) {
                    error(ex, 400, "not an event topic of a twin: " + topic);
                } else {
                    stream(ex, topic);
                }
            } else {
                error(ex, 404, "no such endpoint");
            }
        } catch (IllegalArgumentException e) {
            error(ex, 400, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            error(ex, 502, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            ex.close();
        }
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
            }
            try (cursor) {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.getResponseHeaders().set("X-Accel-Buffering", "no");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                write(out, "retry: 3000\n: connected to " + topic + "\n\n");
                long lastWrite = System.currentTimeMillis();
                while (!closed) {
                    var recs = cursor.poll(POLL);
                    for (Topics.Rec r : recs) {
                        write(out, "id: " + r.offset() + "\ndata: " + EventTopic.JSON.writeValueAsString(data(topic, r)) + "\n\n");
                    }
                    if (!recs.isEmpty()) {
                        lastWrite = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - lastWrite > KEEPALIVE_MS) {
                        write(out, ": keepalive\n\n");
                        lastWrite = System.currentTimeMillis();
                    }
                }
            } catch (IOException gone) {
                // the browser closed the stream
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

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
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
        json(ex, status, EventTopic.JSON.createObjectNode().put("error", message));
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = EventTopic.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}
