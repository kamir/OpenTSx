package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Micros;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;
import org.opentsx.stats.QuantileSketch;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxStatsVectorsTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "stats-window.v1.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static StatsEngine.Config config(JsonNode c) {
        List<Duration> windows = new ArrayList<>();
        c.path("windows").forEach(w -> windows.add(Duration.parse(w.textValue())));
        return StatsEngine.Config.defaults().withWindows(windows.toArray(Duration[]::new))
                .withSketchAlpha(c.path("alpha").doubleValue())
                .withExpectedInterval(id -> OptionalLong.of(c.path("expectedIntervalMicros").longValue()));
    }

    private static List<SpecificRecord> observations(JsonNode c) {
        List<SpecificRecord> out = new ArrayList<>();
        for (JsonNode sample : c.path("samples")) {
            JsonNode value = sample.path("value");
            out.add(new Observation("synthetic-series", Micros.toInstant(sample.path("tsMicros").longValue()),
                    value.isTextual() ? Double.parseDouble(value.textValue()) : value.doubleValue(), null));
        }
        return out;
    }

    private static List<SeriesStats> plan(TsxStatsWindow operator, List<SpecificRecord> input) {
        CollectionSource<SpecificRecord> source = new CollectionSource<>(input, SpecificRecord.class);
        List<SpecificRecord> output = new ArrayList<>();
        LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(output, SpecificRecord.class);
        source.connectTo(0, operator, 0);
        operator.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return output.stream().map(r -> (SeriesStats) r).toList();
    }

    private static void put(ObjectNode node, String name, double value) {
        if (Double.isFinite(value)) {
            node.put(name, value);
        } else {
            node.put(name, Double.toString(value));
        }
    }

    private static ArrayNode records(List<SeriesStats> records) {
        ArrayNode out = JSON.createArrayNode();
        for (SeriesStats s : records) {
            ObjectNode record = out.addObject();
            record.put("seriesId", s.getSeriesId());
            record.put("windowType", s.getWindowType());
            record.put("windowStartMicros", Micros.of(s.getWindowStart()));
            record.put("windowEndMicros", Micros.of(s.getWindowEnd()));
            record.put("validCount", s.getValidCount());
            record.put("nanCount", s.getNanCount());
            record.put("gapCount", s.getGapCount());
            put(record, "min", s.getMin());
            put(record, "max", s.getMax());
            put(record, "mean", s.getMean());
            put(record, "m2", s.getM2());
            if (s.getLastSeen() == null) {
                record.putNull("lastSeenMicros");
            } else {
                record.put("lastSeenMicros", Micros.of(s.getLastSeen()));
            }
            ByteBuffer buffer = s.getQuantileSketch().duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            record.put("sketchHex", HexFormat.of().formatHex(bytes));
            QuantileSketch sketch = QuantileSketch.fromBytes(bytes);
            ArrayNode quantiles = record.putArray("quantiles");
            for (double q : new double[]{0.01, 0.5, 0.99}) {
                ObjectNode quantile = quantiles.addObject();
                quantile.put("q", q);
                put(quantile, "value", sketch.quantile(q));
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> everySharedCaseMatchesThroughAWayangPlan() throws Exception {
        JsonNode document = JSON.readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.stats-window.v1", document.path("schema").textValue());
        assertEquals(5, document.path("cases").size());
        return StreamSupport.stream(document.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest(c.path("name").textValue(), () -> {
                    TsxState state = new TsxState(Map.of());
                    TsxStatsWindow operator = switch (c.path("mode").textValue()) {
                        case "engine" -> TsxStatsWindow.bounded("stats", config(c), state);
                        case "accumulator" -> TsxStatsWindow.window("stats", config(c), state, "synthetic-series", 0);
                        default -> throw new IllegalArgumentException("unknown statistics vector mode");
                    };
                    assertEquals(c.path("expect").toString(), records(plan(operator, observations(c))).toString());
                }));
    }

    @Test
    void observationsAdvanceWindowsThroughAWayangPlan() throws Exception {
        JsonNode c = JSON.readTree(VECTORS.toFile()).path("cases").get(0);
        List<SeriesStats> out = plan(new TsxStatsWindow("stats", config(c), new TsxState(Map.of())), observations(c));
        assertEquals(4, out.size());
        assertEquals(c.path("expect").get(0).path("validCount").longValue(), out.get(0).getValidCount());
    }

    @Test
    void observationWindowsContinueFromSavedStateBeforeBoundedCompletion() throws Exception {
        JsonNode c = JSON.readTree(VECTORS.toFile()).path("cases").get(0);
        List<SpecificRecord> input = observations(c);
        TsxState first = new TsxState(Map.of());
        List<SeriesStats> output = new ArrayList<>(plan(new TsxStatsWindow("stats", config(c), first),
                input.subList(0, 4)));
        assertTrue(first.get("stats").isTextual());
        TsxState restored = new TsxState(first.snapshot());
        output.addAll(plan(TsxStatsWindow.bounded("stats", config(c), restored), input.subList(4, input.size())));
        assertEquals(c.path("expect").toString(), records(output).toString());
    }

    @Test
    void explicitWindowsRejectInvalidScopes() {
        StatsEngine.Config config = StatsEngine.Config.defaults();
        TsxState state = new TsxState(Map.of());
        assertEquals("statistics window needs a non-empty series id", assertThrows(IllegalArgumentException.class,
                () -> TsxStatsWindow.window("stats", config, state, "", 0)).getMessage());
        assertEquals("statistics window needs at least one duration", assertThrows(IllegalArgumentException.class,
                () -> TsxStatsWindow.window("stats", config.withWindows(), state, "series", 0)).getMessage());
        assertEquals("statistics window start must be aligned to PT10M", assertThrows(IllegalArgumentException.class,
                () -> TsxStatsWindow.window("stats", config, state, "series", 1)).getMessage());
        long size = Duration.ofHours(1).toNanos() / 1000;
        long overflowingStart = Long.MAX_VALUE - Math.floorMod(Long.MAX_VALUE, size);
        assertThrows(ArithmeticException.class,
                () -> TsxStatsWindow.window("stats", config, state, "series", overflowingStart));
    }

    private static String rootMessage(RuntimeException failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    @Test
    void explicitWindowsRejectWrongSeriesAndOutOfScopeSamplesThroughAWayangPlan() {
        TsxState state = new TsxState(Map.of());
        assertEquals("statistics window expects series series, not other", rootMessage(assertThrows(RuntimeException.class,
                () -> plan(TsxStatsWindow.window("stats", StatsEngine.Config.defaults(), state, "series", 0),
                        List.of(new Observation("other", Micros.toInstant(0), 1.0, null))))));
        for (long timestamp : new long[]{-1, 3_600_000_000L}) {
            assertEquals("sample is outside the requested statistics windows: " + timestamp,
                    rootMessage(assertThrows(RuntimeException.class, () -> plan(
                            TsxStatsWindow.window("stats", StatsEngine.Config.defaults(), state, "series", 0),
                            List.of(new Observation("series", Micros.toInstant(timestamp), 1.0, null))))));
        }
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void unsupportedRecordsDoNotChangeStatisticsState() {
        TsxState state = new TsxState(Map.of());
        TsxStatsWindow operator = TsxStatsWindow.bounded("stats", StatsEngine.Config.defaults(), state);
        assertEquals("unsupported statistics input: org.opentsx.model.v2.SeriesStats",
                rootMessage(assertThrows(RuntimeException.class, () -> plan(operator, List.of(new SeriesStats())))));
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void explicitWindowsRejectEmptyEpisodesOfAnotherSeries() {
        TsxState state = new TsxState(Map.of());
        TsxStatsWindow operator = TsxStatsWindow.window("stats", StatsEngine.Config.defaults(), state, "series", 0);
        SpecificRecord empty = Episodes.regular(TsxCleanVectorsTest.RAW, 0, 1, new double[0])
                .episodeId("00000000000000000000000000").createdAt(0).build();
        assertEquals("statistics window expects series series, not " + TsxCleanVectorsTest.RAW.getSeriesId(),
                rootMessage(assertThrows(RuntimeException.class, () -> plan(operator, List.of(empty)))));
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void explicitWindowsRejectIncrementalState() throws Exception {
        JsonNode c = JSON.readTree(VECTORS.toFile()).path("cases").get(0);
        TsxState state = new TsxState(Map.of());
        plan(new TsxStatsWindow("stats", config(c), state), observations(c).subList(0, 1));
        Map<String, JsonNode> saved = state.snapshot();
        assertEquals("explicit statistics windows cannot resume incremental state",
                rootMessage(assertThrows(RuntimeException.class, () -> plan(
                        TsxStatsWindow.window("stats", config(c), state, "synthetic-series", 0), List.of()))));
        assertEquals(saved, state.snapshot());
    }
}
