package org.opentsx.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.v2.SeriesStats;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DqResultVectorsTest {

    private static final String NAME = "dq-result";

    @TestFactory
    Stream<DynamicTest> sharedVectors() throws IOException {
        if (Boolean.getBoolean("opentsx.vectors.generate")) {
            generate();
        }
        JsonNode doc = StatsVectors.read(NAME);
        assertEquals("opentsx.testvectors.dq-result.v1", doc.path("schema").textValue());
        assertEquals(7, doc.path("cases").size());
        return StreamSupport.stream(doc.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest(c.path("name").textValue(), () ->
                        assertEquals(c.path("expect").toString(), evaluate(c).toString())));
    }

    private static ArrayNode evaluate(JsonNode c) {
        StatsAccumulator acc = new StatsAccumulator(c.path("sketchAlpha").doubleValue());
        int i = 0;
        for (JsonNode value : c.path("values")) {
            acc.add(i++ * 1_000_000L, StatsVectors.number(value));
        }
        for (int gap = 0; gap < c.path("gapCount").intValue(); gap++) {
            acc.addGap();
        }
        SeriesStats stats = acc.toSeriesStats("synthetic-series", "PT10M", 0, c.path("windowEndMicros").longValue());
        JsonNode e = c.path("expectation");
        DataQuality.Expectation expectation = new DataQuality.Expectation(
                e.path("min").isNull() ? null : e.path("min").doubleValue(),
                e.path("max").isNull() ? null : e.path("max").doubleValue(),
                e.path("expectedIntervalMicros").longValue(), e.path("minCompleteness").doubleValue(),
                e.path("maxOutOfRange").doubleValue(), e.path("flatlineCheck").booleanValue(),
                e.path("flatlineMinCount").longValue(), e.path("flatlineEpsilon").doubleValue(),
                e.path("maxGaps").longValue(),
                e.path("maxAge").isNull() ? null : Duration.parse(e.path("maxAge").textValue()));
        OptionalLong now = c.path("nowMicros").isNull() ? OptionalLong.empty()
                : OptionalLong.of(c.path("nowMicros").longValue());
        List<DataQuality.Result> results;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ROOT);
            results = DataQuality.evaluate(stats, expectation, now);
        } finally {
            Locale.setDefault(previous);
        }
        ArrayNode out = StatsVectors.JSON.createArrayNode();
        for (DataQuality.Result result : results) {
            ObjectNode r = out.addObject();
            r.put("seriesId", result.seriesId());
            r.put("windowType", result.windowType());
            r.put("windowStartMicros", result.windowStartMicros());
            r.put("windowEndMicros", result.windowEndMicros());
            r.put("check", result.check().name());
            r.put("status", result.status().name());
            StatsVectors.put(r, "value", result.value());
            r.put("threshold", result.threshold());
            r.put("message", result.message());
        }
        return out;
    }

    private static ObjectNode input(ArrayNode cases, String name, double alpha, double[] values,
                                    int gaps, long end, Long now) {
        ObjectNode c = cases.addObject();
        c.put("name", name);
        c.put("sketchAlpha", alpha);
        c.set("values", StatsVectors.values(values));
        c.put("gapCount", gaps);
        c.put("windowEndMicros", end);
        if (now == null) {
            c.putNull("nowMicros");
        } else {
            c.put("nowMicros", now);
        }
        ObjectNode e = c.putObject("expectation");
        e.put("min", 0.0);
        e.put("max", 10.0);
        e.put("expectedIntervalMicros", 1_000_000L);
        e.put("minCompleteness", 0.95);
        e.put("maxOutOfRange", 0.001);
        e.put("flatlineCheck", true);
        e.put("flatlineMinCount", 3);
        e.put("flatlineEpsilon", 1e-9);
        e.put("maxGaps", 0);
        e.put("maxAge", "PT5S");
        return c;
    }

    private static void generate() throws IOException {
        ObjectNode doc = StatsVectors.document(NAME,
                "DataQuality.evaluate with explicit expectations and an optional fixed clock. "
                        + "Covers every Check and Status, sketch and min/max range checks, empty windows and disabled "
                        + "checks. Messages use Locale.ROOT. All inputs are synthetic.");
        ArrayNode cases = (ArrayNode) doc.path("cases");
        input(cases, "all checks succeed", 0.01, new double[]{1, 2, 3, 4}, 0, 4_000_000, 4_000_000L);
        input(cases, "missing, implausible, gapped and stale values", 0.01,
                new double[]{-5, Double.NaN, 20}, 2, 10_000_000, 20_000_000L);
        input(cases, "flatline", 0.01, new double[]{5, 5, 5, 5}, 0, 4_000_000, 4_000_000L);
        input(cases, "empty window aborts freshness", 0.01, new double[0], 0, 10_000_000, 20_000_000L);
        input(cases, "range without a sketch and freshness without a clock", 0,
                new double[]{1, 20, 3}, 0, 3_000_000, null);
        ObjectNode disabled = input(cases, "all checks disabled", 0.01, new double[]{1, 2}, 0, 2_000_000, null);
        ObjectNode e = (ObjectNode) disabled.path("expectation");
        e.putNull("min");
        e.putNull("max");
        e.put("expectedIntervalMicros", 0);
        e.put("flatlineCheck", false);
        e.putNull("maxAge");
        ObjectNode boundary = input(cases, "inclusive completeness, gaps and freshness thresholds", 0.01,
                new double[]{2, 4, 6}, 1, 4_000_000, 7_000_000L);
        ObjectNode thresholds = (ObjectNode) boundary.path("expectation");
        thresholds.put("minCompleteness", 0.75);
        thresholds.put("maxGaps", 1);
        for (JsonNode c : cases) {
            ((ObjectNode) c).set("expect", evaluate(c));
        }
        StatsVectors.write(NAME, doc);
    }
}
