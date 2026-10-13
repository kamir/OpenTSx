package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the test vectors shared with the web workbench (opentsx-saas-frontend, vector.test.ts). */
class VectorAlignerTest {

    static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "align-vector.v1.json");
    static final ObjectMapper JSON = new ObjectMapper();

    static List<VectorAligner.Input> inputs(JsonNode c) {
        List<VectorAligner.Input> inputs = new ArrayList<>();
        for (JsonNode in : c.path("inputs")) {
            List<VectorAligner.Series> series = new ArrayList<>();
            for (JsonNode s : in.path("series")) {
                Map<String, String> tags = new TreeMap<>();
                s.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
                long[] t = new long[s.path("t").size()];
                double[] v = new double[t.length];
                for (int i = 0; i < t.length; i++) {
                    t[i] = s.path("t").get(i).asLong() * 1_000_000L;
                    v[i] = s.path("v").get(i).isNull() ? Double.NaN : s.path("v").get(i).asDouble();
                }
                series.add(new VectorAligner.Series(s.path("id").asText(), s.path("metric").asText(), tags, t, v));
            }
            inputs.add(new VectorAligner.Input(in.path("name").asText(), series));
        }
        return inputs;
    }

    static VectorAligner.Params params(JsonNode p, String agg) {
        return new VectorAligner.Params(Duration.ofSeconds(p.path("every").asLong()), p.path("key").asText(),
                VectorAligner.Agg.valueOf(agg), p.path("fill").asText().equals("as-of"),
                Duration.ofSeconds(p.path("maxAge").asLong()), p.path("emit").asText().equals("complete"));
    }

    /** A point in the notation of the test vectors. */
    static String text(Map<String, String> key, long fromSeconds, List<String> components, List<?> values) {
        return new TreeMap<>(key) + " @" + fromSeconds + " " + components + " = " + values;
    }

    static List<Double> doubles(JsonNode values) {
        List<Double> out = new ArrayList<>();
        values.forEach(v -> out.add(v.isNull() ? null : v.asDouble()));
        return out;
    }

    @TestFactory
    List<DynamicTest> sharedTestVectors() throws Exception {
        JsonNode root = JSON.readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.align-vector.v1", root.path("schema").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                List<String> expected = new ArrayList<>();
                for (JsonNode e : c.path("expect")) {
                    Map<String, String> key = new TreeMap<>();
                    e.path("key").fields().forEachRemaining(k -> key.put(k.getKey(), k.getValue().asText()));
                    List<String> components = new ArrayList<>();
                    e.path("components").forEach(x -> components.add(x.asText()));
                    expected.add(text(key, e.path("from").asLong(), components, doubles(e.path("values"))));
                }
                List<String> actual = new ArrayList<>();
                for (VectorAligner.Point pt : VectorAligner.align(inputs(c), params(c.path("params"), c.path("params").path("agg").asText()))) {
                    assertEquals(pt.fromMicros() + c.path("params").path("every").asLong() * 1_000_000L, pt.toMicros());
                    actual.add(text(pt.key(), pt.fromMicros() / 1_000_000L, pt.components(), pt.values()));
                }
                assertEquals(expected, actual);
                // the same input under the other aggregations, where the case lists them
                var also = c.path("also").fields();
                while (also.hasNext()) {
                    var a = also.next();
                    List<VectorAligner.Point> points = VectorAligner.align(inputs(c), params(c.path("params"), a.getKey()));
                    assertEquals(doubles(a.getValue()), points.get(0).values(), a.getKey());
                }
            }));
        }
        assertTrue(tests.size() >= 6);
        return tests;
    }

    @Test
    void refusesAnUnknownTagAndAClockOfZero() {
        VectorAligner.Series s = new VectorAligner.Series("a", "m.x", Map.of("turbine", "T01"), new long[]{0}, new double[]{1});
        List<VectorAligner.Input> in = List.of(new VectorAligner.Input("x", List.of(s)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VectorAligner.align(in,
                new VectorAligner.Params(Duration.ofSeconds(60), "rotor", VectorAligner.Agg.MEAN, false, null, false)));
        assertTrue(e.getMessage().contains("rotor"));
        assertThrows(IllegalArgumentException.class,
                () -> new VectorAligner.Params(Duration.ZERO, "series", VectorAligner.Agg.MEAN, false, null, false));
        assertEquals(1, VectorAligner.align(in, new VectorAligner.Params(Duration.ofSeconds(60), "turbine",
                VectorAligner.Agg.MEAN, false, null, false)).get(0).missing() + 1);
    }
}
