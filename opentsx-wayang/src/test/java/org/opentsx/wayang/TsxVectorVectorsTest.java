package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.MapOperator;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.vector.VectorAligner;
import org.opentsx.retrieval.vector.VectorJson;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared {@code align.vector} test vectors through a Wayang plan (TASK-020, A3.1): every input of a case is a
 * source of its own, marked with its name and joined with a union before {@link TsxAlignVector}. The vectors name
 * their series with short ids, and one id in two inputs is one series. Each id therefore becomes the series key of
 * its first occurrence, and the {@code series} entry of an expected key is translated to that key's seriesId.
 */
class TsxVectorVectorsTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "align-vector.v1.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static VectorAligner.Params params(JsonNode p, String agg) {
        return new VectorAligner.Params(Duration.ofSeconds(p.path("every").asLong()), p.path("key").asText(),
                VectorAligner.Agg.valueOf(agg), p.path("fill").asText().equals("as-of"),
                Duration.ofSeconds(p.path("maxAge").asLong()), p.path("emit").asText().equals("complete"));
    }

    /** The series key of every short id of a case: metric and tags of its first occurrence. */
    private static Map<String, SeriesKey> keys(JsonNode c) {
        Map<String, SeriesKey> out = new LinkedHashMap<>();
        for (JsonNode in : c.path("inputs")) {
            for (JsonNode s : in.path("series")) {
                Map<String, String> tags = new TreeMap<>();
                s.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
                out.putIfAbsent(s.path("id").asText(), SeriesKeys.of(s.path("metric").asText(), tags));
            }
        }
        return out;
    }

    /** One episode per sample, so the operator sees the series sample by sample as on a topic. */
    private static List<Episode> episodes(JsonNode series, SeriesKey key) {
        List<Episode> out = new ArrayList<>();
        for (int i = 0; i < series.path("t").size(); i++) {
            JsonNode v = series.path("v").get(i);
            out.add(Episodes.irregular(key, new long[]{series.path("t").get(i).asLong() * 1_000_000L},
                    new double[]{v.isNull() ? Double.NaN : v.asDouble()}).build());
        }
        return out;
    }

    private static Map<String, Set<String>> expectedByInput(JsonNode c) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (JsonNode in : c.path("inputs")) {
            Set<String> ids = new LinkedHashSet<>();
            in.path("series").forEach(s -> ids.add(keys(c).get(s.path("id").asText()).getSeriesId()));
            out.put(in.path("name").asText(), ids);
        }
        return out;
    }

    /** The episodes of every input, sample index {@code part} of two halves (0 = first, 1 = second, -1 = all). */
    private static Map<String, List<Episode>> inputs(JsonNode c, int part) {
        Map<String, List<Episode>> out = new LinkedHashMap<>();
        for (JsonNode in : c.path("inputs")) {
            List<Episode> all = new ArrayList<>();
            for (JsonNode s : in.path("series")) {
                List<Episode> e = episodes(s, keys(c).get(s.path("id").asText()));
                int half = (e.size() + 1) / 2;
                all.addAll(part < 0 ? e : part == 0 ? e.subList(0, half) : e.subList(half, e.size()));
            }
            out.put(in.path("name").asText(), all);
        }
        return out;
    }

    static List<TsxVectorRecord> plan(Map<String, List<Episode>> inputs, TsxAlignVector align) {
        Operator joined = null;
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
        joined.connectTo(0, align, 0);
        List<TsxVectorRecord> output = new ArrayList<>();
        LocalCallbackSink<TsxVectorRecord> sink = LocalCallbackSink.createCollectingSink(output, TsxVectorRecord.class);
        align.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return output;
    }

    private static TsxAlignVector align(JsonNode c, String agg, boolean flush, TsxState state) {
        return new TsxAlignVector("v", "vec", "dag", "0".repeat(64), params(c.path("params"), agg), expectedByInput(c), 0, flush, state);
    }

    private static String text(Map<String, String> k, long fromSeconds, JsonNode components, JsonNode values) {
        List<String> comps = new ArrayList<>();
        components.forEach(x -> comps.add(x.asText()));
        List<Double> vals = new ArrayList<>();
        values.forEach(v -> vals.add(v.isNull() ? null : v.asDouble()));
        return k + " @" + fromSeconds + " " + comps + " = " + vals;
    }

    private static List<String> expected(JsonNode c) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : c.path("expect")) {
            Map<String, String> key = keyOf(e.path("key"));
            key.computeIfPresent("series", (k, id) -> keys(c).get(id).getSeriesId());
            out.add(text(key, e.path("from").asLong(), e.path("components"), e.path("values")));
        }
        out.sort(String::compareTo);
        return out;
    }

    private static List<String> actual(List<TsxVectorRecord> records, long everySeconds) {
        List<String> out = new ArrayList<>();
        for (TsxVectorRecord r : records) {
            JsonNode n = r.json();
            assertEquals("opentsx.vector.v1", n.path("schema").asText());
            assertEquals(VectorJson.keyText(keyOf(n.path("key"))), r.key());
            long from = Instant.parse(n.path("window").path("from").asText()).getEpochSecond();
            assertEquals(from + everySeconds, Instant.parse(n.path("window").path("to").asText()).getEpochSecond());
            assertEquals("0".repeat(64), n.path("provenance").path("specHash").asText());
            out.add(text(keyOf(n.path("key")), from, n.path("components"), n.path("values")));
        }
        out.sort(String::compareTo);
        return out;
    }

    private static Map<String, String> keyOf(JsonNode key) {
        Map<String, String> out = new TreeMap<>();
        key.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static Stream<JsonNode> cases() throws Exception {
        JsonNode root = JSON.readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.align-vector.v1", root.path("schema").asText());
        assertEquals(6, root.path("cases").size());
        return StreamSupport.stream(root.path("cases").spliterator(), false);
    }

    static JsonNode parityCase(JsonNode c, int chunk, boolean reverse) throws Exception {
        List<String> aggregations = new ArrayList<>();
        aggregations.add(c.path("params").path("agg").textValue());
        c.path("also").fieldNames().forEachRemaining(aggregations::add);
        var result = JSON.createObjectNode();
        for (String aggregation : aggregations) {
            List<List<TsxTagged>> series = new ArrayList<>();
            Map<String, SeriesKey> keys = keys(c);
            for (JsonNode input : c.path("inputs")) {
                for (JsonNode s : input.path("series")) {
                    String name = input.path("name").textValue();
                    series.add(episodes(s, keys.get(s.path("id").textValue())).stream()
                            .map(e -> new TsxTagged(name, e)).toList());
                }
            }
            if (reverse) java.util.Collections.reverse(series);
            List<TsxTagged> input = series.stream().flatMap(List::stream).toList();
            List<TsxVectorRecord> output = new ArrayList<>();
            TsxState state = new TsxState(Map.of());
            for (int from = 0; from < input.size(); from += chunk) {
                CollectionSource<TsxTagged> source = new CollectionSource<>(
                        input.subList(from, Math.min(input.size(), from + chunk)), TsxTagged.class);
                TsxAlignVector operator = align(c, aggregation, false, state);
                LocalCallbackSink<TsxVectorRecord> sink = LocalCallbackSink.createCollectingSink(output, TsxVectorRecord.class);
                source.connectTo(0, operator, 0);
                operator.connectTo(0, sink, 0);
                new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
                state = TsxCleanStatsParity.restore(state);
            }
            CollectionSource<TsxTagged> source = new CollectionSource<>(List.of(), TsxTagged.class);
            TsxAlignVector operator = align(c, aggregation, true, state);
            LocalCallbackSink<TsxVectorRecord> sink = LocalCallbackSink.createCollectingSink(output, TsxVectorRecord.class);
            source.connectTo(0, operator, 0);
            operator.connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
            if (aggregation.equals(c.path("params").path("agg").textValue())) {
                assertEquals(expected(c), actual(output, c.path("params").path("every").longValue()));
            } else {
                assertEquals(1, output.size());
                List<Double> expected = new ArrayList<>();
                c.path("also").path(aggregation).forEach(n -> expected.add(n.doubleValue()));
                List<Double> actual = new ArrayList<>();
                output.get(0).json().path("values").forEach(n -> actual.add(n.doubleValue()));
                assertEquals(expected, actual);
            }
            var records = result.putArray(aggregation);
            output.stream().map(TsxVectorRecord::json).sorted(java.util.Comparator.comparing(JsonNode::toString)).forEach(records::add);
        }
        return result;
    }

    @TestFactory
    Stream<DynamicTest> everySharedCaseMatchesThroughAWayangPlan() throws Exception {
        return cases().map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> {
            String agg = c.path("params").path("agg").asText();
            long every = c.path("params").path("every").asLong();
            List<TsxVectorRecord> out = plan(inputs(c, -1), align(c, agg, true, new TsxState(Map.of())));
            assertEquals(expected(c), actual(out, every));
            // the same input under the other aggregations, where the case lists them
            var also = c.path("also").fields();
            while (also.hasNext()) {
                var a = also.next();
                List<TsxVectorRecord> other = plan(inputs(c, -1), align(c, a.getKey(), true, new TsxState(Map.of())));
                List<Double> values = new ArrayList<>();
                other.get(0).json().path("values").forEach(v -> values.add(v.isNull() ? null : v.asDouble()));
                List<Double> wanted = new ArrayList<>();
                a.getValue().forEach(v -> wanted.add(v.isNull() ? null : v.asDouble()));
                assertEquals(wanted, values, a.getKey());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> twoIncrementsWithTheStateInBetweenGiveTheSamePoints() throws Exception {
        return cases().map(c -> DynamicTest.dynamicTest(c.path("name").asText(), () -> {
            String agg = c.path("params").path("agg").asText();
            long every = c.path("params").path("every").asLong();
            TsxState state = new TsxState(Map.of());
            List<TsxVectorRecord> out = new ArrayList<>(plan(inputs(c, 0), align(c, agg, false, state)));
            JsonNode saved = state.get("v");
            out.addAll(plan(inputs(c, 1), align(c, agg, true, new TsxState(Map.of("v", saved)))));
            assertEquals(expected(c), actual(out, every));
        }));
    }

    @Test
    void anInputTheVectorDoesNotReadIsIgnoredLikeASeriesItDoesNotRead() throws Exception {
        JsonNode c = cases().findFirst().orElseThrow();
        Map<String, List<Episode>> in = new LinkedHashMap<>(inputs(c, -1));
        in.put("elsewhere", in.values().iterator().next());
        List<TsxVectorRecord> out = plan(in, align(c, c.path("params").path("agg").asText(), true, new TsxState(Map.of())));
        assertEquals(expected(c), actual(out, c.path("params").path("every").asLong()));
    }
}
