package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.kafka.TopicLayout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DagToWayangWorkbenchTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path CATALOG = Path.of("..", "systems", "processors");
    private static final Path FIXTURES = Path.of("..", "docs", "datamodel-2026", "testvectors", "workbench-dags");

    @TestFactory
    Stream<DynamicTest> actualWorkbenchExportsTranslateOrNameTheNonCatalogNode() throws Exception {
        List<Path> files;
        try (var paths = Files.list(FIXTURES)) {
            files = paths.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertEquals(5, files.size());
        return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            String text = Files.readString(file);
            JsonNode document = YAML.readTree(text);
            TopicLayout layout = new TopicLayout(document.path("source").path("topics").get(0).asText().split("\\.")[1]);
            if (file.getFileName().toString().equals("noncatalog-diff.yaml")) {
                assertEquals("node n1: operator diff is not in catalog tsx-catalog/1", assertThrows(IllegalArgumentException.class,
                        () -> DagToWayang.compile(text, layout, CATALOG)).getMessage());
            } else {
                DagToWayang compiled = DagToWayang.compile(text, layout, CATALOG);
                assertEquals(document.path("nodes").size(), compiled.dag().path("nodes").size());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))), compiled.specHash());
                if (file.getFileName().toString().equals("published-stats.yaml")) {
                    assertEquals("PT2S", compiled.dag().path("nodes").get(1).path("params").path("every").asText());
                    assertEquals("PT4S", compiled.dag().path("nodes").get(2).path("params").path("windows").get(0).asText());
                    assertEquals(0.01, compiled.dag().path("nodes").get(2).path("params").path("alpha").asDouble());
                    assertEquals("org.opentsx.model.v2.SeriesStats", compiled.dag().path("sinks").get(0).path("schema").asText());
                }
            }
        }));
    }

    @Test
    void hashesOriginalTextBeforeParsing() throws Exception {
        String text = Files.readString(FIXTURES.resolve("clean-signal.yaml"));
        DagToWayang original = DagToWayang.compile(text, new TopicLayout("wind"), CATALOG);
        DagToWayang spaced = DagToWayang.compile(text + "\n", new TopicLayout("wind"), CATALOG);
        assertEquals(original.dag(), spaced.dag());
        assertNotEquals(original.specHash(), spaced.specHash());
    }
}
