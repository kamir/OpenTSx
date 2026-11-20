package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.wayang.ops.TsxPairWindows;
import org.opentsx.wayang.ops.TsxPairXcorr;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared vectors of the default clock ({@code pair-clock.v1.json}) through a Wayang plan: episodes whose sampling
 * interval changes or jitters, without {@code every}, so each pair range takes the larger sampling interval of its two
 * episodes. Values are compared with an absolute error below 5e-13, the clock exactly.
 */
class TsxPairClockVectorsTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "pair-clock.v1.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOLERANCE = 5e-13;
    private static final String SPEC = "c".repeat(64);

    private static JsonNode vectors() throws Exception {
        JsonNode root = JSON.readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.pair-clock.v1", root.path("schema").asText());
        assertEquals(3, root.path("cases").size());
        assertEquals(2, root.path("refusals").size());
        return root;
    }

    private static Stream<JsonNode> each(JsonNode list) {
        return StreamSupport.stream(list.spliterator(), false);
    }

    private static Map<String, Object> params(JsonNode node) {
        Map<String, Object> p = new LinkedHashMap<>();
        node.fields().forEachRemaining(f -> p.put(f.getKey(), f.getValue().isNumber() ? f.getValue().numberValue() : f.getValue().asText()));
        return p;
    }

    /** An episode of the vectors, given in the JSON form of GET /v1/episodes. */
    private static Episode episode(JsonNode e) {
        int n = e.path("values").size();
        long[] t = new long[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = e.path("timestampsMicros").get(i).asLong();
            v[i] = e.path("values").get(i).asDouble();
        }
        long start = Micros.of(Instant.parse(e.path("tStart").asText()));
        Episodes.Draft draft = e.has("intervalMicros")
                ? Episodes.regular(SeriesKeys.of(e.path("metric").asText(), Map.of()), start, e.path("intervalMicros").asLong(), v)
                : Episodes.irregular(SeriesKeys.of(e.path("metric").asText(), Map.of()), t, v);
        Episode out = draft.tEnd(Micros.of(Instant.parse(e.path("tEnd").asText()))).episodeId(e.path("episodeId").asText()).build();
        assertEquals(start, Micros.of(out.getTStart()));
        assertArrayEquals(t, Episodes.timestampsMicros(out));
        return out;
    }

    private static List<Episode> episodes(JsonNode list) {
        return each(list).map(TsxPairClockVectorsTest::episode).toList();
    }

    private static TsxPairWindows.Piece piece(Episode e) {
        long[] t = Episodes.timestampsMicros(e);
        double[] x = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            x[i] = t[i] / 1e6;
        }
        return new TsxPairWindows.Piece(e.getSeries().getSeriesId(), e.getEpisodeId(), Micros.of(e.getTStart()) / 1e6,
                Micros.of(e.getTEnd()) / 1e6, x, Episodes.values(e));
    }

    private static JsonNode range(JsonNode c, String a, String b) {
        return each(c.path("ranges")).filter(r -> r.path("a").asText().equals(a) && r.path("b").asText().equals(b))
                .findFirst().orElseThrow(() -> new AssertionError("no pair range " + a + " " + b + " in the vectors"));
    }

    private static void check(JsonNode c, String method, List<Episode> out) {
        List<String> fields = new ArrayList<>();
        c.path("ranges").get(0).path(method).fieldNames().forEachRemaining(f -> {
            if (!f.equals("quality")) fields.add(f);
        });
        assertEquals(c.path("ranges").size() * fields.size(), out.size());
        for (Episode e : out) {
            String metric = e.getSeries().getMetric();
            String field = metric.substring(method.length() + 1);
            assertTrue(fields.contains(field), metric);
            JsonNode r = range(c, e.getLabels().get("pair.a"), e.getLabels().get("pair.b"));
            JsonNode expected = r.path(method);
            assertArrayEquals(new long[]{r.path("centreMicros").asLong()}, Episodes.timestampsMicros(e));
            assertEquals(expected.path("quality").asInt(), e.getQuality().get(0), metric);
            double value = Episodes.values(e)[0];
            if (expected.path(field).isNull()) {
                assertTrue(Double.isNaN(value), metric);
            } else {
                assertEquals(expected.path(field).asDouble(), value, TOLERANCE, metric + " of " + r.path("a").asText());
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> everyCaseMatchesThroughAWayangPlan() throws Exception {
        return each(vectors().path("cases")).flatMap(c -> Stream.of("pair.xcorr", "pair.es").map(method ->
                DynamicTest.dynamicTest(c.path("name").asText() + ": " + method, () -> {
                    List<Episode> a = episodes(c.path("a"));
                    List<Episode> b = episodes(c.path("b"));
                    TsxPair pair = new TsxPair("x", method, params(c.path("params")), Set.of(a.get(0).getSeries().getSeriesId()),
                            Set.of(b.get(0).getSeries().getSeriesId()), SPEC, new TsxState(Map.of()));
                    check(c, method, TsxPairVectorsTest.plan(a, b, pair));
                })));
    }

    @TestFactory
    Stream<DynamicTest> eachPairRangeTakesTheLargerSamplingIntervalOfItsTwoEpisodes() throws Exception {
        return each(vectors().path("cases")).map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> {
            Map<String, Object> p = params(c.path("params"));
            TsxPairXcorr.PairSettings st = TsxPairXcorr.pairSettings(p);
            Map<String, Episode> a = new LinkedHashMap<>();
            episodes(c.path("a")).forEach(e -> a.put(e.getEpisodeId(), e));
            Map<String, Episode> b = new LinkedHashMap<>();
            episodes(c.path("b")).forEach(e -> b.put(e.getEpisodeId(), e));
            for (JsonNode r : c.path("ranges")) {
                TsxPairWindows.Range range = TsxPairWindows.pair(piece(a.get(r.path("a").asText())), piece(b.get(r.path("b").asText())),
                        st.pairing(), st.window());
                assertNotNull(range);
                assertEquals(r.path("every").asDouble(), TsxPairWindows.run(TsxPairWindows.xcorr(p), st, range).every());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> unknownSettingsAreRefusedWithTheMessageOfEveryRuntime() throws Exception {
        return each(vectors().path("refusals")).map(r -> DynamicTest.dynamicTest(r.path("name").asText(), () -> assertEquals(
                r.path("error").asText(),
                assertThrows(IllegalArgumentException.class, () -> TsxPairXcorr.pairSettings(params(r.path("params")))).getMessage())));
    }
}
