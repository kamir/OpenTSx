package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** TASK-020 A1.1 and A1.2: the source and the sink of Track A keep the shared wire vectors byte for byte. */
class TsxWireVectorsTest {

    private static final Path VECTORS = Path.of("..", "opentsx-model", "src", "test", "resources", "testvectors");
    private static final ObjectMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    private static JsonNode load(String name) throws IOException {
        return JSON.readTree(VECTORS.resolve(name).toFile());
    }

    private static TsxRead plan(Set<String> fingerprints) {
        return new TsxRead("unused", "tsx.v.episodes.v2", Map.of(), fingerprints, "vectors", 1);
    }

    @Test
    void everyEpisodeVectorPassesTheSourceAndTheSinkUnchanged() throws IOException {
        JsonNode wire = load("wire-format.json");
        Set<String> fingerprints = Set.of(wire.get("fingerprints").get("Episode").asText());
        OpenTsxAvro avro = new OpenTsxAvro();
        int cases = 0;
        for (JsonNode v : wire.get("episodes")) {
            byte[] bytes = HexFormat.of().parseHex(v.get("hex").asText());
            SpecificRecord decoded = JavaTsxTopicSource.decode(avro, plan(fingerprints),
                    new ConsumerRecord<>("tsx.v.episodes.v2", 0, cases, null, bytes));
            byte[] written = JavaTsxTopicSink.toRecord(avro, "tsx.v.clean.v2", decoded).value();
            assertEquals(v.get("hex").asText(), HexFormat.of().formatHex(written), v.get("spec").get("kind").asText());
            cases++;
        }
        assertEquals(wire.get("episodes").size(), cases);
    }

    @Test
    void theFingerprintsOfThePlanAreThoseOfTheVectors() throws IOException {
        JsonNode fps = load("wire-format.json").get("fingerprints");
        Map<String, String> expected = new TreeMap<>();
        fps.fields().forEachRemaining(f -> expected.put(f.getKey(), f.getValue().asText()));
        expected.put("AnalysisResult", load("analysis-result-wire.json").get("fingerprint").asText());
        Map<String, String> actual = new TreeMap<>();
        for (Class<? extends SpecificRecord> type : OpenTsxAvro.TOP_LEVEL_TYPES) {
            org.apache.avro.Schema s = org.apache.avro.specific.SpecificData.get().getSchema(type);
            actual.put(s.getName(), org.opentsx.model.XxHash64.hex(OpenTsxAvro.fingerprint(s)));
        }
        assertEquals(expected, actual);
    }

    @Test
    void aFingerprintOutsideThePlanStopsWithTheMessageOfTheSpecification() throws IOException {
        JsonNode wire = load("wire-format.json");
        byte[] bytes = HexFormat.of().parseHex(wire.get("episodes").get(0).get("hex").asText());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> JavaTsxTopicSource.decode(
                new OpenTsxAvro(), plan(Set.of(wire.get("fingerprints").get("SeriesStats").asText())),
                new ConsumerRecord<>("tsx.v.episodes.v2", 2, 41, null, bytes)));
        assertEquals("record tsx.v.episodes.v2/2@41 has schema fingerprint 8985f02b48c77195, not in plan vectors version 1",
                e.getMessage());
    }

    @Test
    void seriesIdsMatchTheVectors() throws IOException {
        int cases = 0;
        for (JsonNode v : load("series-id.json")) {
            Map<String, String> tags = new HashMap<>();
            v.get("tags").fields().forEachRemaining(f -> tags.put(f.getKey(), f.getValue().asText()));
            assertEquals(v.get("canonical").asText(), SeriesKeys.canonical(v.get("metric").asText(), tags));
            assertEquals(v.get("seriesId").asText(), SeriesKeys.seriesId(v.get("metric").asText(), tags));
            cases++;
        }
        assertEquals(load("series-id.json").size(), cases);
    }
}
