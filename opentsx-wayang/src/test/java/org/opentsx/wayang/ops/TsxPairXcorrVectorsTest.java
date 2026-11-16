package org.opentsx.wayang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxPairXcorrVectorsTest {

    static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "pair-xcorr.v1.json");

    @TestFactory
    List<DynamicTest> sharedVectors() throws Exception {
        JsonNode root = new ObjectMapper().readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.pair-xcorr.v1", root.path("schema").asText());
        assertTrue(root.path("source").asText().startsWith("kamir/WikiExplorer.NG@7b56e3d"));
        assertEquals(5, root.path("cases").size());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> checkCase(c)));
        }
        return tests;
    }

    private static void checkCase(JsonNode c) {
        double[] a = doubles(c.path("a"));
        double[] b = doubles(c.path("b"));
        double lags = c.path("lags").asDouble();
        Double width = c.path("window").isNull() ? null : c.path("window").asDouble();
        Double step = c.path("step").isNull() ? null : c.path("step").asDouble();
        List<TsxPairXcorr.Window> actual = TsxPairXcorr.compute(a, b, 0, 1, width, step, lags, 0.8);
        if (c.has("windows")) {
            assertEquals(c.path("windows").size(), actual.size());
            int i = 0;
            for (JsonNode w : c.path("windows")) {
                TsxPairXcorr.Window result = actual.get(i++);
                assertEquals(w.path("centre").asDouble(), result.centre());
                double[] expected = doubles(w.path("cc"));
                assertEquals(expected.length, result.cc().length);
                for (int j = 0; j < expected.length; j++) close(expected[j], result.cc()[j]);
                int hi = 0;
                int lo = 0;
                for (int j = 1; j < expected.length; j++) {
                    if (expected[j] > expected[hi]) hi = j;
                    if (expected[j] < expected[lo]) lo = j;
                }
                int peak = Math.abs(expected[hi]) < Math.abs(expected[lo]) ? lo : hi;
                close(expected[peak], result.max());
                assertEquals(lags - peak, result.lag());
                close(w.path("strength").asDouble(), result.strength());
                assertEquals(1, result.coverage());
                assertEquals(0, result.quality());
                if (result.max() > 0) {
                    close(w.path("tMax").asDouble(), result.max());
                    assertEquals(-w.path("ttMax").asDouble(), result.lag());
                }
            }
        } else {
            assertEquals(48, c.path("sliding").size());
            assertEquals(49, actual.size());
            int i = 0;
            for (JsonNode w : c.path("sliding")) {
                TsxPairXcorr.Window result = actual.get(i++);
                assertEquals(w.path("centre").asDouble(), result.centre());
                close(w.path("cc").asDouble(), result.max());
                assertEquals(0, result.lag());
                assertTrue(Double.isNaN(result.strength()));
            }
            assertEquals(56, actual.get(48).centre());
            close(TsxPairXcorr.crossCorrelation(Arrays.copyOfRange(a, 48, 64),
                    Arrays.copyOfRange(b, 48, 64), 0)[0], actual.get(48).max());
        }
    }

    @Test
    void parsesPairSettingsWithTheWorkbenchDefaults() {
        assertEquals(new TsxPairXcorr.PairSettings("same", null, 0, null, "MEAN", null, 0.8),
                TsxPairXcorr.pairSettings(Map.of()));
        assertEquals(new TsxPairXcorr.PairSettings("same", 120.0, 120, null, "MEAN", 0.0, 0.8),
                TsxPairXcorr.pairSettings(Map.of("window", 120, "step", 0, "every", -1, "fill", "as-of")));
        assertEquals(new TsxPairXcorr.PairSettings("same", null, 0, null, "MEAN", null, 0.8),
                TsxPairXcorr.pairSettings(Map.of("window", " ", "step", 10, "minCoverage", "invalid")));
        assertEquals(0, TsxPairXcorr.pairSettings(Map.of("minCoverage", "")).minCoverage());
        assertEquals(0.8, TsxPairXcorr.pairSettings(Map.of("minCoverage", Double.POSITIVE_INFINITY)).minCoverage());
    }

    @Test
    void parsesExplicitPairSettingsAndEverySupportedAggregation() {
        assertEquals(new TsxPairXcorr.PairSettings("intersect", 120.0, 60, 10.0, "LAST", 30.0, 0.75),
                TsxPairXcorr.pairSettings(Map.of("pairing", "intersect", "window", " 120 ", "step", "60",
                        "every", "10", "agg", "LAST", "fill", "as-of", "maxAge", "30", "minCoverage", "0.75")));
        assertEquals(0, TsxPairXcorr.pairSettings(Map.of("fill", "as-of", "maxAge", -30)).maxAge());
        assertEquals(0, TsxPairXcorr.pairSettings(Map.of("fill", "as-of", "maxAge", "invalid")).maxAge());
        for (String agg : List.of("MEAN", "LAST", "FIRST", "MIN", "MAX", "SUM", "COUNT")) {
            assertEquals(agg, TsxPairXcorr.pairSettings(Map.of("agg", agg)).agg());
        }
        assertEquals(16, TsxPairXcorr.pairSettings(Map.of("window", "0x10")).window());
        assertEquals(2, TsxPairXcorr.pairSettings(Map.of("window", "0b10")).window());
        assertEquals(8, TsxPairXcorr.pairSettings(Map.of("window", "0o10")).window());
        assertEquals(120, TsxPairXcorr.pairSettings(Map.of("window", "\u00a0\uFEFF120\u202f")).window());
        assertEquals(60, TsxPairXcorr.pairSettings(Map.of("window", 120, "step", "\u00a060\u00a0")).step());
        assertEquals(0.75, TsxPairXcorr.pairSettings(Map.of("minCoverage", "\u00a00.75\u00a0")).minCoverage());
    }

    @Test
    void rejectsInvalidPairSettingsWithTheWorkbenchMessages() {
        assertEquals("Pairing \"other\" is unknown. Use same or intersect.", assertThrows(IllegalArgumentException.class,
                () -> TsxPairXcorr.pairSettings(Map.of("pairing", "other"))).getMessage());
        for (String window : List.of("ten", "0", "-1", "1d")) {
            assertEquals("The window \"" + window + "\" is neither episode nor a width in seconds greater than 0.",
                    assertThrows(IllegalArgumentException.class,
                            () -> TsxPairXcorr.pairSettings(Map.of("window", window))).getMessage());
        }
        assertEquals("Aggregate \"median\" is unknown.", assertThrows(IllegalArgumentException.class,
                () -> TsxPairXcorr.pairSettings(Map.of("agg", "median"))).getMessage());
        for (double coverage : new double[]{-0.1, 1.1}) {
            assertEquals("The minimum coverage is a share between 0 and 1.", assertThrows(IllegalArgumentException.class,
                    () -> TsxPairXcorr.pairSettings(Map.of("minCoverage", coverage))).getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.pairSettings(Map.of("window", new Object())));
    }

    @Test
    void rejectsNegativeAndFractionalLagsWithTheWorkbenchMessage() {
        for (double lags : new double[]{-1, 2.5}) {
            String text = lags == -1 ? "-1" : "2.5";
            String message = "lags \"" + text + "\" is not a whole number of clock ticks, 0 or more.";
            assertEquals(message, assertThrows(IllegalArgumentException.class,
                    () -> TsxPairXcorr.lagsOf(lags)).getMessage());
            assertEquals(message, assertThrows(IllegalArgumentException.class,
                    () -> TsxPairXcorr.crossCorrelation(new double[]{1, 2}, new double[]{1, 2}, lags)).getMessage());
        }
        assertEquals(10, TsxPairXcorr.lagsOf(null));
        for (double lags : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e20}) {
            assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.lagsOf(lags));
        }
    }

    @Test
    void preservesTheWorkbenchNumberFormattingInLagErrors() {
        Object[][] cases = {
                {1e-7, "1e-7"}, {-1e-7, "-1e-7"}, {-1e21, "-1e+21"}, {-1e22, "-1e+22"},
                {-1.2345678901234568e21, "-1.2345678901234568e+21"},
                {-1e20, "-100000000000000000000"}, {-1e-6, "-0.000001"},
                {Double.MIN_VALUE, "5e-324"}, {-Double.MIN_VALUE, "-5e-324"}
        };
        for (Object[] c : cases) {
            assertEquals("lags \"" + c[1] + "\" is not a whole number of clock ticks, 0 or more.",
                    assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.lagsOf((Double) c[0])).getMessage());
        }
        for (String raw : List.of("2.50", "-0.0000001", "-1.0e21", "ten")) {
            assertEquals("lags \"" + raw + "\" is not a whole number of clock ticks, 0 or more.",
                    assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.lagsOf(raw)).getMessage());
        }
        assertEquals(0, TsxPairXcorr.lagsOf(""));
        assertEquals(2, TsxPairXcorr.lagsOf("2.00"));
    }

    @Test
    void usesTheSignedAbsoluteExtremeAndPrefersThePositivePeakOnATie() {
        TsxPairXcorr.Peak negative = TsxPairXcorr.peakOf(new double[]{0.2, -0.9, 0.1});
        assertEquals(1, negative.index());
        assertEquals(-0.9, negative.value());
        TsxPairXcorr.Peak tied = TsxPairXcorr.peakOf(new double[]{-1, 0, 1});
        assertEquals(2, tied.index());
        assertEquals(1, tied.value());
        assertTrue(Double.isNaN(TsxPairXcorr.peakOf(new double[]{1}).strength()));
    }

    @Test
    void centresOnlyCompleteWindowsAndDefaultsTheStepToTheWidth() {
        assertEquals(List.of(new TsxPairXcorr.WindowSpan(3, 13, 8)), TsxPairXcorr.windowsOf(3, 13, null, 0));
        assertEquals(List.of(new TsxPairXcorr.WindowSpan(0, 4, 2), new TsxPairXcorr.WindowSpan(3, 7, 5),
                new TsxPairXcorr.WindowSpan(6, 10, 8)), TsxPairXcorr.windowsOf(0, 10, 4.0, 3));
        assertTrue(TsxPairXcorr.windowsOf(0, 3, 4.0, 1).isEmpty());
        assertTrue(TsxPairXcorr.windowsOf(0, 4e-7, null, 0).isEmpty());
        assertTrue(TsxPairXcorr.windowsOf(0, 10, 4.0, 0).isEmpty());
        assertTrue(TsxPairXcorr.windowsOf(0, 4 - 4e-7, 4.0, 1e-10).isEmpty());
        assertEquals(3, TsxPairXcorr.windowsOf(0, 0.3, 0.1, 0.1).size());
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        List<TsxPairXcorr.Window> out = TsxPairXcorr.compute(values, values, 0, 1, 4.0, null, 0.0, 0.8);
        assertEquals(List.of(2.0, 6.0), out.stream().map(TsxPairXcorr.Window::centre).toList());
        assertEquals(List.of(2.0, 6.0), TsxPairXcorr.compute(values, values, 0, 1, 4.0, -1.0, 0.0, 0.8)
                .stream().map(TsxPairXcorr.Window::centre).toList());
    }

    @Test
    void measuresLagInSecondsOfTheClock() throws Exception {
        JsonNode c = new ObjectMapper().readTree(VECTORS.toFile()).path("cases").get(0);
        TsxPairXcorr.Window result = TsxPairXcorr.compute(doubles(c.path("a")), doubles(c.path("b")),
                0, 10, null, null, 5.0, 0.8).get(0);
        assertEquals(320, result.centre());
        assertEquals(30, result.lag());
    }

    @Test
    void countsMissingTicksAsTheMeanAndMarksInsufficientCoverage() {
        double[] a = {1, 2, 3, 4};
        double[] b = {1, Double.NaN, 3, 4};
        double mean = 8.0 / 3;
        double ss = (1 - mean) * (1 - mean) + (3 - mean) * (3 - mean) + (4 - mean) * (4 - mean);
        double expected = ((1 - 2.5) * (1 - mean) + (3 - 2.5) * (3 - mean) + (4 - 2.5) * (4 - mean))
                / (3 * Math.sqrt(5.0 / 3) * Math.sqrt(ss / 2));
        close(expected, TsxPairXcorr.crossCorrelation(a, b, 0)[0]);
        TsxPairXcorr.Window low = TsxPairXcorr.compute(a, b, 0, 1, null, null, 0.0, 0.8).get(0);
        assertEquals(0.75, low.coverage());
        assertEquals(1, low.quality());
        assertTrue(Double.isNaN(low.max()));
        TsxPairXcorr.Window covered = TsxPairXcorr.compute(a, b, 0, 1, null, null, 0.0, 0.75).get(0);
        close(expected, covered.max());
        assertEquals(0, covered.quality());
    }

    @Test
    void flatEmptyAndSingleValueWindowsHaveNoCorrelation() {
        for (double[] flat : new double[][]{{1, 1, 1}, {}, {1}, {Double.NaN, Double.POSITIVE_INFINITY}}) {
            assertTrue(Arrays.stream(TsxPairXcorr.crossCorrelation(flat, flat, 2)).allMatch(Double::isNaN));
        }
        TsxPairXcorr.Window flat = TsxPairXcorr.compute(new double[]{1, 1}, new double[]{2, 2},
                0, 1, null, null, 1.0, 0.8).get(0);
        assertTrue(Double.isNaN(flat.max()));
        assertTrue(Double.isNaN(flat.lag()));
        assertTrue(Double.isNaN(flat.strength()));
        assertEquals(0, flat.quality());
        assertTrue(TsxPairXcorr.compute(new double[0], new double[0], 0, 1, null, null, 1.0, 0.8).isEmpty());
    }

    @Test
    void rejectsMismatchedArraysAndInvalidWindowParameters() {
        assertEquals("A has 1 values and B 2: a window needs both alike.", assertThrows(IllegalArgumentException.class,
                () -> TsxPairXcorr.crossCorrelation(new double[1], new double[2], 0)).getMessage());
        assertEquals("The minimum coverage is a share between 0 and 1.", assertThrows(IllegalArgumentException.class,
                () -> TsxPairXcorr.compute(new double[1], new double[1], 0, 1, null, null, 0.0, 1.1)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TsxPairXcorr.compute(new double[1], new double[1], 0, 0, null, null, 0.0, 0.8));
        assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.windowsOf(0, 10, 0.0, 1));
        assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.windowsOf(Double.NaN, 10, null, 0));
        assertEquals("The first aligned sample must start on the epoch clock.", assertThrows(IllegalArgumentException.class,
                () -> TsxPairXcorr.compute(new double[]{1, 2, 3, 4}, new double[]{1, 2, 3, 4},
                        0.5, 1, null, null, 0.0, 0.8)).getMessage());
    }

    private static double[] doubles(JsonNode node) {
        double[] out = new double[node.size()];
        for (int i = 0; i < out.length; i++) out[i] = node.get(i).isNull() ? Double.NaN : node.get(i).asDouble();
        return out;
    }

    private static void close(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < 5e-13, () -> "expected " + expected + " but got " + actual);
    }
}
