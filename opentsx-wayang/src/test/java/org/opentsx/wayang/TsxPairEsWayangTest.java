package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-020 A3.3: the shared {@code pair.es} cases that carry series ({@code a}, {@code b}) through a Wayang plan.
 * A and B are one episode each, samples 1 s apart from 0, so tick indices are the times of the vectors; the window is
 * the episode and the clock 1 s. The four cases with event lists only are run on the kernel by
 * {@code ops.TsxPairEsVectorsTest}.
 */
class TsxPairEsWayangTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "pair-es.v1.json");
    private static final double TOLERANCE = 5e-13;

    private static double[] doubles(JsonNode values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).asDouble();
        }
        return out;
    }

    private static Map<String, Object> params(JsonNode c) {
        Map<String, Object> p = new HashMap<>();
        p.put("every", 1);
        p.put("sigma", c.path("sigma").asDouble());
        if (!c.path("tauMax").isNull()) {
            p.put("tauMax", c.path("tauMax").asDouble());
        }
        return p;
    }

    private static Stream<JsonNode> seriesCases() throws Exception {
        JsonNode root = new ObjectMapper().readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.pair-es.v1", root.path("schema").asText());
        assertEquals(8, root.path("cases").size());
        List<JsonNode> withSeries = StreamSupport.stream(root.path("cases").spliterator(), false).filter(c -> c.has("a")).toList();
        assertEquals(4, withSeries.size());
        return withSeries.stream();
    }

    private static void check(JsonNode c, List<Episode> out) {
        assertEquals(4, out.size());
        Map<String, Double> byField = new HashMap<>();
        for (Episode e : out) {
            String metric = e.getSeries().getMetric();
            assertTrue(metric.startsWith("pair.es."));
            double[] v = Episodes.values(e);
            assertEquals(1, v.length, "window episode gives one item");
            byField.put(metric.substring("pair.es.".length()), v[0]);
            assertEquals("2", e.getLabels().get("pair.sigma"));
            assertEquals("none", e.getLabels().get("pair.tauMax"));
            assertEquals("10", e.getLabels().get("pair.shuffles"));
            assertEquals("1", e.getLabels().get("pair.seed"));
            assertEquals("episode", e.getSeries().getTags().get("window"));
        }
        assertEquals(Set.of("q_total", "q_ab", "q_ba", "q_rand"), byField.keySet());
        c.path("expect").fields().forEachRemaining(x ->
                assertEquals(x.getValue().asDouble(), byField.get(x.getKey()), TOLERANCE, x.getKey()));
    }

    @TestFactory
    Stream<DynamicTest> everySeriesCaseMatchesThroughAWayangPlan() throws Exception {
        return seriesCases().map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> check(c,
                TsxPairVectorsTest.plan(List.of(TsxPairVectorsTest.episode(TsxPairVectorsTest.A, doubles(c.path("a")))),
                        List.of(TsxPairVectorsTest.episode(TsxPairVectorsTest.B, doubles(c.path("b")))),
                        TsxPairVectorsTest.pair("pair.es", params(c), new TsxState(Map.of()))))));
    }

    @TestFactory
    Stream<DynamicTest> aInALaterIncrementGivesTheSameResult() throws Exception {
        return seriesCases().map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> {
            TsxState state = new TsxState(Map.of());
            assertEquals(List.of(), TsxPairVectorsTest.plan(List.of(),
                    List.of(TsxPairVectorsTest.episode(TsxPairVectorsTest.B, doubles(c.path("b")))),
                    TsxPairVectorsTest.pair("pair.es", params(c), state)));
            check(c, TsxPairVectorsTest.plan(List.of(TsxPairVectorsTest.episode(TsxPairVectorsTest.A, doubles(c.path("a")))),
                    List.of(), TsxPairVectorsTest.pair("pair.es", params(c), new TsxState(Map.of("x", state.get("x"))))));
        }));
    }

    @Test
    void largeSeedsUseTheSameUnsigned32BitStateAsTheWorkbench() throws Exception {
        var large = org.opentsx.wayang.ops.TsxPairWindows.es(Map.of("seed", 1e20));
        var reduced = org.opentsx.wayang.ops.TsxPairWindows.es(Map.of("seed", 1661992960L));
        for (JsonNode c : seriesCases().toList()) {
            double[] a = doubles(c.path("a"));
            double[] b = doubles(c.path("b"));
            assertArrayEquals(reduced.compute(a, b, 1, 1), large.compute(a, b, 1, 1), 0,
                    c.path("name").asText());
        }
    }

    @Test
    void settingsLabelsKeepWorkbenchNumberFormatting() {
        assertEquals(Map.of("sigma", "1e-7", "tauMax", "PT1e+21S", "shuffles", "10",
                        "seed", "100000000000000000000"),
                org.opentsx.wayang.ops.TsxPairWindows.es(
                        Map.of("sigma", 1e-7, "tauMax", 1e21, "seed", 1e20)).settings());
    }

    @Test
    void invalidSettingsAreRefusedWithTheMessagesOfTheWorkbench() {
        assertEquals("shuffles \"0\" is not a whole number of 1 or more.", assertThrows(IllegalArgumentException.class,
                () -> org.opentsx.wayang.ops.TsxPairWindows.es(Map.of("shuffles", 0))).getMessage());
        assertEquals("seed \"1.5\" is not a whole number.", assertThrows(IllegalArgumentException.class,
                () -> org.opentsx.wayang.ops.TsxPairWindows.es(Map.of("seed", 1.5))).getMessage());
        assertEquals("tauMax \"-1\" is not a duration in seconds, 0 or more.", assertThrows(IllegalArgumentException.class,
                () -> org.opentsx.wayang.ops.TsxPairWindows.es(Map.of("tauMax", -1))).getMessage());
        assertEquals(Map.of("sigma", "1.5", "tauMax", "PT30S", "shuffles", "10", "seed", "1"),
                org.opentsx.wayang.ops.TsxPairWindows.es(Map.of("sigma", 1.5, "tauMax", 30)).settings());
    }
}
