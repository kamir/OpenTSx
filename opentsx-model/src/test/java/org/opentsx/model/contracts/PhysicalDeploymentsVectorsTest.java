package org.opentsx.model.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PhysicalDeploymentsVectorsTest {
    @Test
    void providesLocatedPhysicalAdmission() throws Exception {
        Class<?> contract = Class.forName("org.opentsx.model.contracts.PhysicalDeployments");
        assertNotNull(contract.getMethod("validate", String.class));
        assertNotNull(contract.getMethod("admit", String.class, com.fasterxml.jackson.databind.JsonNode.class, String.class));
    }
    @TestFactory
    List<DynamicTest> reproducesEverySharedCase() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Path.of("..", "docs", "datamodel-2026", "testvectors", "physical-deployment.v1.json").toFile());
        assertEquals("opentsx.physical-deployment-vectors.v1", vectors.path("schema").asText());
        assertEquals(53, vectors.path("cases").size());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : vectors.path("cases")) tests.add(DynamicTest.dynamicTest(vector.path("name").asText(), () -> {
            String kind = vector.path("kind").asText();
            if (!kind.equals("physical")) {
                String text = kind.equals("canonical") ? PhysicalDeployments.canonical(vector.path("input"))
                        : PhysicalDeployments.encodeComputation(vector.path("input"));
                assertEquals(vector.path("canonical").asText(), text);
                assertEquals(vector.path("sha256").asText(), ExecutionAssignments.sha256(text));
                if (kind.equals("computation")) assertEquals(text, PhysicalDeployments.encodeComputation(PhysicalDeployments.decodeComputation(text)));
            } else if (vector.path("refusal").isNull()) {
                assertDoesNotThrow(() -> PhysicalDeployments.validate(vector.path("input").asText()));
                assertDoesNotThrow(() -> PhysicalDeployments.admit(vector.path("input").asText(), vector.path("facts"), vector.path("nowMicros").asText()));
            } else {
                PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class,
                        () -> PhysicalDeployments.admit(vector.path("input").asText(), vector.path("facts"), vector.path("nowMicros").asText()));
                assertEquals(vector.path("refusal"), error.document());
            }
        }));
        return tests;
    }
}
