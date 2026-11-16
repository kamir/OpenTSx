package org.opentsx.retrieval.system;

import org.junit.jupiter.api.Test;
import org.opentsx.retrieval.clean.CleanChain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorLearningAvailabilityTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void existingCleanProcessorDescriptionsReportImplementedAvailability() throws IOException {
        int checked = 0;
        try (var paths = Files.list(ROOT.resolve("systems/processors"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                var processor = SystemDoc.read(file);
                if (CleanChain.OPS.contains(processor.path("dag").path("op").asText())) {
                    assertEquals("available", processor.path("runtimes").path("processor").path("status").asText(),
                            file.toString());
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void operatorManualsNameEveryImplementedCleanOperator() throws IOException {
        for (String page : new String[]{"14-saas-service-layer.md", "16-engineering-method.md"}) {
            String manual = Files.readString(ROOT.resolve("docs/user-manual").resolve(page));
            for (String operator : CleanChain.OPS) {
                assertTrue(manual.contains("`" + operator + "`"), page + " omits " + operator);
            }
        }
    }
}
