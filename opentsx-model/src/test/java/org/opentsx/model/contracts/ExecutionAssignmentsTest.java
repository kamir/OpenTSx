package org.opentsx.model.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAssignmentsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "execution-contracts.v1.json");

    @Test
    void providesTheOfflineAssignmentValidator() throws Exception {
        Class<?> contract = Class.forName("org.opentsx.model.contracts.ExecutionAssignments");
        assertNotNull(contract.getMethod("validate", String.class));
    }

    @TestFactory
    List<DynamicTest> followsTheSharedContractVectors() throws IOException {
        JsonNode vectors = JSON.readTree(VECTORS.toFile());
        assertEquals("opentsx.execution-contract-vectors.v1", vectors.path("schema").textValue());
        assertTrue(vectors.path("cases").size() >= 61);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : vectors.path("cases")) {
            tests.add(DynamicTest.dynamicTest(vector.path("name").textValue(), () -> {
                if (vector.path("kind").textValue().equals("hash")) {
                    String text = vector.path("text").textValue();
                    assertEquals(vector.path("sha256").textValue(), ExecutionAssignments.sha256(text));
                    assertEquals(vector.path("utf8Hex").textValue(), HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
                    return;
                }
                if (vector.path("error").isNull()) {
                    assertDoesNotThrow(() -> validate(vector));
                } else {
                    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> validate(vector));
                    assertEquals(vector.path("error").textValue(), error.getMessage());
                }
            }));
        }
        return tests;
    }

    @Test
    void validatesAllDocumentsBeforeSetConsistency() throws IOException {
        ObjectNode first = valid();
        ObjectNode differentGeneration = first.deepCopy();
        differentGeneration.put("generation", "2");
        ObjectNode unavailable = first.deepCopy();
        unavailable.put("runtime", "unavailable");
        assertCode("assignment.runtime", () -> ExecutionAssignments.validateSet(List.of(
                JSON.writeValueAsString(first), JSON.writeValueAsString(differentGeneration), JSON.writeValueAsString(unavailable))));
        assertCode("assignment.context", () -> ExecutionAssignments.validateSet(List.of(
                JSON.writeValueAsString(first), JSON.writeValueAsString(differentGeneration))));
    }

    @Test
    void validatesEveryInputShapeBeforeOffsetValues() throws IOException {
        ObjectNode document = valid();
        ((ObjectNode) document.path("inputs").get(0)).put("from", "01");
        ObjectNode wrongShape = document.path("inputs").get(0).deepCopy();
        wrongShape.put("extra", true);
        ((ArrayNode) document.path("inputs")).add(wrongShape);
        assertCode("assignment.input", () -> ExecutionAssignments.validate(JSON.writeValueAsString(document)));
    }

    @Test
    void rejectsInvalidUnicodeKeysBeforeFieldChecks() throws IOException {
        String text = JSON.writeValueAsString(valid()).replace("\"schema\":", "\"\\ud800\":");
        assertCode("assignment.json", () -> ExecutionAssignments.validate(text));
        assertCode("assignment.json", () -> ExecutionAssignments.sha256("\ud800"));
        assertCode("assignment.json", () -> ExecutionAssignments.validate(null));
    }

    @Test
    void validatesFragmentJsonAndCountsItsUtf8Bytes() throws IOException {
        ObjectNode duplicate = valid();
        String text = "{\"source\":{},\"nodes\":[{}],\"sinks\":[{}],\"\\u0073ource\":{}}";
        duplicate.put("fragmentText", text).put("fragmentHash", ExecutionAssignments.sha256(text));
        assertCode("assignment.dag", () -> ExecutionAssignments.validate(JSON.writeValueAsString(duplicate)));
        ObjectNode tooLarge = valid();
        String unicode = "🙂".repeat(8_193);
        tooLarge.put("sourceDagText", unicode).put("specHash", ExecutionAssignments.sha256(unicode));
        assertTrue(JSON.writeValueAsString(tooLarge).getBytes(StandardCharsets.UTF_8).length < 65_536);
        assertCode("assignment.dag", () -> ExecutionAssignments.validate(JSON.writeValueAsString(tooLarge)));
    }

    @Test
    void admitsOnlyFragmentShapeWithoutClaimingOperatorSupport() throws IOException {
        ObjectNode document = valid();
        String fragment = "{\"source\":{},\"nodes\":[{\"op\":\"not-an-installed-operator\"}],\"sinks\":[{}]}";
        document.put("fragmentText", fragment).put("fragmentHash", ExecutionAssignments.sha256(fragment));
        document.put("sourceDagText", "original UI text\r\n").put("specHash", ExecutionAssignments.sha256("original UI text\r\n"));
        assertDoesNotThrow(() -> ExecutionAssignments.validate(JSON.writeValueAsString(document)));
    }

    private static void validate(JsonNode vector) {
        switch (vector.path("kind").textValue()) {
            case "assignment" -> ExecutionAssignments.validate(vector.path("input").textValue());
            case "set" -> {
                List<String> inputs = new ArrayList<>();
                vector.path("inputs").forEach(value -> inputs.add(value.textValue()));
                ExecutionAssignments.validateSet(inputs);
            }
            default -> throw new IllegalArgumentException("unknown vector kind");
        }
    }

    private static ObjectNode valid() throws IOException {
        JsonNode vectors = JSON.readTree(VECTORS.toFile());
        return (ObjectNode) JSON.readTree(vectors.path("cases").get(0).path("input").textValue());
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(IllegalArgumentException.class, operation).getMessage());
    }
}
