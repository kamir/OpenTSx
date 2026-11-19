package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.opentsx.model.v2.SeriesKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The refusals of docs/datamodel-2026/testvectors/model-refusals.v1.json, which python-package checks as well. */
class ModelRefusalVectorsTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "model-refusals.v1.json");
    private static final SeriesKey KEY = SeriesKeys.of("x", Map.of());

    @TestFactory
    List<DynamicTest> everyRefusalHasTheSharedMessage() throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(VECTORS.toFile());
        assertEquals("opentsx.testvectors.model-refusals.v1", root.path("schema").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> assertEquals(c.path("error").asText(),
                    assertThrows(IllegalArgumentException.class, call(c)).getMessage())));
        }
        return tests;
    }

    private static Executable call(JsonNode c) {
        return switch (c.path("call").asText()) {
            case "encodeDeltas" -> () -> TimeAxis.encodeDeltas(TestVectors.longs(c.path("timestampsUs")));
            case "decodeDeltas" -> () -> TimeAxis.decodeDeltas(c.path("tStartUs").asLong(), c.path("count").asInt(),
                    ByteBuffer.wrap(HexFormat.of().parseHex(c.path("deltasHex").asText())));
            case "regular" -> () -> {
                Episodes.Draft d = Episodes.regular(KEY, c.path("tStartUs").asLong(), c.path("intervalUs").asLong(),
                        TestVectors.doubles(c.path("values")));
                if (c.has("tEndUs")) {
                    d.tEnd(c.path("tEndUs").asLong());
                }
                if (c.has("qualityHex")) {
                    d.quality(HexFormat.of().parseHex(c.path("qualityHex").asText()));
                }
                d.build();
            };
            case "irregular" -> () -> {
                Episodes.Draft d = Episodes.irregular(KEY, TestVectors.longs(c.path("timestampsUs")),
                        TestVectors.doubles(c.path("values")));
                if (c.has("tEndUs")) {
                    d.tEnd(c.path("tEndUs").asLong());
                }
                d.build();
            };
            default -> throw new IllegalStateException("call: \"" + c.path("call").asText()
                    + "\" is unknown; expected encodeDeltas, decodeDeltas, regular or irregular");
        };
    }
}
