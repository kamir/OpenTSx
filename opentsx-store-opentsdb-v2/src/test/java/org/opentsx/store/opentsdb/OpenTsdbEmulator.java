package org.opentsx.store.opentsdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal OpenTSDB 2.x HTTP emulator for tests: /api/put (rejects NaN, ms timestamps), /api/query with
 * aggregator "none" and exact tag filters, /api/suggest?type=metrics, /api/search/lookup.
 * Unknown metrics answer 400 like OpenTSDB ("No such name for 'metrics'").
 */
final class OpenTsdbEmulator implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** series ("metric{sorted tags}") -> (ms -> value) */
    private final Map<String, TreeMap<Long, Double>> data = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> tagsOf = new ConcurrentHashMap<>();
    private final Map<String, String> metricOf = new ConcurrentHashMap<>();
    private final HttpServer server;
    int putRequests;

    OpenTsdbEmulator() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/put", this::put);
        server.createContext("/api/query", this::query);
        server.createContext("/api/suggest", this::suggest);
        server.createContext("/api/search/lookup", this::lookup);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void put(HttpExchange ex) throws IOException {
        putRequests++;
        JsonNode body = JSON.readTree(ex.getRequestBody());
        int ok = 0;
        int failed = 0;
        for (JsonNode p : body.isArray() ? body : JSON.createArrayNode().add(body)) {
            double v = p.path("value").asDouble();
            if (Double.isNaN(v) || !p.path("value").isNumber()) {
                failed++;
                continue;
            }
            long ts = p.path("timestamp").asLong();
            long ms = ts < 10_000_000_000L ? ts * 1000 : ts; // seconds or milliseconds, like OpenTSDB
            Map<String, String> tags = new TreeMap<>();
            p.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
            String metric = p.path("metric").asText();
            String id = metric + tags;
            data.computeIfAbsent(id, k -> new TreeMap<>()).put(ms, v);
            tagsOf.put(id, tags);
            metricOf.put(id, metric);
            ok++;
        }
        ObjectNode r = JSON.createObjectNode();
        r.put("success", ok);
        r.put("failed", failed);
        respond(ex, failed > 0 ? 400 : 200, r);
    }

    private synchronized void query(HttpExchange ex) throws IOException {
        JsonNode body = JSON.readTree(ex.getRequestBody());
        long start = body.path("start").asLong();
        long end = body.path("end").asLong();
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode q : body.path("queries")) {
            String metric = q.path("metric").asText();
            if (!metricOf.containsValue(metric)) {
                respond(ex, 400, JSON.createObjectNode().put("error", "No such name for 'metrics': '" + metric + "'"));
                return;
            }
            for (Map.Entry<String, TreeMap<Long, Double>> s : data.entrySet()) {
                if (!metric.equals(metricOf.get(s.getKey())) || !matches(tagsOf.get(s.getKey()), q.path("tags"))) {
                    continue;
                }
                ObjectNode series = out.addObject();
                series.put("metric", metric);
                ObjectNode tags = series.putObject("tags");
                tagsOf.get(s.getKey()).forEach(tags::put);
                series.putArray("aggregateTags");
                ObjectNode dps = series.putObject("dps");
                s.getValue().subMap(start, true, end, true).forEach((ms, v) -> dps.put(Long.toString(ms), v));
            }
        }
        respond(ex, 200, out);
    }

    private synchronized void suggest(HttpExchange ex) throws IOException {
        ArrayNode out = JSON.createArrayNode();
        new java.util.TreeSet<>(metricOf.values()).forEach(out::add);
        respond(ex, 200, out);
    }

    private synchronized void lookup(HttpExchange ex) throws IOException {
        JsonNode body = JSON.readTree(ex.getRequestBody());
        String metric = body.path("metric").asText();
        ObjectNode r = JSON.createObjectNode();
        r.put("metric", metric);
        ArrayNode results = r.putArray("results");
        metricOf.forEach((id, m) -> {
            if (m.equals(metric)) {
                ObjectNode hit = results.addObject();
                hit.put("metric", m);
                ObjectNode tags = hit.putObject("tags");
                tagsOf.get(id).forEach(tags::put);
                hit.put("tsuid", Integer.toHexString(id.hashCode()));
            }
        });
        r.put("totalResults", results.size());
        respond(ex, 200, r);
    }

    private static boolean matches(Map<String, String> tags, JsonNode filter) {
        Iterator<Map.Entry<String, JsonNode>> it = filter.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!f.getValue().asText().equals(tags.get(f.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static void respond(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
