package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.MapOperator;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-020 A3.2: the shared {@code pair.xcorr} vectors through a Wayang plan. A and B are one episode each, samples
 * 1 s apart from 0, as in the vectors; the clock is 1 s. Results are compared with an absolute error below 5e-13.
 */
class TsxPairVectorsTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "pair-xcorr.v1.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOLERANCE = 5e-13;
    static final SeriesKey A = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"));
    static final SeriesKey B = SeriesKeys.of("wind.turbine.power_active", Map.of("turbine", "T01"));
    private static final String SPEC = "b".repeat(64);

    private static double[] doubles(JsonNode values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).asDouble();
        }
        return out;
    }

    private static Map<String, Object> params(JsonNode c) {
        Map<String, Object> p = new HashMap<>();
        p.put("lags", c.path("lags").asInt());
        p.put("every", 1);
        if (!c.path("window").isNull()) {
            p.put("window", c.path("window").asDouble());
        }
        if (!c.path("step").isNull()) {
            p.put("step", c.path("step").asDouble());
        }
        return p;
    }

    static Episode episode(SeriesKey key, double[] values) {
        return Episodes.regular(key, 0, 1_000_000L, values).episodeId("e" + key.getSeriesId()).build();
    }

    /** A plan with the inputs a and b joined by a union; an empty input is left out. */
    static List<Episode> plan(List<Episode> a, List<Episode> b, TsxPair pair) {
        Map<String, List<Episode>> inputs = new LinkedHashMap<>();
        if (!a.isEmpty()) {
            inputs.put(TsxPair.A, a);
        }
        if (!b.isEmpty()) {
            inputs.put(TsxPair.B, b);
        }
        org.apache.wayang.core.plan.wayangplan.Operator joined = null;
        for (Map.Entry<String, List<Episode>> in : inputs.entrySet()) {
            String name = in.getKey();
            CollectionSource<Episode> source = new CollectionSource<>(in.getValue(), Episode.class);
            MapOperator<Episode, TsxTagged> tag = new MapOperator<>(e -> new TsxTagged(name, e), Episode.class, TsxTagged.class);
            source.connectTo(0, tag, 0);
            if (joined == null) {
                joined = tag;
            } else {
                UnionAllOperator<TsxTagged> union = new UnionAllOperator<>(TsxTagged.class);
                joined.connectTo(0, union, 0);
                tag.connectTo(0, union, 1);
                joined = union;
            }
        }
        joined.connectTo(0, pair, 0);
        List<SpecificRecord> output = new ArrayList<>();
        LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(output, SpecificRecord.class);
        pair.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return output.stream().map(r -> (Episode) r).toList();
    }

    static TsxPair pair(Map<String, Object> params, TsxState state) {
        return pair("pair.xcorr", params, state);
    }

    static TsxPair pair(String method, Map<String, Object> params, TsxState state) {
        return new TsxPair("x", method, params, Set.of(A.getSeriesId()), Set.of(B.getSeriesId()), SPEC, state);
    }

    private static Map<String, Episode> byField(List<Episode> out) {
        Map<String, Episode> m = new HashMap<>();
        for (Episode e : out) {
            String metric = e.getSeries().getMetric();
            m.put(metric.substring(metric.lastIndexOf('.') + 1), e);
        }
        return m;
    }

    private static void check(JsonNode c, List<Episode> out) {
        assertEquals(3, out.size());
        Map<String, Episode> f = byField(out);
        assertEquals(Set.of("max", "lag", "strength"), f.keySet());
        for (Episode e : out) {
            assertEquals(A.getSeriesId(), e.getSeries().getTags().get("a"));
            assertEquals(B.getSeriesId(), e.getSeries().getTags().get("b"));
            assertEquals("e" + A.getSeriesId(), e.getProvenance().getParentEpisodeId());
            assertEquals("e" + B.getSeriesId(), e.getLabels().get("pair.b"));
            assertEquals(Integer.toString(c.path("lags").asInt()), e.getLabels().get("pair.lags"));
            assertTrue(e.getEpisodeId().startsWith("p"));
        }
        long[] t = Episodes.timestampsMicros(f.get("max"));
        double[] max = Episodes.values(f.get("max"));
        double[] lag = Episodes.values(f.get("lag"));
        double[] strength = Episodes.values(f.get("strength"));
        if (c.has("windows")) {
            assertEquals(c.path("windows").size(), max.length);
            int i = 0;
            for (JsonNode w : c.path("windows")) {
                assertEquals(Math.round(w.path("centre").asDouble() * 1e6), t[i]);
                // the workbench takes the peak of largest magnitude (xcorr.ts, peakOf); tMax of the original is the
                // largest value, so both agree where the peak is positive
                double[] cc = doubles(w.path("cc"));
                int hi = 0;
                int lo = 0;
                for (int j = 1; j < cc.length; j++) {
                    if (cc[j] > cc[hi]) hi = j;
                    if (cc[j] < cc[lo]) lo = j;
                }
                int peak = Math.abs(cc[hi]) < Math.abs(cc[lo]) ? lo : hi;
                assertEquals(cc[peak], max[i], TOLERANCE);
                assertEquals(c.path("lags").asInt() - peak, lag[i], TOLERANCE);
                if (max[i] > 0) {
                    assertEquals(w.path("tMax").asDouble(), max[i], TOLERANCE);
                    assertEquals(-w.path("ttMax").asDouble(), lag[i], TOLERANCE);
                }
                assertEquals(w.path("strength").asDouble(), strength[i], TOLERANCE);
                i++;
            }
        } else {
            // calcKR_SLIDINGWINDOW leaves out the last window; the workbench computes it
            assertEquals(c.path("sliding").size() + 1, max.length);
            int i = 0;
            for (JsonNode w : c.path("sliding")) {
                assertEquals(Math.round(w.path("centre").asDouble() * 1e6), t[i]);
                assertEquals(w.path("cc").asDouble(), max[i], TOLERANCE);
                assertEquals(0, lag[i]);
                i++;
            }
        }
    }

    private static Stream<JsonNode> cases() throws Exception {
        JsonNode root = JSON.readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.pair-xcorr.v1", root.path("schema").asText());
        assertEquals(5, root.path("cases").size());
        return StreamSupport.stream(root.path("cases").spliterator(), false);
    }

    @TestFactory
    Stream<DynamicTest> everySharedCaseMatchesThroughAWayangPlan() throws Exception {
        return cases().map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> check(c,
                plan(List.of(episode(A, doubles(c.path("a")))), List.of(episode(B, doubles(c.path("b")))),
                        pair(params(c), new TsxState(Map.of()))))));
    }

    @TestFactory
    Stream<DynamicTest> bInALaterIncrementGivesTheSameResult() throws Exception {
        return cases().map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> {
            TsxState state = new TsxState(Map.of());
            assertEquals(List.of(), plan(List.of(episode(A, doubles(c.path("a")))), List.of(), pair(params(c), state)));
            check(c, plan(List.of(), List.of(episode(B, doubles(c.path("b")))),
                    pair(params(c), new TsxState(Map.of("x", state.get("x"))))));
        }));
    }

    @Test
    void anEpisodeDeliveredAgainIsNotPairedAgainAndPairedEpisodesLeaveTheState() throws Exception {
        JsonNode c = cases().findFirst().orElseThrow();
        Episode a = episode(A, doubles(c.path("a")));
        Episode b = episode(B, doubles(c.path("b")));
        TsxState state = new TsxState(Map.of());
        assertEquals(3, plan(List.of(a), List.of(b), pair(params(c), state)).size());
        assertEquals(0, state.get("x").path("pieces").size());
        TsxState next = new TsxState(Map.of("x", state.get("x")));
        assertEquals(List.of(), plan(List.of(a), List.of(b), pair(params(c), next)));
    }

    @Test
    void intersectPairsOverlappingEpisodesOnTheirIntersection() throws Exception {
        JsonNode c = cases().findFirst().orElseThrow();
        double[] a = doubles(c.path("a"));
        double[] b = doubles(c.path("b"));
        Episode a1 = Episodes.regular(A, 0, 1_000_000L, java.util.Arrays.copyOfRange(a, 0, 40)).episodeId("a1").build();
        Episode b1 = Episodes.regular(B, 10_000_000L, 1_000_000L, java.util.Arrays.copyOfRange(b, 10, 64)).episodeId("b1").build();
        Map<String, Object> p = new HashMap<>(params(c));
        p.put("pairing", "intersect");
        List<Episode> out = plan(List.of(a1), List.of(b1), pair(p, new TsxState(Map.of())));
        assertEquals(3, out.size());
        // intersection [10, 40): one window over the range, its item at the centre 25
        assertEquals(25_000_000L, Micros.of(byField(out).get("max").getTStart()));
        assertEquals(1, Episodes.values(byField(out).get("max")).length);
        assertEquals(List.of(), plan(List.of(a1), List.of(b1),
                pair(Map.of("lags", 5, "every", 1), new TsxState(Map.of()))), "same pairs only equal ranges");
    }
}
