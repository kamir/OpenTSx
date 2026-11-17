package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.DataQuality;
import org.opentsx.stats.StatsAccumulator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxDqVectorsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "dq-result.v1.json");
    static final String HASH = "a".repeat(64);

    static JsonNode cases() throws Exception { return JSON.readTree(VECTORS.toFile()).path("cases"); }

    static DataQuality.Expectation expectation(JsonNode c) {
        JsonNode e = c.path("expectation");
        return new DataQuality.Expectation(e.path("min").isNull() ? null : e.path("min").doubleValue(),
                e.path("max").isNull() ? null : e.path("max").doubleValue(),
                e.path("expectedIntervalMicros").longValue(), e.path("minCompleteness").doubleValue(),
                e.path("maxOutOfRange").doubleValue(), e.path("flatlineCheck").booleanValue(),
                e.path("flatlineMinCount").longValue(), e.path("flatlineEpsilon").doubleValue(),
                e.path("maxGaps").longValue(), e.path("maxAge").isNull() ? null : Duration.parse(e.path("maxAge").textValue()));
    }

    static TsxDqResult operator(JsonNode c) {
        OptionalLong now = c.path("nowMicros").isNull() ? OptionalLong.empty() : OptionalLong.of(c.path("nowMicros").longValue());
        return new TsxDqResult("dq-job", "test-site", HASH, expectation(c), EnumSet.allOf(DataQuality.Check.class), now);
    }

    static SeriesStats statistics(JsonNode c, int series, int window) {
        String id = SeriesKeys.of("synthetic.signal", Map.of("site", series == 0 ? "dq-first" : "dq-second"), "1").getSeriesId();
        long offset = -window * 60_000_000L;
        StatsAccumulator acc = new StatsAccumulator(c.path("sketchAlpha").doubleValue());
        int i = 0;
        for (JsonNode value : c.path("values")) {
            acc.add(offset + i++ * 1_000_000L,
                    value.isTextual() ? Double.parseDouble(value.textValue()) : value.doubleValue());
        }
        for (int gap = 0; gap < c.path("gapCount").intValue(); gap++) acc.addGap();
        return acc.toSeriesStats(id, "PT10M", offset, offset + c.path("windowEndMicros").longValue());
    }

    static List<JsonNode> plan(TsxDqResult operator, List<? extends SpecificRecord> input) {
        CollectionSource<SpecificRecord> source = new CollectionSource<>(new ArrayList<>(input), SpecificRecord.class);
        List<JsonNode> output = new ArrayList<>();
        LocalCallbackSink<JsonNode> sink = LocalCallbackSink.createCollectingSink(output, JsonNode.class);
        source.connectTo(0, operator, 0);
        operator.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return output;
    }

    @TestFactory
    Stream<DynamicTest> allSharedResultsMatchThroughWayang() throws Exception {
        JsonNode cases = cases();
        assertEquals(7, cases.size());
        return StreamSupport.stream(cases.spliterator(), false).map(c -> DynamicTest.dynamicTest(c.path("name").textValue(), () -> {
            SeriesStats input = statistics(c, 0, 0);
            List<JsonNode> output = plan(operator(c), List.of(input));
            assertEquals(c.path("expect").size(), output.size());
            for (int i = 0; i < output.size(); i++) {
                JsonNode expected = c.path("expect").get(i);
                JsonNode actual = output.get(i);
                assertEquals(expected.path("windowType").textValue(), input.getWindowType());
                assertEquals(input.getSeriesId(), actual.path("seriesId").textValue());
                assertEquals(Micros.toInstant(expected.path("windowStartMicros").longValue()).toString(), actual.path("window").path("from").textValue());
                assertEquals(Micros.toInstant(expected.path("windowEndMicros").longValue()).toString(), actual.path("window").path("to").textValue());
                for (String field : List.of("check", "status", "threshold", "message")) assertEquals(expected.path(field), actual.path(field), field);
                if (expected.path("value").isTextual()) assertTrue(actual.path("value").isNull());
                else assertEquals(Double.doubleToLongBits(expected.path("value").doubleValue()),
                        Double.doubleToLongBits(actual.path("value").doubleValue()));
                assertEquals(actual.path("window").path("to"), actual.path("at"));
                assertEquals("opentsx.signal.v1", actual.path("schema").textValue());
                assertEquals("dq", actual.path("kind").textValue());
                JavaTsxTopicSink.toRecord(new org.opentsx.model.OpenTsxAvro(), "tsx.test.signals.v1", actual);
            }
        }));
    }

    @Test
    void differentChecksAndWindowWidthsHaveDifferentIdsAndReplayIdentically() throws Exception {
        JsonNode c = cases().get(0);
        SeriesStats narrow = statistics(c, 0, 0);
        SeriesStats wide = SeriesStats.newBuilder(narrow).setWindowType("PT1H")
                .setWindowStart(Micros.toInstant(Micros.of(narrow.getWindowEnd()) - 3_600_000_000L)).build();
        List<JsonNode> first = plan(operator(c), List.of(narrow, wide));
        assertEquals(10, first.size());
        Set<String> ids = new HashSet<>();
        first.forEach(signal -> assertTrue(ids.add(signal.path("id").textValue())));
        assertEquals(first, plan(operator(c), List.of(narrow, wide)));
    }

    @Test
    void selectedChecksDoNotChangeOtherResults() throws Exception {
        JsonNode c = cases().get(0);
        TsxDqResult selected = new TsxDqResult("dq-job", "test-site", HASH, expectation(c), Set.of(DataQuality.Check.GAPS), OptionalLong.empty());
        List<JsonNode> output = plan(selected, List.of(statistics(c, 0, 0)));
        assertEquals(1, output.size());
        assertEquals("GAPS", output.get(0).path("check").textValue());
        SeriesStats invalid = SeriesStats.newBuilder(statistics(c, 0, 0)).setSeriesId("invalid").build();
        assertThrows(IllegalArgumentException.class, () -> JavaTsxDqResult.results(selected, invalid));
    }

    @Test
    void jsonSinkValidatesSignalsAndProducesCanonicalBytes() throws Exception {
        JsonNode c = cases().get(0);
        JsonNode signal = plan(operator(c), List.of(statistics(c, 0, 0))).get(0);
        org.opentsx.model.OpenTsxAvro avro = new org.opentsx.model.OpenTsxAvro();
        byte[] expected = JavaTsxTopicSink.toRecord(avro, "tsx.test.signals.v1", signal).value();
        com.fasterxml.jackson.databind.node.ObjectNode reordered = JSON.createObjectNode();
        List<String> fields = new ArrayList<>();
        signal.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.reverse(fields);
        fields.forEach(field -> reordered.set(field, signal.get(field)));
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected,
                JavaTsxTopicSink.toRecord(avro, "tsx.test.signals.v1", reordered).value());
        for (String field : List.of("id", "seriesId", "specHash", "schema", "at", "kind", "check", "status")) {
            com.fasterxml.jackson.databind.node.ObjectNode invalid = signal.deepCopy();
            invalid.put(field, "invalid");
            assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSink.toRecord(avro, "tsx.test.signals.v1", invalid), field);
        }
        com.fasterxml.jackson.databind.node.ObjectNode extra = signal.deepCopy();
        extra.put("extra", "unsupported");
        assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSink.toRecord(avro, "tsx.test.signals.v1", extra));
        com.fasterxml.jackson.databind.node.ObjectNode nan = signal.deepCopy();
        nan.put("value", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> JavaTsxTopicSink.toRecord(avro, "tsx.test.signals.v1", nan));
    }

    static JsonNode batchCase(JsonNode c) { return runCase(c, 16, false); }

    static JsonNode runCase(JsonNode c, int chunk, boolean reverseSeries) {
        List<SpecificRecord> input = new ArrayList<>();
        for (int series : reverseSeries ? new int[]{1, 0} : new int[]{0, 1}) {
            for (int window = 0; window < 8; window++) input.add(statistics(c, series, window));
        }
        List<JsonNode> output = new ArrayList<>();
        for (int from = 0; from < input.size(); from += chunk) {
            output.addAll(plan(operator(c), input.subList(from, Math.min(input.size(), from + chunk))));
        }
        output.sort(Comparator.comparing(signal -> signal.path("id").textValue()));
        ArrayNode result = JSON.createArrayNode();
        output.forEach(result::add);
        return result;
    }
}
