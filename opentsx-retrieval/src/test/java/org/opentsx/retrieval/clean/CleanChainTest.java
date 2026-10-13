package org.opentsx.retrieval.clean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void theSharedTestVectorsHoldInOnePiece() throws Exception {
        List<Case> cases = cases();
        assertTrue(cases.size() >= 8);
        for (Case c : cases) {
            int n = c.values().length;
            CleanChain.Block out = c.chain().apply(c.chain().newState(), block(c, 0, n), c.t0() + n * c.interval());
            assertArrayEquals(c.expectTimes(), out.times(), c.name());
            assertArrayEquals(c.expectValues(), out.values(), 1e-12, c.name());
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
                assertArrayEquals(c.expectValues(), values.stream().mapToDouble(Double::doubleValue).toArray(), 1e-12, c.name() + ", pieces of " + piece);
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
        assertTrue(e.getMessage().contains("every must be a duration"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"dq.checks\",\"params\":{\"checks\":[\"FLATLINE\"]}}"))));
        assertTrue(e.getMessage().contains("PLAUSIBLE_RANGE only"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> CleanChain.of(List.of(JSON.readTree("{\"id\":\"n1\",\"op\":\"dq.checks\",\"params\":{\"min\":5,\"max\":1}}"))));
        assertTrue(e.getMessage().contains("min is above max"), e.getMessage());
    }

    // ---------------------------------------------------------------- the stream that writes a clean signal

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
        assertEquals("{park=ost-01, signal=park/T02/WTUR.W, system=h2-chain, turbine=T02}", new java.util.TreeMap<>(strings(e.getSeries().getTags())).toString());
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
