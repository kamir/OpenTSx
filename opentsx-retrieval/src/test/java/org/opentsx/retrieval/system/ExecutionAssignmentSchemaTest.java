package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAssignmentSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static JsonSchema schema() throws IOException {
        JsonNode source = JSON.readTree(ROOT.resolve(
                "docs/datamodel-2026/schemas/opentsx.execution-assignment.v1.schema.json").toFile());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(source);
    }

    private static JsonNode cases() throws IOException {
        return JSON.readTree(ROOT.resolve(
                "docs/datamodel-2026/testvectors/execution-contracts.v1.json").toFile()).path("cases");
    }

    /** The raw assignment of a case, with its {@code expand} marker replaced (see the vector generator). */
    private static String input(JsonNode c) {
        String raw = c.path("input").textValue();
        JsonNode expand = c.path("expand");
        return expand.isMissingNode() ? raw
                : raw.replace(expand.path("marker").textValue(), expand.path("text").textValue().repeat(expand.path("count").intValue()));
    }

    @Test
    void validSharedAssignmentsMatchTheSchema() throws IOException {
        JsonSchema schema = schema();
        int checked = 0;
        for (JsonNode c : cases()) {
            if (!c.path("error").isNull()) {
                continue;
            }
            if (c.path("kind").asText().equals("assignment")) {
                assertTrue(schema.validate(JSON.readTree(input(c))).isEmpty(),
                        c.path("name").textValue());
                checked++;
            } else if (c.path("kind").asText().equals("set")) {
                for (JsonNode input : c.path("inputs")) {
                    assertTrue(schema.validate(JSON.readTree(input.textValue())).isEmpty(),
                            c.path("name").textValue());
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void malformedTypedAndModeFieldsFailTheSchema() throws IOException {
        Set<String> structural = Set.of("unknown-field", "missing-node", "unknown-version", "invalid-node-id",
                "numeric-generation", "zero-fence", "epoch-leading-zero", "unknown-runtime", "unknown-catalog",
                "invalid-artifact", "uppercase-hash", "unknown-mode", "source-text-not-string",
                "source-text-too-large", "empty-inputs", "missing-bounded-end", "continuous-with-end",
                "input-extra-field", "invalid-topic", "numeric-offset-rounding-risk", "negative-offset",
                "offset-leading-zero");
        JsonSchema schema = schema();
        int checked = 0;
        for (JsonNode c : cases()) {
            if (structural.contains(c.path("name").textValue())) {
                assertFalse(schema.validate(JSON.readTree(input(c))).isEmpty(),
                        c.path("name").textValue());
                checked++;
            }
        }
        assertTrue(checked == structural.size());
    }
}
