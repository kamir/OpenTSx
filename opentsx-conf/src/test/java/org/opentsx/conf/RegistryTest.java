package org.opentsx.conf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The shipped registry follows the schema and the registry rules 1 to 8, and the checks reject a broken registry. */
class RegistryTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path SOURCE = ROOT.resolve("opentsx-conf/src/main/resources/org/opentsx/conf/opentsx.config.v1.json");
    static final Path PYTHON_COPY = ROOT.resolve("python-package/opentsx/conf/opentsx.config.v1.json");
    static final Path SCHEMA = ROOT.resolve("docs/datamodel-2026/schemas/opentsx.config.v1.schema.json");
    static final Path VECTORS = ROOT.resolve("docs/datamodel-2026/testvectors/conf.v1.json");
    static final ObjectMapper MAPPER = new ObjectMapper();

    static byte[] resource() throws IOException {
        try (InputStream in = Registry.class.getResourceAsStream(Registry.RESOURCE)) {
            return in.readAllBytes();
        }
    }

    static JsonSchema schema() throws IOException {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(MAPPER.readTree(SCHEMA.toFile()));
    }

    static JsonNode fixture() throws IOException {
        return MAPPER.readTree(VECTORS.toFile()).get("registry");
    }

    @Test
    void theShippedRegistryLoadsWithItsCounts() throws IOException {
        Registry reg = Registry.shipped();
        assertEquals(126, reg.keys().size());
        assertEquals(48, reg.commands().size());
        assertEquals(List.of("opentsx.dwd.httpsProxy", "opentsx.market.entsoeToken", "opentsx.market.ntpClientId",
                        "opentsx.market.ntpClientSecret", "opentsx.openmetadata.token", "opentsx.retrieval.dagRunToken"),
                reg.keys().stream().filter(Registry.Key::secret).map(Registry.Key::key).toList());
        assertArrayEquals(Files.readAllBytes(SOURCE), reg.json());
        assertArrayEquals(resource(), reg.json());
    }

    @Test
    void theRegistryAndTheVectorFixtureMatchTheSchema() throws IOException {
        JsonSchema schema = schema();
        assertEquals(List.of(), List.copyOf(schema.validate(MAPPER.readTree(resource()))));
        assertEquals(List.of(), List.copyOf(schema.validate(fixture())));
    }

    @Test
    void theSchemaRejectsAnEnumWithoutValuesAndASecretWithADefault() throws IOException {
        JsonSchema schema = schema();
        ObjectNode noValues = (ObjectNode) fixture();
        ((ObjectNode) key(noValues, "opentsx.test.mode")).remove("values");
        assertFalse(schema.validate(noValues).isEmpty());
        ObjectNode secretDefault = (ObjectNode) fixture();
        ((ObjectNode) key(secretDefault, "opentsx.test.token")).put("default", "x");
        assertFalse(schema.validate(secretDefault).isEmpty());
    }

    @Test
    void thePythonCopyIsByteIdentical() throws IOException {
        assumeTrue(Files.exists(PYTHON_COPY), PYTHON_COPY + " does not exist yet; the Python package ships it as package data");
        assertArrayEquals(Files.readAllBytes(SOURCE), Files.readAllBytes(PYTHON_COPY));
    }

    @Test
    void theEnvironmentMappingIsInjectiveAndAvoidsTheLauncherVariables() {
        List<String> names = new ArrayList<>();
        for (Registry.Key e : Registry.shipped().keys()) {
            names.addAll(e.envNames());
        }
        Set<String> unique = new HashSet<>(names);
        assertEquals(names.size(), unique.size());
        for (String launcher : List.of("OPENTSX_HOME", "OPENTSX_CONF_DIR", "OPENTSX_EXTRA_ARGS")) {
            assertFalse(unique.contains(launcher), launcher);
        }
        assertTrue(unique.contains("OPENTSX_STATS_SKETCH_ALPHA"));
        assertTrue(unique.contains("KAFSCALE_BOOTSTRAP"));
    }

    @Test
    void twoKeysWithTheSameVariableAreRejected() {
        assertProblem(r -> keys(r).add(MAPPER.createObjectNode().put("key", "opentsx.statsSketch.alpha").put("type", "double")
                        .put("secret", false).put("since", "3.0.1").put("doc", "A clash.")
                        .set("commands", MAPPER.createArrayNode().add("serve"))),
                "environment variable OPENTSX_STATS_SKETCH_ALPHA: used by opentsx.stats.sketchAlpha and opentsx.statsSketch.alpha");
        assertProblem(r -> ((ArrayNode) key(r, "opentsx.test.pin").get("env")).add("KAFSCALE_BOOTSTRAP"),
                "environment variable KAFSCALE_BOOTSTRAP: used by opentsx.kafscale.bootstrap and opentsx.test.pin");
        assertProblem(r -> ((ArrayNode) key(r, "opentsx.test.pin").get("env")).add("OPENTSX_HOME"),
                "environment variable OPENTSX_HOME: launcher variable");
    }

    @Test
    void theRegistryRulesRejectABrokenRegistry() {
        assertProblem(r -> {
            JsonNode first = keys(r).remove(0);
            keys(r).add(first);
        }, "keys not sorted or not unique at opentsx.domain");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.count")).put("key", "opentsx.test.count_x"),
                "opentsx.test.count_x: breaks the key grammar");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.count")).put("key", "opentsx.test"),
                "opentsx.test: prefix of opentsx.test.delayMs");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.count")).put("flag", "text"),
                "serve: --text belongs to opentsx.test.count and opentsx.test.text");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.count")).put("flag", "output"),
                "opentsx.test.count: flag output is not allowed");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.count")).put("default", "six"),
                "default of opentsx.test.count: \"six\" is not an int; expected a whole number from -2147483648 to 2147483647");
        assertProblem(r -> ((ArrayNode) key(r, "opentsx.test.count").get("commands")).add("serves"),
                "opentsx.test.count: command serves is not in commands");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.count")).put("doc", "an int"),
                "opentsx.test.count: doc is not one sentence");
        assertProblem(r -> ((ObjectNode) key(r, "opentsx.test.token")).put("default", "x"),
                "opentsx.test.token: secret with a default");
    }

    @Test
    void theHelpOfStatsIsTheOneInTheSpecification() {
        assertEquals("""
                usage: opentsx stats [options]
                Run the streaming statistics processor on KafScale.
                In the Java runtime, stats parses its own flags and does not read --conf, --properties-file, OPENTSX_CONF_DIR or the canonical OPENTSX_ variables.

                keys:
                  opentsx.domain  string = wind  --domain  OPENTSX_DOMAIN
                      Domain of the topic names tsx.<domain>.<kind>.v2.
                  opentsx.kafscale.bootstrap  string  --kafscale, --bootstrap  OPENTSX_KAFSCALE_BOOTSTRAP, KAFSCALE_BOOTSTRAP
                      Bootstrap address of the KafScale proxy as host:port, several separated by commas.
                  opentsx.stats.allowedLateness  duration = PT0S  --lateness  OPENTSX_STATS_ALLOWED_LATENESS
                      How long a closed window still accepts late episodes.
                  opentsx.stats.checkpoint  path  --checkpoint  OPENTSX_STATS_CHECKPOINT
                      Checkpoint file of the processor; without it the processor starts from the beginning every time.
                  opentsx.stats.observations  boolean = false  --observations  OPENTSX_STATS_OBSERVATIONS
                      Also read the observations topic of the domain.
                  opentsx.stats.once  boolean = false  --once  OPENTSX_STATS_ONCE
                      Process the backlog, close all windows and exit.
                  opentsx.stats.sketchAlpha  double = 0.01  --alpha  OPENTSX_STATS_SKETCH_ALPHA
                      Relative accuracy of the quantile sketch.
                  opentsx.stats.windows  list of duration = PT10M,PT1H  --windows  OPENTSX_STATS_WINDOWS
                      Window lengths of the statistics.

                common options:
                  -c, --conf <key>=<value>    set a key on the command line; repeatable
                  --properties-file <path>    read keys from a properties file or a YAML file (.yaml, .yml)
                  -h, --help                  print this help and exit
                  --version                   print the version and exit
                """, Registry.shipped().help("stats"));
    }

    @Test
    void theHelpOfASecretShowsNoFlag() {
        String help = Registry.shipped().help("metadata");
        assertTrue(help.contains("  opentsx.openmetadata.token  string  OPENTSX_OPENMETADATA_TOKEN, OPENMETADATA_JWT"
                + "  secret: environment or file only\n"), help);
        assertFalse(help.contains("--token"), help);
    }

    static ArrayNode keys(ObjectNode registry) {
        return (ArrayNode) registry.get("keys");
    }

    static JsonNode key(ObjectNode registry, String key) {
        for (JsonNode e : keys(registry)) {
            if (e.get("key").asText().equals(key)) {
                return e;
            }
        }
        throw new AssertionError(key);
    }

    static void assertProblem(Consumer<ObjectNode> change, String problem) {
        ObjectNode registry;
        try {
            registry = (ObjectNode) fixture();
            assertNotEquals(null, Registry.parse(MAPPER.writeValueAsBytes(registry)));
            change.accept(registry);
            byte[] json = MAPPER.writeValueAsBytes(registry);
            String message = assertThrows(IllegalStateException.class, () -> Registry.parse(json)).getMessage();
            assertTrue(message.contains(problem), message);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
