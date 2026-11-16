package org.opentsx.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsWindowVectorsTest {

    private static final String NAME = "stats-window";

    @TestFactory
    Stream<DynamicTest> sharedVectors() throws IOException {
        if (Boolean.getBoolean("opentsx.vectors.generate")) {
            generate();
        }
        JsonNode doc = StatsVectors.read(NAME);
        assertEquals("opentsx.testvectors.stats-window.v1", doc.path("schema").textValue());
        assertEquals(5, doc.path("cases").size());
        return StreamSupport.stream(doc.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest(c.path("name").textValue(), () ->
                        assertEquals(c.path("expect").toString(), evaluate(c).toString())));
    }

    private static ArrayNode evaluate(JsonNode c) {
        ArrayNode out = StatsVectors.JSON.createArrayNode();
        double alpha = c.path("alpha").doubleValue();
        if (c.path("mode").textValue().equals("accumulator")) {
            StatsAccumulator acc = new StatsAccumulator(alpha);
            for (JsonNode sample : c.path("samples")) {
                acc.add(sample.path("tsMicros").longValue(), StatsVectors.number(sample.path("value")));
            }
            for (JsonNode window : c.path("windows")) {
                long end = Duration.parse(window.textValue()).toNanos() / 1000;
                out.add(StatsVectors.stats(acc.toSeriesStats("synthetic-series", window.textValue(), 0, end)));
            }
        } else {
            List<Duration> windows = new ArrayList<>();
            c.path("windows").forEach(w -> windows.add(Duration.parse(w.textValue())));
            StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults()
                    .withWindows(windows.toArray(Duration[]::new)).withSketchAlpha(alpha)
                    .withExpectedInterval(id -> OptionalLong.of(c.path("expectedIntervalMicros").longValue())));
            List<SeriesStats> records = new ArrayList<>();
            for (JsonNode sample : c.path("samples")) {
                records.addAll(engine.onObservation(new Observation("synthetic-series",
                        Micros.toInstant(sample.path("tsMicros").longValue()),
                        StatsVectors.number(sample.path("value")), null)));
            }
            records.addAll(engine.flush());
            records.forEach(s -> out.add(StatsVectors.stats(s)));
        }
        return out;
    }

    private static ObjectNode input(ArrayNode cases, String name, String mode, long[] ts, double[] values) {
        ObjectNode c = cases.addObject();
        c.put("name", name);
        c.put("mode", mode);
        c.put("alpha", 0.01);
        c.putArray("windows").add("PT10M").add("PT1H");
        c.put("expectedIntervalMicros", 1_000_000L);
        ArrayNode samples = c.putArray("samples");
        for (int i = 0; i < ts.length; i++) {
            ObjectNode sample = samples.addObject();
            sample.put("tsMicros", ts[i]);
            StatsVectors.put(sample, "value", values[i]);
        }
        c.set("expect", evaluate(c));
        return c;
    }

    private static void generate() throws IOException {
        ObjectNode doc = StatsVectors.document(NAME,
                "StatsEngine tumbling PT10M and PT1H windows and GLOBAL, plus explicit StatsAccumulator windows, "
                        + "with QuantileSketch alpha 0.01. Empty engine input emits no windows; empty accumulator "
                        + "windows retain NaN moments and an empty sketch. All inputs are synthetic.");
        ArrayNode cases = (ArrayNode) doc.path("cases");
        input(cases, "boundaries, NaN, infinities and gaps", "engine",
                new long[]{0, 1_000_000, 2_000_000, 599_000_000, 600_000_000, 601_000_000,
                        3_599_000_000L, 3_600_000_000L, 3_601_000_000L},
                new double[]{0, -3.75, Double.NaN, 2.5, 8.2, Double.POSITIVE_INFINITY,
                        12, Double.NEGATIVE_INFINITY, -0.5});
        input(cases, "NaN-only windows", "engine", new long[]{0, 1_000_000},
                new double[]{Double.NaN, Double.NaN});
        input(cases, "empty window", "accumulator", new long[0], new double[0]);
        input(cases, "empty stream", "engine", new long[0], new double[0]);
        input(cases, "canonical sketch values", "accumulator",
                new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                new double[]{0, 1, 2.5, 2.5, -3.75, 8.2, 12, 1e-12, 1000, -0.5});
        StatsVectors.write(NAME, doc);
    }
}
