package org.opentsx.store.opentsdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.store.Capability;
import org.opentsx.store.EpisodeOps;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.StoreCapabilities;
import org.opentsx.store.WriteResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link SeriesStore} on OpenTSDB 2.x via its HTTP API.
 *
 * <ul>
 *   <li>Write: episodes are split into data points ({@code POST /api/put}, batched). OpenTSDB stores milliseconds
 *   and no NaN: timestamps are truncated to ms, NaN points are dropped and counted. The unit travels as tag
 *   {@code _unit}.</li>
 *   <li>Read: {@code POST /api/query} with aggregator {@code none} and ms resolution, one reconstructed episode per
 *   series and query (REGULAR if equally spaced). Episode ids, labels and provenance are not stored.</li>
 *   <li>Selectors without a metric enumerate metrics via {@code /api/suggest}.</li>
 * </ul>
 */
public final class OpenTsdbSeriesStore implements SeriesStore {

    private static final int PUT_BATCH = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final HttpClient http;
    private final Duration timeout;

    public OpenTsdbSeriesStore(URI base) {
        this(base, HttpClient.newHttpClient(), Duration.ofSeconds(30));
    }

    public OpenTsdbSeriesStore(URI base, HttpClient http, Duration timeout) {
        this.base = base;
        this.http = http;
        this.timeout = timeout;
    }

    @Override
    public StoreCapabilities capabilities() {
        return StoreCapabilities.of(Capability.RAW_POINTS, Capability.DOWNSAMPLING);
    }

    @Override
    public WriteResult write(Collection<Episode> episodes) {
        ArrayNode batch = JSON.createArrayNode();
        long dropped = 0;
        int written = 0;
        for (Episode e : episodes) {
            SeriesKey k = e.getSeries();
            OpenTsdbNames.validate(k.getMetric(), k.getTags(), k.getUnit() != null);
            Map<String, String> tags = new LinkedHashMap<>(k.getTags());
            if (k.getUnit() != null) {
                tags.put(OpenTsdbNames.UNIT_TAG, sanitizeUnit(k.getUnit()));
            }
            long[] ts = Episodes.timestampsMicros(e);
            double[] v = Episodes.values(e);
            for (int i = 0; i < ts.length; i++) {
                if (Double.isNaN(v[i]) || Double.isInfinite(v[i])) {
                    dropped++;
                    continue;
                }
                ObjectNode p = batch.addObject();
                p.put("metric", k.getMetric());
                p.put("timestamp", Math.floorDiv(ts[i], 1_000L));
                p.put("value", v[i]);
                ObjectNode t = p.putObject("tags");
                tags.forEach(t::put);
                if (batch.size() >= PUT_BATCH) {
                    put(batch);
                    batch = JSON.createArrayNode();
                }
            }
            written++;
        }
        if (!batch.isEmpty()) {
            put(batch);
        }
        return new WriteResult(written, 0, dropped);
    }

    @Override
    public List<Episode> read(EpisodeQuery q) {
        SeriesSelector s = q.selector();
        List<String> metrics = s.metric() != null ? List.of(s.metric()) : metrics();
        long startMs = Math.floorDiv(q.fromMicros(), 1_000L);
        long endMs = Math.floorDiv(q.toMicros() - 1, 1_000L); // OpenTSDB end is inclusive
        List<Episode> out = new ArrayList<>();
        for (String metric : metrics) {
            ObjectNode body = JSON.createObjectNode();
            body.put("start", startMs);
            body.put("end", endMs);
            body.put("msResolution", true);
            ObjectNode sub = body.putArray("queries").addObject();
            sub.put("aggregator", "none");
            sub.put("metric", metric);
            ObjectNode tags = sub.putObject("tags");
            s.tags().forEach(tags::put);
            JsonNode result = post("/api/query", body, true);
            if (result == null) {
                continue; // OpenTSDB answers 400 "No such name" for unknown metrics
            }
            for (JsonNode series : result) {
                Episode e = toEpisode(series, q.fromMicros(), q.toMicros());
                if (e != null && s.matches(e.getSeries())) {
                    out.add(e);
                }
            }
        }
        out.sort(EpisodeOps.READ_ORDER);
        return out.size() > q.limit() ? new ArrayList<>(out.subList(0, q.limit())) : out;
    }

    @Override
    public List<SeriesKey> listSeries(SeriesSelector selector) {
        List<SeriesKey> out = new ArrayList<>();
        for (String metric : selector.metric() != null ? List.of(selector.metric()) : metrics()) {
            ObjectNode body = JSON.createObjectNode();
            body.put("metric", metric);
            body.putArray("tags");
            JsonNode r = post("/api/search/lookup", body, true);
            if (r == null) {
                continue;
            }
            for (JsonNode hit : r.path("results")) {
                SeriesKey k = key(metric, hit.path("tags"));
                if (selector.matches(k)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    private Episode toEpisode(JsonNode series, long fromMicros, long toMicros) {
        SeriesKey key = key(series.path("metric").asText(), series.path("tags"));
        TreeMap<Long, Double> points = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = series.path("dps").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> dp = it.next();
            long tsMicros = Long.parseLong(dp.getKey()) * 1_000L;
            if (tsMicros >= fromMicros && tsMicros < toMicros) {
                points.put(tsMicros, dp.getValue().asDouble());
            }
        }
        if (points.isEmpty()) {
            return null;
        }
        long[] ts = points.keySet().stream().mapToLong(Long::longValue).toArray();
        double[] v = points.values().stream().mapToDouble(Double::doubleValue).toArray();
        boolean regular = ts.length >= 2;
        for (int i = 2; regular && i < ts.length; i++) {
            regular = ts[i] - ts[i - 1] == ts[1] - ts[0];
        }
        Episodes.Draft d = regular ? Episodes.regular(key, ts[0], ts[1] - ts[0], v) : Episodes.irregular(key, ts, v);
        return d.source("opentsdb:" + base.getHost()).producer("opentsx-store-opentsdb-v2/3.0.0").build();
    }

    private static SeriesKey key(String metric, JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        String unit = null;
        Iterator<Map.Entry<String, JsonNode>> it = tagsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> t = it.next();
            if (OpenTsdbNames.UNIT_TAG.equals(t.getKey())) {
                unit = t.getValue().asText();
            } else {
                tags.put(t.getKey(), t.getValue().asText());
            }
        }
        return SeriesKeys.of(metric, tags, unit);
    }

    /** OpenTSDB tag values cannot hold every UCUM character; units like m/s, kW, Cel are fine. */
    private static String sanitizeUnit(String unit) {
        return unit.replaceAll("[^\\p{L}\\p{N}\\-_./]", "_");
    }

    private List<String> metrics() {
        JsonNode r = get("/api/suggest?type=metrics&max=10000&q=" + URLEncoder.encode("", StandardCharsets.UTF_8));
        List<String> out = new ArrayList<>();
        r.forEach(n -> out.add(n.asText()));
        return out;
    }

    private void put(ArrayNode batch) {
        JsonNode r = post("/api/put?summary", batch, false);
        if (r != null && r.path("failed").asInt(0) > 0) {
            throw new IllegalStateException("OpenTSDB rejected " + r.path("failed").asInt() + " data points: " + r);
        }
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).GET().build(), false);
    }

    private JsonNode post(String path, JsonNode body, boolean nullOn400) {
        try {
            return send(HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), nullOn400);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode send(HttpRequest request, boolean nullOn400) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 400 && nullOn400) {
                return null;
            }
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("OpenTSDB " + request.uri().getPath() + " returned " + r.statusCode() + ": " + r.body());
            }
            return r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
