package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.segment.Segmentations;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.store.EpisodeOps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TsxSegmentVectorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors");
    private static final SeriesKey FIRST = SeriesKeys.of("synthetic.signal", Map.of("site", "test"), "1");
    private static final SeriesKey SECOND = SeriesKeys.of("synthetic.signal", Map.of("site", "other"), "1");

    static List<JsonNode> cases() throws IOException {
        List<JsonNode> cases = new ArrayList<>();
        for (String name : List.of("segment-threshold", "segment-extremum")) {
            JsonNode document = JSON.readTree(VECTORS.resolve(name + ".v1.json").toFile());
            assertEquals("opentsx.testvectors." + name + ".v1", document.path("schema").asText());
            assertEquals(6, document.path("cases").size());
            document.path("cases").forEach(cases::add);
        }
        return cases;
    }

    static Segmenter segmenter(JsonNode c) {
        JsonNode p = c.path("params");
        Duration pre = Duration.parse(p.path("pre").asText());
        Duration post = Duration.parse(p.path("post").asText());
        if (c.path("op").asText().equals("segment.threshold")) {
            return Segmenter.threshold(Segmenter.Direction.valueOf(p.path("direction").asText()),
                    p.path("enter").asDouble(), p.path("exit").asDouble(), pre, post,
                    Duration.parse(p.path("minDuration").asText()), Duration.parse(p.path("maxDuration").asText()));
        }
        return Segmenter.extremum(Segmenter.Feature.valueOf(p.path("feature").asText()), p.path("prominence").asDouble(),
                Duration.parse(p.path("window").asText()), Duration.parse(p.path("distance").asText()), pre, post);
    }

    private static Episode fragment(JsonNode c, SeriesKey key, int from, int to) {
        long[] timestamps = new long[to - from];
        double[] values = new double[to - from];
        for (int i = from; i < to; i++) {
            timestamps[i - from] = c.path("timestampsMicros").get(i).asLong();
            JsonNode value = c.path("values").get(i);
            values[i - from] = value.isNull() ? Double.NaN
                    : value.isNumber() ? value.doubleValue() : Double.parseDouble(value.textValue());
        }
        long end = c.path("timestampsMicros").get(c.path("values").size() - 1).asLong() + 1;
        return Episodes.irregular(key, timestamps, values).episodeId(Ulid.deterministic(0, key.equals(FIRST) ? 1 : 2))
                .source("synthetic-vectors").createdAt(end).build();
    }

    private static List<Episode> whole(JsonNode c) {
        int count = c.path("values").size();
        return List.of(fragment(c, FIRST, 0, count), fragment(c, SECOND, 0, count));
    }

    static JsonNode batchCase(JsonNode c) {
        return records(Segmentations.segment(whole(c), segmenter(c)));
    }

    static JsonNode runCase(JsonNode c, int chunk, boolean reverseSeries) throws IOException {
        if (chunk < 1) throw new IllegalArgumentException("chunk must be positive");
        TsxState state = new TsxState(Map.of());
        List<Episode> output = new ArrayList<>();
        List<SeriesKey> keys = reverseSeries ? List.of(SECOND, FIRST) : List.of(FIRST, SECOND);
        int count = c.path("values").size();
        for (int from = 0; from < count; from += chunk) {
            List<Episode> input = new ArrayList<>();
            for (SeriesKey key : keys) input.add(fragment(c, key, from, Math.min(count, from + chunk)));
            output.addAll(plan(new TsxSegment("segment", segmenter(c), state), input));
            ObjectNode checkpoint = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(state.snapshot()));
            Map<String, JsonNode> saved = new TreeMap<>();
            checkpoint.fields().forEachRemaining(e -> saved.put(e.getKey(), e.getValue()));
            state = new TsxState(saved);
        }
        output.addAll(plan(TsxSegment.bounded("segment", segmenter(c), state), List.of()));
        assertEquals(payloads(Segmentations.segment(whole(c), segmenter(c))), payloads(output),
                "full Avro payloads, chunk " + chunk + ", reverse " + reverseSeries);
        return records(output);
    }

    private static List<Episode> plan(TsxSegment op, List<Episode> input) {
        CollectionSource<SpecificRecord> source = new CollectionSource<>(new ArrayList<SpecificRecord>(input), SpecificRecord.class);
        List<SpecificRecord> output = new ArrayList<>();
        LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(output, SpecificRecord.class);
        source.connectTo(0, op, 0);
        op.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return output.stream().map(Episode.class::cast).toList();
    }

    private static List<String> payloads(List<Episode> input) {
        OpenTsxAvro avro = new OpenTsxAvro();
        return input.stream().sorted(EpisodeOps.READ_ORDER)
                .map(e -> HexFormat.of().formatHex(avro.encode(JavaTsxTopicSink.canonical(e)))).toList();
    }

    private static ArrayNode records(List<Episode> input) {
        ArrayNode records = JSON.createArrayNode();
        for (Episode episode : input.stream().sorted(EpisodeOps.READ_ORDER).toList()) {
            Episodes.validate(episode);
            ObjectNode out = records.addObject();
            out.put("episodeId", episode.getEpisodeId());
            out.put("seriesId", episode.getSeries().getSeriesId());
            out.put("tStartMicros", Micros.of(episode.getTStart()));
            out.put("tEndMicros", Micros.of(episode.getTEnd()));
            out.put("timeEncoding", episode.getTimeEncoding().name());
            out.put("count", episode.getCount());
            out.put("anchorMicros", Micros.of(episode.getSegmentation().getAnchorTs()));
            out.put("strategy", episode.getSegmentation().getStrategy().name());
            out.set("params", JSON.valueToTree(episode.getSegmentation().getParams()));
            ArrayNode timestamps = out.putArray("timestampsMicros");
            for (long timestamp : Episodes.timestampsMicros(episode)) timestamps.add(timestamp);
            ArrayNode values = out.putArray("values");
            for (double value : Episodes.values(episode)) {
                if (Double.isFinite(value)) values.add(value); else values.add(Double.toString(value));
            }
        }
        return records;
    }

    @TestFactory
    List<DynamicTest> sharedVectorsThroughRealWayangPlans() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases()) {
            tests.add(DynamicTest.dynamicTest(c.path("op").asText() + ": " + c.path("name").asText(), () -> {
                List<Episode> input = List.of(fragment(c, FIRST, 0, c.path("values").size()));
                List<Episode> output = plan(TsxSegment.bounded("segment", segmenter(c), new TsxState(Map.of())), input);
                assertEquals(c.path("expect").toString(), records(output).toString());
                assertEquals(payloads(Segmentations.segment(input, segmenter(c))), payloads(output));
            }));
        }
        assertEquals(12, tests.size());
        return tests;
    }

    @Test
    void failsClosedOnCorruptStateAndUnsupportedInput() throws IOException {
        JsonNode c = cases().get(0);
        TsxState state = new TsxState(Map.of("segment", TextNode.valueOf("not Base64")));
        assertThrows(UncheckedIOException.class,
                () -> JavaTsxSegment.run(new TsxSegment("segment", segmenter(c), state), List.of()));
        assertEquals(TextNode.valueOf("not Base64"), state.get("segment"));
        assertThrows(IllegalArgumentException.class,
                () -> JavaTsxSegment.run(new TsxSegment("segment", segmenter(c), new TsxState(Map.of())), List.of(FIRST)));
        assertThrows(IllegalArgumentException.class,
                () -> new TsxSegment("fixed", Segmenter.fixedCount(4, 0), new TsxState(Map.of())));
    }
}
