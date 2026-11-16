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
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.retrieval.pattern.Paa;
import org.opentsx.retrieval.pattern.Sax;
import org.opentsx.retrieval.pattern.ZNorm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TsxSaxVectorsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "sax-word.v1.json");
    static final String HASH = "a".repeat(64);

    static JsonNode cases() throws Exception { return JSON.readTree(VECTORS.toFile()).path("cases"); }

    static double[] numbers(JsonNode array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).isTextual()
                ? Double.parseDouble(array.get(i).textValue()) : array.get(i).doubleValue();
        return values;
    }

    static TsxSaxWord operator(JsonNode c) {
        return c.path("normalize").booleanValue() ? new TsxSaxWord(c.path("w").intValue(), c.path("a").intValue(), HASH)
                : TsxSaxWord.normalized(c.path("w").intValue(), c.path("a").intValue(), HASH);
    }

    static Episode episode(JsonNode c, int series, int window) {
        SeriesKey key = SeriesKeys.of("synthetic.signal", Map.of("site", series == 0 ? "sax-first" : "sax-second"), "1");
        long start = window * 60_000_000L;
        return Episodes.regular(key, start, 1_000_000, numbers(c.path("values")))
                .episodeId(Ulid.deterministic(start / 1000, 100L * series + window)).createdAt(start).source("synthetic-vectors").build();
    }

    static List<Episode> plan(TsxSaxWord operator, List<? extends SpecificRecord> input) {
        CollectionSource<SpecificRecord> source = new CollectionSource<>(new ArrayList<>(input), SpecificRecord.class);
        List<SpecificRecord> output = new ArrayList<>();
        LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(output, SpecificRecord.class);
        source.connectTo(0, operator, 0);
        operator.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return output.stream().map(record -> (Episode) record).toList();
    }

    @TestFactory
    Stream<DynamicTest> allSharedWordsMatchThroughWayang() throws Exception {
        JsonNode cases = cases();
        assertEquals(7, cases.size());
        return StreamSupport.stream(cases.spliterator(), false).map(c -> DynamicTest.dynamicTest(c.path("name").textValue(), () -> {
            Episode input = episode(c, 0, 0);
            Episode out = plan(operator(c), List.of(input)).get(0);
            assertEquals(c.path("expect").path("word").textValue(), out.getSummary().getSax());
            assertNull(input.getSummary().getSax());
            assertNotEquals(input.getEpisodeId(), out.getEpisodeId());
            assertEquals(input.getEpisodeId(), out.getProvenance().getParentEpisodeId());
            assertArrayEquals(Episodes.values(input), Episodes.values(out));
            assertArrayEquals(Episodes.timestampsMicros(input), Episodes.timestampsMicros(out));
            double[] values = numbers(c.path("values"));
            double[] z = c.path("normalize").booleanValue() ? ZNorm.of(values) : values;
            assertArrayEquals(numbers(c.path("expect").path("normalized")), z);
            assertArrayEquals(numbers(c.path("expect").path("paa")), Paa.of(z, c.path("w").intValue()));
            assertArrayEquals(numbers(c.path("expect").path("breakpoints")), Sax.breakpoints(c.path("a").intValue()));
            assertEquals(c.path("expect").path("letters").textValue(), out.getSummary().getSax().split(":", 2)[1]);
        }));
    }

    @Test
    void rejectsInvalidParametersAndRecords() {
        assertThrows(IllegalArgumentException.class, () -> new TsxSaxWord(0, 2, HASH));
        assertThrows(IllegalArgumentException.class, () -> new TsxSaxWord(1, 27, HASH));
        assertThrows(IllegalArgumentException.class, () -> new TsxSaxWord(1, 2, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> JavaTsxSaxWord.word(new TsxSaxWord(1, 2, HASH), new SeriesStats()));
    }

    static JsonNode batchCase(JsonNode c) { return runCase(c, 16, false); }

    static JsonNode runCase(JsonNode c, int chunk, boolean reverseSeries) {
        List<SpecificRecord> input = new ArrayList<>();
        for (int series : reverseSeries ? new int[]{1, 0} : new int[]{0, 1}) {
            for (int window = 0; window < 8; window++) input.add(episode(c, series, window));
        }
        List<Episode> output = new ArrayList<>();
        for (int from = 0; from < input.size(); from += chunk) {
            output.addAll(plan(operator(c), input.subList(from, Math.min(input.size(), from + chunk))));
        }
        output.sort(Comparator.comparing(Episode::getEpisodeId));
        OpenTsxAvro avro = new OpenTsxAvro();
        ArrayNode result = JSON.createArrayNode();
        for (Episode episode : output) {
            result.addObject().put("id", episode.getEpisodeId())
                    .put("hex", HexFormat.of().formatHex(avro.encode(JavaTsxTopicSink.canonical(episode))));
        }
        return result;
    }
}
