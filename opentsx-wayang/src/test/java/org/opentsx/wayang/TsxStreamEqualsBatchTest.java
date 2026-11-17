package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TsxStreamEqualsBatchTest {

    @TestFactory
    List<DynamicTest> everyVectorCaseMatchesInSixChunkAndSeriesOrders() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, Integer> counts = Map.of("clean-ops", 15, "stats-window", 5, "align-vector", 6,
                "segment-threshold", 6, "segment-extremum", 6, "sax-word", 7, "dq-result", 7,
                "pair-xcorr", 5, "pair-es", 8);
        int nativeCases = 0;
        int kernelCases = 0;
        for (String name : List.of("clean-ops", "stats-window", "align-vector", "segment-threshold",
                "segment-extremum", "sax-word", "dq-result", "pair-xcorr", "pair-es")) {
            Path path = Path.of("..", "docs", "datamodel-2026", "testvectors", name + ".v1.json");
            JsonNode root = new ObjectMapper().readTree(path.toFile());
            assertEquals("opentsx.testvectors." + name + ".v1", root.path("schema").textValue());
            JsonNode cases = root.path("cases");
            assertEquals(counts.get(name).intValue(), cases.size());
            for (JsonNode c : cases) {
                boolean kernel = name.equals("pair-es") && !c.has("a");
                if (kernel) kernelCases++; else nativeCases++;
                String label = kernel ? "pair-es kernel atomic event lists" : name;
                tests.add(DynamicTest.dynamicTest(label + ": " + c.path("name").asText(), () -> {
                    JsonNode expected = batchCase(name, c, root);
                    int runs = 0;
                    for (int chunk : new int[]{1, 7, Integer.MAX_VALUE}) {
                        for (boolean reverse : new boolean[]{false, true}) {
                            JsonNode actual = runCase(name, c, root, chunk, reverse);
                            assertEquals(expected, actual, "chunk=" + chunk + ", reverse=" + reverse);
                            runs++;
                        }
                    }
                    assertEquals(6, runs);
                }));
            }
        }
        assertEquals(61, nativeCases);
        assertEquals(4, kernelCases);
        assertEquals(65, tests.size());
        return tests;
    }

    private static JsonNode batchCase(String name, JsonNode c, JsonNode root) throws Exception {
        return switch (name) {
            case "segment-threshold", "segment-extremum" -> TsxSegmentVectorsTest.batchCase(c);
            case "sax-word" -> TsxSaxVectorsTest.batchCase(c);
            case "dq-result" -> TsxDqVectorsTest.batchCase(c);
            default -> runCase(name, c, root, Integer.MAX_VALUE, false);
        };
    }

    private static JsonNode runCase(String name, JsonNode c, JsonNode root, int chunk, boolean reverse) throws Exception {
        return switch (name) {
            case "clean-ops" -> TsxCleanStatsParity.cleanCase(c, chunk, reverse);
            case "stats-window" -> TsxCleanStatsParity.statsCase(c, chunk, reverse);
            case "align-vector" -> TsxVectorVectorsTest.parityCase(c, chunk, reverse);
            case "segment-threshold", "segment-extremum" -> TsxSegmentVectorsTest.runCase(c, chunk, reverse);
            case "sax-word" -> TsxSaxVectorsTest.runCase(c, chunk, reverse);
            case "dq-result" -> TsxDqVectorsTest.runCase(c, chunk, reverse);
            case "pair-xcorr" -> TsxPairParity.nativeCase(c, root, "pair.xcorr", chunk, reverse);
            case "pair-es" -> c.has("a") ? TsxPairParity.nativeCase(c, root, "pair.es", chunk, reverse)
                    : TsxPairParity.eventListsCase(c, root, chunk, reverse);
            default -> throw new IllegalArgumentException("unknown vector file " + name);
        };
    }
}
