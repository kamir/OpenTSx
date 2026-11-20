package org.opentsx.retrieval.clean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleaning operators against the test vectors they share with the workbench, in one piece and as a stream; and
 * the series stream that writes the clean grade of a signal.
 */
class CleanChainTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "clean-ops.v1.json");
    static final long SECOND = 1_000_000L;

    record Case(String name, CleanChain chain, long t0, long interval, double[] values, long[] expectTimes, double[] expectValues,
                String unit) {
    }

    static List<Case> cases() throws Exception {
        List<Case> out = new ArrayList<>();
        for (JsonNode c : JSON.readTree(VECTORS.toFile()).path("cases")) {
            List<JsonNode> nodes = new ArrayList<>();
            c.path("nodes").forEach(nodes::add);
            long t0 = Instant.parse(c.path("t0").asText()).toEpochMilli() * 1000;
            out.add(new Case(c.path("name").asText(), CleanChain.of(nodes), t0, c.path("intervalSeconds").asLong() * SECOND,
                    doubles(c.path("values")), longs(c.path("expect").path("times"), t0), doubles(c.path("expect").path("values")),
                    c.path("expect").path("unit").asText(null)));
        }
        return out;
    }

    static double[] doubles(JsonNode a) {
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = a.get(i).isNull() ? Double.NaN : a.get(i).asDouble();
        }
        return v;
    }

    static long[] longs(JsonNode seconds, long t0) {
        long[] v = new long[seconds.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = t0 + seconds.get(i).asLong() * SECOND;
        }
        return v;
    }

    static CleanChain.Block block(Case c, int from, int to) {
        long[] t = new long[to - from];
        for (int i = from; i < to; i++) {
            t[i - from] = c.t0() + i * c.interval();
        }
        return new CleanChain.Block(t, Arrays.copyOfRange(c.values(), from, to));
    }

    /**
     * Equal up to the last bits a power function may differ in between platforms: 1e-12 relative to the value, at
     * least 1e-12. A gap must be a gap.
     */
    static void close(double[] expected, double[] actual, String what) {
        assertEquals(expected.length, actual.length, what);
        for (int i = 0; i < expected.length; i++) {
            if (Double.isNaN(expected[i]) || Double.isNaN(actual[i])) {
                assertEquals(Double.isNaN(expected[i]), Double.isNaN(actual[i]), what + " at " + i);
            } else {
                assertEquals(expected[i], actual[i], 1e-12 * Math.max(1, Math.abs(expected[i])), what + " at " + i);
            }
        }
    }

    @Test
    void theSharedTestVectorsHoldInOnePiece() throws Exception {
        List<Case> cases = cases();
        assertTrue(cases.size() >= 8);
        for (Case c : cases) {
            int n = c.values().length;
            CleanChain.Block out = c.chain().apply(c.chain().newState(), block(c, 0, n), c.t0() + n * c.interval());
            assertArrayEquals(c.expectTimes(), out.times(), c.name());
            close(c.expectValues(), out.values(), c.name());
            assertEquals(c.unit(), c.chain().outputUnit(), c.name());
        }
    }

    @Test
    void aStreamInAnyPiecesGivesTheSameAsOnePiece() throws Exception {
        for (Case c : cases()) {
            int n = c.values().length;
            for (int piece = 1; piece < n; piece++) {
                CleanChain.State st = c.chain().newState();
                List<Long> times = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (int from = 0; from < n; from += piece) {
                    int to = Math.min(n, from + piece);
                    // the state goes through a checkpoint between two blocks
                    st = c.chain().restore(JSON.readTree(st.json().toString()));
                    CleanChain.Block out = c.chain().apply(st, block(c, from, to), c.t0() + to * c.interval());
                    for (int i = 0; i < out.size(); i++) {
                        times.add(out.times()[i]);
                        values.add(out.values()[i]);
                    }
                }
                assertArrayEquals(c.expectTimes(), times.stream().mapToLong(Long::longValue).toArray(), c.name() + ", pieces of " + piece);
                close(c.expectValues(), values.stream().mapToDouble(Double::doubleValue).toArray(), c.name() + ", pieces of " + piece);
            }
        }
    }

    @Test
    void anOpenWindowWaitsForTheDataToPassItsEnd() throws Exception {
        CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":\"PT1M\",\"agg\":\"MEAN\"}}")));
        CleanChain.State st = chain.newState();
        long t0 = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli() * 1000;
        // forty seconds of the first minute: nothing is final
        CleanChain.Block first = chain.apply(st, new CleanChain.Block(new long[]{t0, t0 + 20 * SECOND}, new double[]{1, 3}), t0 + 40 * SECOND);
        assertEquals(0, first.size());
        // the rest of the minute and the start of the next: the first minute is final, the second is open
        CleanChain.Block second = chain.apply(st, new CleanChain.Block(new long[]{t0 + 40 * SECOND, t0 + 60 * SECOND}, new double[]{5, 100}), t0 + 80 * SECOND);
        assertArrayEquals(new long[]{t0}, second.times());
        assertArrayEquals(new double[]{3}, second.values(), 1e-12);
        assertEquals(60 * SECOND, chain.outputIntervalMicros());
    }

    @Test
    void aLongSilenceStartsANewRunInsteadOfFillingIt() throws Exception {
        CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":60,\"agg\":\"MEAN\"}}")));
        CleanChain.State st = chain.newState();
        long t0 = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli() * 1000;
        chain.apply(st, new CleanChain.Block(new long[]{t0}, new double[]{1}), t0 + 60 * SECOND);
        long later = t0 + (CleanChain.MAX_EMPTY_WINDOWS + 5) * 60 * SECOND;
        CleanChain.Block out = chain.apply(st, new CleanChain.Block(new long[]{later}, new double[]{2}), later + 60 * SECOND);
        assertArrayEquals(new long[]{later}, out.times(), "no windows for the silence");
    }

    @Test
    void anOperatorThisRuntimeDoesNotRunIsRefusedWithItsName() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"n4\",\"op\":\"norm.zscore\"}"))));
        assertTrue(e.getMessage().contains("node n4 (norm.zscore) is not available"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":\"one minute\"}}"))));
        assertTrue(e.getMessage().contains("every: \"one minute\" is not a duration"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"dq.checks\",\"params\":{\"checks\":[\"FLATLINE\"]}}"))));
        assertTrue(e.getMessage().contains("PLAUSIBLE_RANGE only"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"dq.checks\",\"params\":{\"min\":5,\"max\":1}}"))));
        assertTrue(e.getMessage().contains("min is above max"), e.getMessage());
    }

    static String refusal(String node) {
        return assertThrows(IllegalArgumentException.class, () -> CleanChain.of(List.of(JSON.readTree(node)))).getMessage();
    }

    @Test
    void aParameterThatIsNoNumberIsRefusedWithNodeAndField() throws Exception {
        assertEquals("node n3: factor: \"1,5\" is not a number; expected a number without quotes, such as 0.01 or 1e-3",
                refusal("{\"id\":\"n3\",\"op\":\"convert.unit\",\"params\":{\"factor\":\"1,5\"}}"));
        assertEquals("node n3: factor: \"1.5\" is not a number; expected a number without quotes, such as 0.01 or 1e-3",
                refusal("{\"id\":\"n3\",\"op\":\"convert.unit\",\"params\":{\"factor\":\"1.5\"}}"));
        assertTrue(refusal("{\"id\":\"n1\",\"op\":\"dq.checks\",\"params\":{\"checks\":[\"PLAUSIBLE_RANGE\"],\"min\":\"0 kW\",\"max\":10}}")
                .startsWith("node n1: min: \"0 kW\" is not a number;"));
        assertTrue(refusal("{\"id\":\"h\",\"op\":\"model.wind-shear\",\"params\":{\"fromM\":10,\"toM\":120,\"alpha\":\"0,11\"}}")
                .startsWith("node h: alpha: \"0,11\" is not a number;"));
        assertTrue(refusal("{\"id\":\"p\",\"op\":\"model.power-curve\",\"params\":{\"ratedKw\":3000,\"cutIn\":3,\"ratedWind\":12.5,"
                + "\"cutOut\":25,\"count\":\"four\"}}").startsWith("node p: count: \"four\" is not a number;"));
        assertEquals("node l: steps: \"1.5\" is not a whole number; expected a whole number from -2147483648 to 2147483647 without quotes",
                refusal("{\"id\":\"l\",\"op\":\"feature.lag\",\"params\":{\"steps\":1.5}}"));
        // absent or null: the default
        CleanChain c = CleanChain.of(List.of(JSON.readTree("{\"id\":\"n3\",\"op\":\"convert.unit\",\"params\":{\"factor\":null,\"offset\":2}}")));
        assertArrayEquals(new double[]{7}, c.apply(c.newState(), new CleanChain.Block(new long[]{0}, new double[]{5}), 1).values());
    }

    @Test
    void everyDocCommentOfTheChainSitsOnItsOwnDeclaration() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/opentsx/retrieval/clean/CleanChain.java"));
        assertFalse(Pattern.compile("\\*/\\s*/\\*\\*").matcher(src).find(), "a doc comment follows another one, so the first documents the wrong declaration");
        assertTrue(Pattern.compile("Reference power curve[^/]*\\*/\\s*record PowerCurve\\(").matcher(src).find());
        assertTrue(Pattern.compile("by the power law[^/]*\\*/\\s*record WindShear\\(").matcher(src).find());
    }

    @Test
    void aRollingWindowThatIsNoDurationIsRefusedAsWindow() throws Exception {
        assertEquals("node r: window: \"an hour\" is not a duration; expected an ISO-8601 duration such as PT1M, or a number of seconds",
                refusal("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"an hour\"}}"));
        assertEquals("node n1: every: \"10min\" is not a duration; expected an ISO-8601 duration such as PT1M, or a number of seconds",
                refusal("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":\"10min\"}}"));
        assertInstanceOf(java.time.format.DateTimeParseException.class, assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"an hour\"}}")))).getCause());
        assertEquals("node r: window: \"PT0S\" is not above zero; expected a duration above zero, such as PT1M",
                refusal("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT0S\"}}"));
        assertEquals("node n1: every: \"-60\" is not above zero; expected a duration above zero, such as PT1M",
                refusal("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":-60}}"));
    }

    @Test
    void aDurationTheTimeAxisCannotHoldIsRefusedWithItsRange() throws Exception {
        String range = " is out of range; expected a duration from PT0.000001S to PT2562047H47M16.854775807S, such as PT1M";
        assertEquals("node r: window: \"P200000D\"" + range,
                refusal("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"P200000D\"}}"));
        assertEquals("node r: window: \"PT0.0000001S\"" + range,
                refusal("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT0.0000001S\"}}"));
        assertEquals("node n1: every: \"1.0E20\"" + range, refusal("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":1e20}}"));
        assertEquals("node n1: every: \"1.0E-9\"" + range, refusal("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":1e-9}}"));
    }

    @Test
    void aRollingWindowOverMoreValuesThanItMayHoldFailsInsteadOfDroppingTheOldest() throws Exception {
        CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT10M\"}}")));
        // a state that already holds the most values a window may hold: 1 ms apart, all inside the ten minutes
        StringBuilder kept = new StringBuilder("{\"kept\":[{\"step\":0,\"samples\":[");
        for (int i = 0; i < CleanChain.MAX_WINDOW_VALUES; i++) {
            kept.append(i == 0 ? "" : ",").append("[").append(i * 1000L).append(",\"1.0\"]");
        }
        CleanChain.State st = chain.restore(JSON.readTree(kept.append("]}]}").toString()));
        long t = CleanChain.MAX_WINDOW_VALUES * 1000L;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> chain.apply(st, new CleanChain.Block(new long[]{t}, new double[]{1}), t + 1));
        assertEquals("node r: window PT10M holds more than 100000 values; give a shorter window or resample the series first", e.getMessage());
    }

    @Test
    void aRollingWindowOverMoreValuesThanItMayHoldIsRefusedWhenTheChainKnowsTheInterval() throws Exception {
        JsonNode tenMs = JSON.readTree("{\"id\":\"n1\",\"op\":\"resample\",\"params\":{\"every\":\"PT0.01S\"}}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CleanChain.of(List.of(tenMs,
                JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT2H\"}}"))));
        assertEquals("node r: window: PT2H holds 720000 values at the interval PT0.01S of its input; expected a window of at most"
                + " PT16M40S (100000 values) or a longer resample interval before it", e.getMessage());
        // the most a window may hold is fine, also with a lag in between
        assertEquals(3, CleanChain.of(List.of(tenMs, JSON.readTree("{\"id\":\"l\",\"op\":\"feature.lag\",\"params\":{\"steps\":2}}"),
                JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT16M40S\"}}"))).size());
    }

    /** A rolling aggregate the long way: every value of (t - window, t] that is no gap, added up for each sample. */
    static double[] rollingByHand(long[] t, double[] v, long window, String fn) {
        double[] out = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            // divided by the power of two of the largest finite magnitude, which rounds nothing, the values add up and
            // square without overflow
            double largest = 0;
            for (int k = 0; k <= i; k++) {
                if (t[k] > t[i] - window && Double.isFinite(v[k])) {
                    largest = Math.max(largest, Math.abs(v[k]));
                }
            }
            double scale = largest == 0 ? 1 : Math.scalb(1.0, Math.getExponent(largest));
            double sum = 0;
            double sq = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int n = 0;
            for (int k = 0; k <= i; k++) {
                if (t[k] > t[i] - window && !Double.isNaN(v[k])) {
                    sum += v[k] / scale;
                    sq += (v[k] / scale) * (v[k] / scale);
                    min = Math.min(min, v[k]);
                    max = Math.max(max, v[k]);
                    n++;
                }
            }
            double mean = sum / n;
            out[i] = n == 0 ? Double.NaN : switch (fn) {
                case "MIN" -> min;
                case "MAX" -> max;
                case "STD" -> Math.sqrt(Math.max(0, sq / n - mean * mean)) * scale;
                default -> mean * scale;
            };
        }
        return out;
    }

    @Test
    void aRollingFeatureEqualsTheAggregateOfItsWindowAlsoInPiecesAndAfterARestart() throws Exception {
        java.util.SplittableRandom random = new java.util.SplittableRandom(7);
        int n = 3000;
        long[] t = new long[n];
        double[] v = new double[n];
        long time = T0;
        for (int i = 0; i < n; i++) {
            // 0 to 1.5 s apart, sometimes twice the same time; one value in twenty is a gap, one in a thousand infinite
            time += random.nextInt(4) * SECOND / 2;
            t[i] = time;
            int kind = random.nextInt(1000);
            v[i] = kind < 50 ? Double.NaN : kind == 50 ? Double.POSITIVE_INFINITY : 100 + 10 * random.nextGaussian();
        }
        for (String fn : List.of("MEAN", "STD", "MIN", "MAX")) {
            for (String window : List.of("PT0.5S", "PT30S", "PT10M")) {
                CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\""
                        + window + "\",\"fn\":\"" + fn + "\"}}")));
                double[] expected = rollingByHand(t, v, java.time.Duration.parse(window).toNanos() / 1000, fn);
                CleanChain.State st = chain.newState();
                double[] got = new double[n];
                for (int from = 0; from < n; ) {
                    int to = Math.min(n, from + 1 + random.nextInt(200));
                    st = chain.restore(JSON.readTree(st.json().toString()));
                    CleanChain.Block out = chain.apply(st, new CleanChain.Block(Arrays.copyOfRange(t, from, to),
                            Arrays.copyOfRange(v, from, to)), t[to - 1] + 1);
                    System.arraycopy(out.values(), 0, got, from, out.size());
                    from = to;
                }
                // the same block by block, without a restart in between
                CleanChain.State kept = chain.newState();
                double[] again = new double[n];
                for (int from = 0; from < n; from += 100) {
                    int to = Math.min(n, from + 100);
                    CleanChain.Block out = chain.apply(kept, new CleanChain.Block(Arrays.copyOfRange(t, from, to),
                            Arrays.copyOfRange(v, from, to)), t[to - 1] + 1);
                    System.arraycopy(out.values(), 0, again, from, out.size());
                }
                assertArrayEquals(again, got, "a restart goes on with the same running sums: " + fn + " over " + window);
                for (double[] actual : List.of(got, again)) {
                    assertEquals(expected.length, actual.length);
                    for (int i = 0; i < n; i++) {
                        if (Double.isNaN(expected[i]) || Double.isInfinite(expected[i])) {
                            assertEquals(expected[i], actual[i], fn + " over " + window + " at " + i);
                        } else {
                            assertEquals(expected[i], actual[i], 1e-9 * Math.max(1, Math.abs(expected[i])), fn + " over " + window + " at " + i);
                        }
                    }
                }
            }
        }
    }

    /**
     * A value that has left the window leaves nothing behind in the running sums: not the rounding of a level that
     * fell by orders of magnitude, nor the overflow of a square. From the first sample whose window holds none of it,
     * the feature equals the aggregate of its window.
     */
    @Test
    void aValueThatLeftTheWindowLeavesNoRoundingBehind() throws Exception {
        java.util.SplittableRandom random = new java.util.SplittableRandom(11);
        int n = 4000;
        long[] t = new long[n];
        double[] fall = new double[n];
        double[] stop = new double[n];
        double[] huge = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = T0 + i * SECOND;
            // a level of a million with a spread of 1 that falls to 1 with a spread of 0.01
            fall[i] = i < 2500 ? 1e6 + random.nextGaussian() : 1 + 0.01 * random.nextGaussian();
            // a turbine in W that stops
            stop[i] = i < 2500 ? 2e6 + 1e4 * random.nextGaussian() : 5 * random.nextGaussian();
            // one finite value whose square is no finite number
            huge[i] = i == 100 ? 1e200 : 1 + 0.01 * random.nextGaussian();
        }
        record Series(String name, double[] values, String window, int clean) {
        }
        for (Series s : List.of(new Series("fall", fall, "PT1000S", 3499), new Series("stop", stop, "PT10M", 3099),
                new Series("huge", huge, "PT1000S", 1100))) {
            for (String fn : List.of("MEAN", "STD")) {
                CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\""
                        + s.window() + "\",\"fn\":\"" + fn + "\"}}")));
                double[] expected = rollingByHand(t, s.values(), java.time.Duration.parse(s.window()).toNanos() / 1000, fn);
                double[] got = chain.apply(chain.newState(), new CleanChain.Block(t, s.values()), t[n - 1] + 1).values();
                for (int i = s.clean(); i < n; i++) {
                    assertEquals(expected[i], got[i], 1e-9 * Math.max(1e-3, Math.abs(expected[i])), fn + " of " + s.name() + " at " + i);
                }
            }
        }
    }

    /**
     * While a finite value whose square is no finite number is in the window, the running sums are not added up afresh:
     * the checkpoint counts the values that left since they were last added up. STD then comes from the scaled window at
     * every step.
     */
    @Test
    void aValueWhoseSquareIsNoFiniteNumberDoesNotMakeEveryStepAddUpTheRunningSums() throws Exception {
        int n = 13;
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = T0 + i * SECOND;
            v[i] = i == 5 ? 1e200 : i;
        }
        for (String fn : List.of("MEAN", "STD")) {
            CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":"
                    + "\"PT10S\",\"fn\":\"" + fn + "\"}}")));
            CleanChain.State st = chain.newState();
            double[] got = chain.apply(st, new CleanChain.Block(t, v), t[n - 1] + 1).values();
            double[] expected = rollingByHand(t, v, 10 * SECOND, fn);
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(expected[i]) || Double.isInfinite(expected[i])) {
                    assertEquals(expected[i], got[i], fn + " at " + i);
                } else {
                    assertEquals(expected[i], got[i], 1e-9 * Math.max(1, Math.abs(expected[i])), fn + " at " + i);
                }
            }
            assertEquals(3, st.json().path("rolling").get(0).path("removed").asInt(),
                    fn + ": samples 0, 1 and 2 left the window, which still holds 1e200: " + st.json());
        }
    }

    /** The sum of 1e308 and 1.5e308 is no finite number, but their mean and their spread are. */
    @Test
    void theMeanAndTheSpreadOfAWindowWhoseSumIsNoFiniteNumberAreFinite() throws Exception {
        long[] t = {T0, T0 + SECOND, T0 + 2 * SECOND, T0 + 3 * SECOND};
        Map<String, double[]> expected = Map.of("MEAN", new double[]{1e308, 1.25e308, 7.5e307, 1},
                "STD", new double[]{0, 2.5e307, 7.5e307, 0});
        for (String fn : List.of("MEAN", "STD")) {
            CleanChain chain = CleanChain.of(List.of(JSON.readTree(
                    "{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT2S\",\"fn\":\"" + fn + "\"}}")));
            double[] got = chain.apply(chain.newState(), new CleanChain.Block(t, new double[]{1e308, 1.5e308, 1, 1}), t[3] + 1).values();
            for (int i = 0; i < t.length; i++) {
                assertEquals(expected.get(fn)[i], got[i], 1e-12 * Math.abs(expected.get(fn)[i]), fn + " at " + i);
            }
        }
        CleanChain mean = CleanChain.of(List.of(JSON.readTree(
                "{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT2S\",\"fn\":\"MEAN\"}}")));
        assertArrayEquals(new double[]{1e308, 1e308, 5e307, 1},
                mean.apply(mean.newState(), new CleanChain.Block(t, new double[]{1e308, 1e308, 1, 1}), t[3] + 1).values());
        assertArrayEquals(new double[]{-1e308, -1.25e308, -7.5e307, -1},
                mean.apply(mean.newState(), new CleanChain.Block(t, new double[]{-1e308, -1.5e308, -1, -1}), t[3] + 1).values());
    }

    /** Each square of 2e153 and 1e153 is finite, but the squares of 120 such values add up beyond the range of a double. */
    @Test
    void theSpreadOfAWindowWhoseSquaresAddUpToNoFiniteNumberIsFinite() throws Exception {
        int n = 120;
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = T0 + i * SECOND;
            v[i] = i % 2 == 0 ? 2e153 : 1e153;
        }
        CleanChain chain = CleanChain.of(List.of(JSON.readTree(
                "{\"id\":\"r\",\"op\":\"feature.rolling\",\"params\":{\"window\":\"PT1H\",\"fn\":\"STD\"}}")));
        double[] got = chain.apply(chain.newState(), new CleanChain.Block(t, v), t[n - 1] + 1).values();
        assertEquals(5e152, got[n - 1], 1e-12 * 5e152);
    }

    static final long T0 = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli() * 1000;
    static final SeriesKey POWER = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "ost-01", "turbine", "T02"), "kW");
    static final SeriesStream.Target TARGET = new SeriesStream.Target("h2-chain", "park/T02/WTUR.W", "clean", "SYNTHETIC", "wind-flow-7", "abc");

    static CleanChain exampleChain() throws Exception {
        return CleanChain.of(List.of(
                JSON.readTree("{\"id\":\"n1\",\"op\":\"dq.checks\",\"params\":{\"checks\":[\"PLAUSIBLE_RANGE\"],\"min\":-100,\"max\":10000,\"mode\":\"gap\"}}"),
                JSON.readTree("{\"id\":\"n2\",\"op\":\"resample\",\"params\":{\"every\":\"PT1M\",\"agg\":\"MEAN\"}}")));
    }

    /** Ten minutes at 1 Hz, number {@code block}, with one implausible value per minute. */
    static Episode raw(int block) {
        double[] v = new double[600];
        for (int i = 0; i < v.length; i++) {
            v[i] = i % 60 == 0 ? 99_999 : 100 * (block + 1);
        }
        return Episodes.regular(POWER, T0 + block * 600L * SECOND, SECOND, v).source("t").createdAt(T0).build();
    }

    @Test
    void theCleanSeriesCarriesSystemSignalGradeAndOrigin() throws Exception {
        SeriesStream stream = new SeriesStream(exampleChain(), TARGET, List.of(POWER.getSeriesId().toString()));
        assertEquals(List.of(POWER.getSeriesId().toString()), stream.waitingFor());
        List<Episode> out = stream.add(raw(0));
        assertEquals(1, out.size());
        Episode e = out.get(0);
        assertEquals("wind.turbine.power_active", e.getSeries().getMetric().toString());
        assertEquals("{flow=wind-flow-7, grade=clean, park=ost-01, signal=park/T02/WTUR.W, system=h2-chain, turbine=T02}", new java.util.TreeMap<>(strings(e.getSeries().getTags())).toString());
        assertEquals("kW", e.getSeries().getUnit().toString());
        assertTrue(!e.getSeries().getSeriesId().toString().equals(POWER.getSeriesId().toString()), "a series of its own");
        assertEquals(TimeEncoding.REGULAR, e.getTimeEncoding());
        assertEquals(60 * SECOND, e.getIntervalMicros());
        assertEquals(10, e.getCount());
        // the implausible value of every minute is left out of the mean
        assertArrayEquals(new double[]{100, 100, 100, 100, 100, 100, 100, 100, 100, 100}, Episodes.values(e), 1e-12);
        Map<String, String> labels = strings(e.getLabels());
        assertEquals("clean", labels.get("grade"));
        assertEquals("SYNTHETIC", labels.get("origin"));
        assertEquals("wind-flow-7", labels.get("flow"));
        assertEquals("abc", labels.get("specHash"));
        assertEquals(List.of(), stream.waitingFor());
        assertEquals(T0 + 600 * SECOND, stream.watermark());
        Episodes.validate(e);
    }

    @Test
    void aBlockOutOfOrderIsSkippedAndARestartGoesOnWhereItWas() throws Exception {
        SeriesStream stream = new SeriesStream(exampleChain(), TARGET, List.of(POWER.getSeriesId().toString()));
        stream.add(raw(0));
        assertEquals(0, stream.add(raw(0)).size(), "the same block again");
        assertEquals(1, stream.skippedBlocks());
        SeriesStream after = SeriesStream.restore(exampleChain(), TARGET, List.of(POWER.getSeriesId().toString()), JSON.readTree(stream.state().toString()));
        assertEquals(1, after.skippedBlocks());
        List<Episode> next = after.add(raw(1));
        assertEquals(1, next.size());
        assertEquals(T0 + 600 * SECOND, org.opentsx.model.Micros.of(next.get(0).getTStart()));
        assertArrayEquals(new double[]{200, 200, 200, 200, 200, 200, 200, 200, 200, 200}, Episodes.values(next.get(0)), 1e-12);
        // a series the stream was not asked for is left alone
        SeriesKey other = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T02"), "m/s");
        assertEquals(0, after.add(Episodes.regular(other, T0, SECOND, new double[]{1, 2}).source("t").build()).size());
    }

    @Test
    void theSamePieceComputedAgainHasTheSameIdentity() throws Exception {
        List<String> ids = List.of(POWER.getSeriesId().toString());
        Episode first = new SeriesStream(exampleChain(), TARGET, ids).add(raw(0)).get(0);
        // a restart computes the block again: a reader that keeps one episode per id does not count it twice
        Episode again = new SeriesStream(exampleChain(), TARGET, ids).add(raw(0)).get(0);
        assertEquals(first.getEpisodeId(), again.getEpisodeId());
        assertTrue(!first.getEpisodeId().equals(new SeriesStream(exampleChain(), TARGET, ids).add(raw(1)).get(0).getEpisodeId()), "another piece");
        SeriesStream.Target changed = new SeriesStream.Target("h2-chain", "park/T02/WTUR.W", "clean", "SYNTHETIC", "wind-flow-7", "other-document");
        assertTrue(!first.getEpisodeId().equals(new SeriesStream(exampleChain(), changed, ids).add(raw(0)).get(0).getEpisodeId()),
                "another document is another result");
        org.opentsx.store.InMemorySeriesStore store = new org.opentsx.store.InMemorySeriesStore();
        store.write(List.of(first));
        store.write(List.of(again));
        assertEquals(1, store.read(org.opentsx.store.EpisodeQuery.of(org.opentsx.store.SeriesSelector.all(), Long.MIN_VALUE, Long.MAX_VALUE)).size());
    }

    @Test
    void aChainWithoutResamplingKeepsTheTimeAxisOfTheRawSeries() throws Exception {
        CleanChain chain = CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"convert.unit\",\"params\":{\"factor\":0.001,\"unit\":\"MW\"}}")));
        SeriesStream stream = new SeriesStream(chain, TARGET, List.of());
        Episode e = stream.add(raw(0)).get(0);
        assertEquals(TimeEncoding.REGULAR, e.getTimeEncoding());
        assertEquals(SECOND, e.getIntervalMicros());
        assertEquals(600, e.getCount());
        assertEquals("MW", e.getSeries().getUnit().toString());
        assertEquals(0.1, Episodes.values(e)[1], 1e-12);
    }

    static Map<String, String> strings(Map<String, String> m) {
        Map<String, String> out = new java.util.TreeMap<>();
        m.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }
}
