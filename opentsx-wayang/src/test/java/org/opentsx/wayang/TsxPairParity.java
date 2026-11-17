package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.wayang.ops.TsxPairEs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TsxPairParity {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static double[] numbers(JsonNode input) {
        double[] result = new double[input.size()];
        for (int i = 0; i < result.length; i++) result[i] = input.get(i).doubleValue();
        return result;
    }

    private static Map<String, Object> params(JsonNode c, JsonNode root, String method) {
        Map<String, Object> result = new HashMap<>();
        result.put("every", 1);
        if (method.equals("pair.xcorr")) {
            result.put("lags", c.path("lags").intValue());
            if (!c.path("window").isNull()) result.put("window", c.path("window").doubleValue());
            if (!c.path("step").isNull()) result.put("step", c.path("step").doubleValue());
        } else {
            result.put("sigma", c.path("sigma").doubleValue());
            result.put("shuffles", root.path("shuffles").intValue());
            result.put("seed", root.path("seed").longValue());
            if (!c.path("tauMax").isNull()) result.put("tauMax", c.path("tauMax").doubleValue());
            assertArrayEquals(numbers(c.path("eventsA")), TsxPairEs.detectEvents(numbers(c.path("a")), c.path("sigma").doubleValue()));
            assertArrayEquals(numbers(c.path("eventsB")), TsxPairEs.detectEvents(numbers(c.path("b")), c.path("sigma").doubleValue()));
        }
        return result;
    }

    private static Episode input(SeriesKey key, double[] values, int window, long spacing) {
        return Episodes.regular(key, window * spacing, 1_000_000, values)
                .episodeId("parity-" + key.getSeriesId() + "-" + window).createdAt(0).build();
    }

    static JsonNode nativeCase(JsonNode c, JsonNode root, String method, int chunk, boolean reverse) throws Exception {
        Map<String, Object> params = params(c, root, method);
        long spacing = (Math.max(c.path("a").size(), c.path("b").size()) + 60L) * 1_000_000;
        List<TsxTagged> input = new ArrayList<>();
        for (String side : reverse ? List.of(TsxPair.B, TsxPair.A) : List.of(TsxPair.A, TsxPair.B)) {
            SeriesKey key = side.equals(TsxPair.A) ? TsxPairVectorsTest.A : TsxPairVectorsTest.B;
            double[] values = numbers(c.path(side));
            for (int window = 0; window < 8; window++) input.add(new TsxTagged(side, input(key, values, window, spacing)));
        }
        TsxState state = new TsxState(Map.of());
        List<SpecificRecord> output = new ArrayList<>();
        for (int from = 0; from < input.size(); from += chunk) {
            CollectionSource<TsxTagged> source = new CollectionSource<>(
                    input.subList(from, Math.min(input.size(), from + chunk)), TsxTagged.class);
            TsxPair operator = new TsxPair("pair", method, params, Set.of(TsxPairVectorsTest.A.getSeriesId()),
                    Set.of(TsxPairVectorsTest.B.getSeriesId()), "b".repeat(64), state);
            LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(output, SpecificRecord.class);
            source.connectTo(0, operator, 0);
            operator.connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
            state = TsxCleanStatsParity.restore(state);
        }
        List<Episode> episodes = output.stream().map(Episode.class::cast).toList();
        assertEquals(8 * (method.equals("pair.es") ? 4 : 3), episodes.size());
        for (int window = 0; window < 8; window++) {
            String id = "parity-" + TsxPairVectorsTest.A.getSeriesId() + "-" + window;
            List<Episode> group = episodes.stream().filter(e -> e.getLabels().get("pair.a").equals(id)).toList();
            assertEquals(method.equals("pair.es") ? 4 : 3, group.size());
            Map<String, Episode> fields = new HashMap<>();
            group.forEach(e -> fields.put(e.getSeries().getMetric().substring(method.length() + 1), e));
            if (method.equals("pair.es")) {
                c.path("expect").fields().forEachRemaining(e -> {
                    assertEquals(1, Episodes.values(fields.get(e.getKey())).length);
                    close(e.getValue().doubleValue(), Episodes.values(fields.get(e.getKey()))[0]);
                });
            } else {
                checkXcorr(c, fields, window * spacing);
            }
        }
        ArrayNode result = JSON.createArrayNode();
        OpenTsxAvro avro = new OpenTsxAvro();
        for (Episode episode : episodes.stream().sorted(Comparator.comparing(Episode::getEpisodeId)).toList()) {
            Episode stable = SpecificData.get().deepCopy(Episode.getClassSchema(), episode);
            stable.getProvenance().setCreatedAt(Instant.EPOCH);
            result.addObject().put("id", stable.getEpisodeId())
                    .put("hex", HexFormat.of().formatHex(avro.encode(JavaTsxTopicSink.canonical(stable))));
        }
        return result;
    }

    private static void close(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < 5e-13, () -> "expected " + expected + ", actual " + actual);
    }

    private static void checkXcorr(JsonNode c, Map<String, Episode> fields, long shift) {
        assertEquals(Set.of("max", "lag", "strength"), fields.keySet());
        double[] maximum = Episodes.values(fields.get("max"));
        double[] lag = Episodes.values(fields.get("lag"));
        double[] strength = Episodes.values(fields.get("strength"));
        long[] times = Episodes.timestampsMicros(fields.get("max"));
        JsonNode windows = c.has("windows") ? c.path("windows") : c.path("sliding");
        assertEquals(windows.size() + (c.has("windows") ? 0 : 1), maximum.length);
        for (int i = 0; i < windows.size(); i++) {
            JsonNode window = windows.get(i);
            assertEquals(shift + Math.round(window.path("centre").doubleValue() * 1_000_000), times[i]);
            if (c.has("windows")) {
                double[] cc = numbers(window.path("cc"));
                int hi = 0;
                int lo = 0;
                for (int j = 1; j < cc.length; j++) {
                    if (cc[j] > cc[hi]) hi = j;
                    if (cc[j] < cc[lo]) lo = j;
                }
                int peak = Math.abs(cc[hi]) < Math.abs(cc[lo]) ? lo : hi;
                close(cc[peak], maximum[i]);
                close(c.path("lags").intValue() - peak, lag[i]);
                close(window.path("strength").doubleValue(), strength[i]);
            } else {
                close(window.path("cc").doubleValue(), maximum[i]);
                assertEquals(0, lag[i]);
            }
        }
    }

    static JsonNode eventListsCase(JsonNode c, JsonNode root, int chunk, boolean reverse) {
        List<String> input = new ArrayList<>();
        for (String series : reverse ? List.of("second", "first") : List.of("first", "second")) {
            for (int window = 0; window < 8; window++) input.add(series + ":" + window);
        }
        Map<String, ObjectNode> output = new java.util.TreeMap<>();
        for (int from = 0; from < input.size(); from += chunk) {
            for (String id : input.subList(from, Math.min(input.size(), from + chunk))) {
                double[] a = numbers(c.path("eventsA"));
                double[] b = numbers(c.path("eventsB"));
                Double tauMax = c.path("tauMax").isNull() ? null : c.path("tauMax").doubleValue();
                TsxPairEs.Sync sync = TsxPairEs.eventSync(a, b, tauMax);
                ObjectNode record = JSON.createObjectNode().put("atomicInput", id).put("q_total", sync.qTotal())
                        .put("q_ab", sync.qAb()).put("q_ba", sync.qBa()).put("q_rand",
                                TsxPairEs.surrogateSync(a, b, root.path("shuffles").intValue(), root.path("seed").longValue(), tauMax));
                c.path("expect").fields().forEachRemaining(e -> close(e.getValue().doubleValue(), record.path(e.getKey()).doubleValue()));
                assertEquals(null, output.put(id, record));
            }
        }
        ArrayNode result = JSON.createArrayNode();
        output.values().forEach(result::add);
        assertEquals(16, result.size());
        return result;
    }
}
