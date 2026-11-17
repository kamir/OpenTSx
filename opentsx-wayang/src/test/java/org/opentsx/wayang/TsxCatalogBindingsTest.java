package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxCatalogBindingsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path SYSTEMS = Path.of("..", "systems");
    private static final Map<Class<?>, Class<?>> EXECUTION = Map.of(
            TsxTopicSource.class, JavaTsxTopicSource.class,
            TsxSeriesChain.class, JavaTsxSeriesChain.class,
            TsxStatsWindow.class, JavaTsxStatsWindow.class,
            TsxAlignVector.class, JavaTsxAlignVector.class,
            TsxSegment.class, JavaTsxSegment.class,
            TsxSaxWord.class, JavaTsxSaxWord.class,
            TsxPair.class, JavaTsxPair.class,
            TsxDqResult.class, JavaTsxDqResult.class);

    private static Class<?> logical(String op) {
        return switch (op) {
            case "decode.avro-soe" -> TsxTopicSource.class;
            case "dq.checks", "convert.unit", "resample", "model.wind-shear", "model.power-curve",
                    "feature.lag", "feature.rolling" -> TsxSeriesChain.class;
            case "stats.welford-ddsketch" -> TsxStatsWindow.class;
            case "align.vector" -> TsxAlignVector.class;
            case "segment.threshold", "segment.extremum" -> TsxSegment.class;
            case "sax.word" -> TsxSaxWord.class;
            case "pair.xcorr", "pair.es" -> TsxPair.class;
            case "dq.result" -> TsxDqResult.class;
            default -> throw new AssertionError("unknown catalog 1 operator " + op);
        };
    }

    private static void binding(JsonNode description) throws ClassNotFoundException {
        String op = description.path("dag").path("op").asText(description.path("id").asText());
        Class<?> declared = Class.forName(description.path("runtimes").path("wayang").path("class").asText());
        assertEquals(logical(op), declared, op);
        assertTrue(Operator.class.isAssignableFrom(declared), op);
        assertTrue(JavaExecutionOperator.class.isAssignableFrom(EXECUTION.get(declared)), op);
    }

    @Test
    void everyFrozenDescriptionNamesItsCompiledWayangBinding() throws Exception {
        JsonNode catalog = YAML.readTree(SYSTEMS.resolve("catalogs/tsx-catalog-1.yaml").toFile());
        assertEquals("tsx-catalog/1", catalog.path("catalog").asText());
        assertEquals(16, catalog.path("operators").size());
        for (JsonNode entry : catalog.path("operators")) {
            binding(YAML.readTree(SYSTEMS.resolve("processors/" + entry.path("id").asText() + ".yaml").toFile()));
        }
    }

    @Test
    void anExistingButIncorrectBindingCannotAdvertiseAvailability() throws Exception {
        ObjectNode description = (ObjectNode) YAML.readTree(SYSTEMS.resolve("processors/stats.welford-ddsketch.yaml").toFile());
        ObjectNode runtime = (ObjectNode) description.path("runtimes").path("wayang");
        runtime.put("class", TsxPair.class.getName());
        assertThrows(AssertionError.class, () -> binding(description));
        runtime.put("class", TsxState.class.getName());
        assertThrows(AssertionError.class, () -> binding(description));
    }
}
