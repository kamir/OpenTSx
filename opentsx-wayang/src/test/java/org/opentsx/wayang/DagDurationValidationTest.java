package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DagDurationValidationTest {

    @Test
    void zeroDurationNeedsAnExplicitNonNegativeCatalogLimit(@TempDir Path directory) throws Exception {
        Path catalog = Path.of("..", "systems", "processors");
        String original = Files.readString(catalog.resolve("resample.yaml"));
        String nonNegative = original.replace("type: duration, required: true", "type: duration, min: 0, required: true");
        assertNotEquals(original, nonNegative);
        Files.writeString(directory.resolve("resample.yaml"), nonNegative);
        JsonNode dag = new ObjectMapper().readTree("""
                {"nodes":[{"id":"sample","op":"resample","params":{"every":"PT0S"}}]}
                """);
        assertEquals("PT0S", DagToWayang.parameters(dag, directory).path("nodes").get(0).path("params").path("every").asText());
        assertEquals("node sample: parameter every must be a duration", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.parameters(dag, catalog)).getMessage());
        ((com.fasterxml.jackson.databind.node.ObjectNode) dag.path("nodes").get(0).path("params")).put("every", "PT-1S");
        assertEquals("node sample: parameter every must be a duration", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.parameters(dag, directory)).getMessage());
    }
}
