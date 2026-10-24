package org.opentsx.app.bucketanalyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PFDRecorderContractTest {

    @TempDir
    Path directory;
    String originalTopic;
    PFDRecorder recorder;

    @BeforeEach
    void configureRecorder() {
        originalTopic = System.getProperty("opentsx.pfd.input.topic");
        System.setProperty("opentsx.pfd.input.topic", "fixture-input");
        recorder = PFDRecorder.getInstance();
        recorder.reset("Contract flow");
    }

    @AfterEach
    void restoreRecorder() {
        if (originalTopic == null) System.clearProperty("opentsx.pfd.input.topic");
        else System.setProperty("opentsx.pfd.input.topic", originalTopic);
        recorder.reset("New Flow");
    }

    @Test
    void configuredExportMatchesTheSchemaCapabilities() throws Exception {
        recorder.addStep("NORMALIZE", Map.of("method", "center"));
        Path file = directory.resolve("flow.json");
        recorder.saveToFile(file.toString());
        JsonObject flow = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("fixture-input", flow.getAsJsonObject("input").get("topic").getAsString());
        assertEquals("LOG", flow.getAsJsonObject("output").get("type").getAsString());
        JsonObject schema = JsonParser.parseString(Files.readString(Path.of("..", "docs", "devguide", "schemas",
                "processing-flow-descriptor-schema.json"))).getAsJsonObject().getAsJsonObject("properties");
        assertEquals("[\"NORMALIZE\"]", schema.getAsJsonObject("steps").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("operation").get("enum").toString());
        assertEquals("[\"LOG\"]", schema.getAsJsonObject("output").getAsJsonObject("properties")
                .getAsJsonObject("type").get("enum").toString());
        assertTrue(schema.getAsJsonObject("input").getAsJsonArray("required").contains(
                new com.google.gson.JsonPrimitive("topic")));
    }

    @Test
    void missingTopicCannotCreateAnExport() {
        System.setProperty("opentsx.pfd.input.topic", " ");
        recorder.reset("No topic");
        Path file = directory.resolve("flow.json");
        assertThrows(IllegalArgumentException.class, () -> recorder.saveToFile(file.toString()));
        assertFalse(Files.exists(file));
    }

    @Test
    void unknownParameterCannotBeExported() {
        recorder.addStep("NORMALIZE", Map.of("window", 5));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.saveToFile(directory.resolve("flow.json").toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DFA", "SMOOTH", "LOAD_DATA", "CACHE", "FILTER", "WINDOW_AGGREGATE",
            "DECOMPOSE", "DETECT_ANOMALY"})
    void unsupportedOperationsCannotOverwriteAnExport(String operation) throws Exception {
        recorder.addStep(operation, Map.of());
        Path file = directory.resolve("flow.json");
        Files.writeString(file, "existing content");
        assertThrows(IllegalArgumentException.class, () -> recorder.saveToFile(file.toString()));
        assertEquals("existing content", Files.readString(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"z-score", "min-max", "unknown"})
    void unsupportedNormalizationCannotBeExported(String method) {
        recorder.addStep("NORMALIZE", Map.of("method", method));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.saveToFile(directory.resolve("flow.json").toString()));
    }
}
