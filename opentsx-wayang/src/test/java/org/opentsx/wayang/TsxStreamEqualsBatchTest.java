package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TsxStreamEqualsBatchTest {

    @TestFactory
    List<DynamicTest> everyVectorCaseMatchesInSixChunkAndSeriesOrders() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (String name : List.of("segment-threshold", "segment-extremum", "sax-word", "dq-result")) {
            Path path = Path.of("..", "docs", "datamodel-2026", "testvectors", name + ".v1.json");
            JsonNode cases = new ObjectMapper().readTree(path.toFile()).path("cases");
            assertEquals(name.startsWith("segment-") ? 6 : 7, cases.size());
            for (JsonNode c : cases) {
                tests.add(DynamicTest.dynamicTest(name + ": " + c.path("name").asText(), () -> {
                    JsonNode expected = switch (name) {
                        case "segment-threshold", "segment-extremum" -> TsxSegmentVectorsTest.batchCase(c);
                        case "sax-word" -> TsxSaxVectorsTest.batchCase(c);
                        case "dq-result" -> TsxDqVectorsTest.batchCase(c);
                        default -> throw new IllegalArgumentException("unknown vector file " + name);
                    };
                    int runs = 0;
                    for (int chunk : new int[]{1, 7, Integer.MAX_VALUE}) {
                        for (boolean reverse : new boolean[]{false, true}) {
                            JsonNode actual = switch (name) {
                                case "segment-threshold", "segment-extremum" -> TsxSegmentVectorsTest.runCase(c, chunk, reverse);
                                case "sax-word" -> TsxSaxVectorsTest.runCase(c, chunk, reverse);
                                case "dq-result" -> TsxDqVectorsTest.runCase(c, chunk, reverse);
                                default -> throw new IllegalArgumentException("unknown vector file " + name);
                            };
                            assertEquals(expected, actual, "chunk=" + chunk + ", reverse=" + reverse);
                            runs++;
                        }
                    }
                    assertEquals(6, runs);
                }));
            }
        }
        assertEquals(26, tests.size());
        return tests;
    }
}
