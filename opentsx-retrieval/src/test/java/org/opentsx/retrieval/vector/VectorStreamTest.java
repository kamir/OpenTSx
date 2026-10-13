package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStreamTest {

    static final long S = 1_000_000L;

    static VectorAligner.Params params(String key, VectorAligner.Agg agg, boolean asOf, long maxAgeSeconds, boolean complete) {
        return new VectorAligner.Params(Duration.ofSeconds(60), key, agg, asOf, Duration.ofSeconds(maxAgeSeconds), complete);
    }

    /** A point in a notation that is easy to compare: key @from-seconds components = values. */
    static List<String> text(List<VectorAligner.Point> points) {
        return points.stream().map(p -> p.key() + " @" + p.fromMicros() / S + " " + p.components() + " = " + p.values()).toList();
    }

    static VectorAligner.Series series(String id, String metric, String turbine, long[] seconds, double[] values) {
        return new VectorAligner.Series(id, metric, Map.of("turbine", turbine), Arrays.stream(seconds).map(t -> t * S).toArray(), values);
    }

    /** Feeds the series to a stream block by block (blocks of {@code blockSeconds}), in the given order of blocks. */
    static List<VectorAligner.Point> stream(VectorStream stream, List<VectorAligner.Series> all, long blockSeconds, long seed) {
        record Block(VectorAligner.Series s, int from, int to, long end) {
        }
        // per series the blocks stay in order; between series they are interleaved at random
        List<List<Block>> perSeries = new ArrayList<>();
        for (VectorAligner.Series s : all) {
            List<Block> blocks = new ArrayList<>();
            int from = 0;
            while (from < s.timesMicros().length) {
                long end = (Math.floorDiv(s.timesMicros()[from], blockSeconds * S) + 1) * blockSeconds * S;
                int to = from;
                while (to < s.timesMicros().length && s.timesMicros()[to] < end) {
                    to++;
                }
                blocks.add(new Block(s, from, to, end));
                from = to;
            }
            perSeries.add(blocks);
        }
        Random r = new Random(seed);
        List<VectorAligner.Point> out = new ArrayList<>();
        while (perSeries.stream().anyMatch(b -> !b.isEmpty())) {
            List<Block> next = perSeries.get(r.nextInt(perSeries.size()));
            if (next.isEmpty()) {
                continue;
            }
            Block b = next.remove(0);
            out.addAll(stream.add(b.s().id(), b.s().metric(), b.s().tags(), Arrays.copyOfRange(b.s().timesMicros(), b.from(), b.to()),
                    Arrays.copyOfRange(b.s().values(), b.from(), b.to()), b.end()));
        }
        out.addAll(stream.flush());
        return out;
    }

    static List<VectorAligner.Series> turbines() {
        // two turbines, two channels each, 1 sample per 20 s over 10 minutes; T02 has a gap in its power
        List<VectorAligner.Series> out = new ArrayList<>();
        for (String t : List.of("T01", "T02")) {
            long[] time = new long[30];
            double[] speed = new double[30];
            double[] power = new double[30];
            for (int i = 0; i < 30; i++) {
                time[i] = 1_790_812_800L + i * 20L;
                speed[i] = 5 + i * 0.25 + (t.equals("T02") ? 1 : 0);
                power[i] = t.equals("T02") && i >= 9 && i < 18 ? Double.NaN : 100 + 10 * i;
            }
            out.add(series("ws-" + t, "wind.met.wind_speed", t, time, speed));
            out.add(series("pa-" + t, "wind.turbine.power_active", t, time, power));
        }
        return out;
    }

    static Set<String> ids(List<VectorAligner.Series> list) {
        Set<String> ids = new LinkedHashSet<>();
        list.forEach(s -> ids.add(s.id()));
        return ids;
    }

    @Test
    void givesWhatTheBatchOperatorGivesWhateverTheOrderOfArrival() {
        List<VectorAligner.Series> data = turbines();
        for (VectorAligner.Params p : List.of(
                params("turbine", VectorAligner.Agg.MEAN, false, 60, false),
                params("turbine", VectorAligner.Agg.LAST, true, 120, false),
                params("turbine", VectorAligner.Agg.MAX, false, 60, true),
                params("series", VectorAligner.Agg.SUM, false, 60, false))) {
            List<String> batch = text(VectorAligner.align(List.of(new VectorAligner.Input("state", data)), p));
            assertTrue(batch.size() >= 10, "the case has data");
            for (long seed = 1; seed <= 5; seed++) {
                List<String> streamed = new ArrayList<>(text(stream(new VectorStream(List.of("state"), p, ids(data), 0), data, 120, seed)));
                List<String> expected = new ArrayList<>(batch);
                // the stream hands out tick by tick as keys become final; the batch key by key
                streamed.sort(String::compareTo);
                expected.sort(String::compareTo);
                assertEquals(expected, streamed, p + " seed " + seed);
            }
        }
    }

    @Test
    void handsATickOutOnlyWhenEverySeriesHasPassedIt() {
        VectorAligner.Params p = params("turbine", VectorAligner.Agg.MEAN, false, 60, false);
        VectorStream v = new VectorStream(List.of("state"), p, Set.of("ws", "pa"), 0);
        Map<String, String> t01 = Map.of("turbine", "T01");

        assertEquals(List.of(), v.add("ws", "wind.met.wind_speed", t01, new long[]{0, 60 * S}, new double[]{4, 6}, 120 * S));
        assertEquals(List.of("pa"), v.waitingFor(), "one series was never seen: nothing is handed out");
        assertEquals(Long.MIN_VALUE, v.watermark());

        // the power arrives up to 60 s: the first tick is final, the second is not
        List<VectorAligner.Point> first = v.add("pa", "wind.turbine.power_active", t01, new long[]{0}, new double[]{100}, 60 * S);
        assertEquals(List.of("{turbine=T01} @0 [power_active, wind_speed] = [100.0, 4.0]"), text(first));
        assertEquals(60 * S, v.watermark());

        List<VectorAligner.Point> second = v.add("pa", "wind.turbine.power_active", t01, new long[]{60 * S}, new double[]{300}, 120 * S);
        assertEquals(List.of("{turbine=T01} @60 [power_active, wind_speed] = [300.0, 6.0]"), text(second));
        assertEquals(List.of(), v.flush(), "nothing is handed out twice");
    }

    @Test
    void keepsATickOpenForTheAllowedLateness() {
        VectorAligner.Params p = params("all", VectorAligner.Agg.MEAN, false, 60, false);
        VectorStream v = new VectorStream(List.of("x"), p, Set.of("a"), 90 * S);
        assertEquals(0, v.add("a", "m.x", Map.of(), new long[]{0}, new double[]{1}, 60 * S).size());
        assertEquals(0, v.add("a", "m.x", Map.of(), new long[]{60 * S}, new double[]{2}, 120 * S).size(), "60 s + 90 s lateness > 120 s");
        assertEquals(1, v.add("a", "m.x", Map.of(), new long[]{120 * S}, new double[]{3}, 180 * S).size());
    }

    @Test
    void skipsABlockThatDoesNotContinueItsSeries() {
        VectorAligner.Params p = params("all", VectorAligner.Agg.SUM, false, 60, false);
        VectorStream v = new VectorStream(List.of("x"), p, Set.of("a"), 0);
        List<VectorAligner.Point> out = new ArrayList<>(v.add("a", "m.x", Map.of(), new long[]{0, 30 * S}, new double[]{1, 2}, 60 * S));
        // the same block again (a producer retried), and an older one
        out.addAll(v.add("a", "m.x", Map.of(), new long[]{0, 30 * S}, new double[]{1, 2}, 60 * S));
        out.addAll(v.add("a", "m.x", Map.of(), new long[]{10 * S}, new double[]{50}, 20 * S));
        out.addAll(v.flush());
        assertEquals(2, v.skippedBlocks());
        assertEquals(List.of("{} @0 [x] = [3.0]"), text(out), "counted once");
        assertEquals(List.of(), v.add("other", "m.y", Map.of(), new long[]{0}, new double[]{9}, 60 * S), "not a series of this vector");
    }

    @Test
    void continuesFromItsStateAfterARestart() throws Exception {
        List<VectorAligner.Series> data = turbines();
        VectorAligner.Params p = params("turbine", VectorAligner.Agg.LAST, true, 120, false);
        List<String> whole = new ArrayList<>(text(stream(new VectorStream(List.of("state"), p, ids(data), 0), data, 120, 3)));

        // the same arrival, but the stream is replaced by one restored from its state after every block
        VectorStream[] v = {new VectorStream(List.of("state"), p, ids(data), 0)};
        List<VectorAligner.Point> out = new ArrayList<>();
        for (int block = 0; block < 5; block++) {
            for (VectorAligner.Series s : data) {
                int from = block * 6;
                long end = s.timesMicros()[from] + 120 * S;
                out.addAll(v[0].add(s.id(), s.metric(), s.tags(), Arrays.copyOfRange(s.timesMicros(), from, from + 6),
                        Arrays.copyOfRange(s.values(), from, from + 6), end));
                JsonNode state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(v[0].state().toString());
                v[0] = VectorStream.restore(List.of("state"), p, ids(data), 0, state);
            }
        }
        out.addAll(v[0].flush());
        List<String> restarted = new ArrayList<>(text(out));
        whole.sort(String::compareTo);
        restarted.sort(String::compareTo);
        assertEquals(whole, restarted);
    }

    @Test
    void refusesWhatCannotBeAVector() {
        VectorAligner.Params p = params("all", VectorAligner.Agg.MEAN, false, 60, false);
        assertThrows(IllegalArgumentException.class, () -> new VectorStream(List.of("x"), p, Set.of(), 0), "no series named");
        // two anemometers in one vector without a key that tells them apart
        VectorStream v = new VectorStream(List.of("x"), p, Set.of("a", "b"), 0);
        v.add("a", "wind.met.wind_speed", Map.of("turbine", "T01"), new long[]{0}, new double[]{1}, 60 * S);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> v.add("b", "wind.met.wind_speed", Map.of("turbine", "T02"), new long[]{0}, new double[]{2}, 60 * S));
        assertTrue(e.getMessage().contains("both give the component \"wind_speed\""), e.getMessage());
    }
}
