package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.TopicLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagToWayangErrorsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path CATALOG = Path.of("..", "systems", "processors");
    private static final TopicLayout LAYOUT = new TopicLayout("wind");

    @TestFactory
    Stream<DynamicTest> allNormativeRejections() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Path.of("..", "docs", "datamodel-2026", "testvectors",
                "dag-to-wayang-rejections.v1.json").toFile());
        assertEquals(22, vectors.path("cases").size());
        return StreamSupport.stream(vectors.path("cases").spliterator(), false).map(c ->
                DynamicTest.dynamicTest("message " + c.path("id").asText(), () -> assertEquals(c.path("message").asText(),
                        assertThrows(IllegalArgumentException.class,
                                () -> DagToWayang.compile(c.path("document"), LAYOUT, "spec", CATALOG, context(c))).getMessage())));
    }

    private static TsxCompileContext context(JsonNode c) {
        Map<String, List<TsxCompileContext.Runtime>> assignments = Map.of();
        if (c.has("runtimeTrack")) {
            TsxCompileContext.Runtime runtime = new TsxCompileContext.Runtime(c.path("runtimeTrack").asText(),
                    Map.of("decode.avro-soe", 1), Set.of("none", "keyed", "windowed"));
            assignments = Map.of("decode", List.of(runtime), "n1", List.of(runtime));
        }
        Map<String, TsxCompileContext.Boundary> boundaries = c.path("federated").asBoolean()
                ? Map.of(c.path("document").path("sinks").get(0).path("topic").asText(),
                        new TsxCompileContext.Boundary(c.has("statisticWindow") ? Set.of(c.path("statisticWindow").asText()) : Set.of()))
                : Map.of();
        return new TsxCompileContext(assignments, boundaries,
                c.has("productsLimit") ? OptionalLong.of(c.path("productsLimit").asLong()) : OptionalLong.empty(),
                c.has("productsEstimate") ? Map.of("n1", c.path("productsEstimate").asLong()) : Map.of(),
                Map.of(), Map.of(), "site", OptionalLong.empty(), false);
    }

    private static ObjectNode dag(String op, String params) throws Exception {
        return (ObjectNode) YAML.readTree("""
                schema: opentsx.dag.v2
                dag: flow
                source: { topics: [tsx.wind.episodes.v2], series: [series-1], start: earliest }
                nodes:
                  - { id: decode, op: decode.avro-soe }
                  - { id: n1, op: %s, in: [decode], params: %s }
                sinks:
                  - { from: n1, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2,
                      signal: { system: lab, id: wind, grade: clean } }
                """.formatted(op, params));
    }

    private static void refused(String op, String params, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag(op, params), LAYOUT, "spec", CATALOG));
        assertEquals(message, error.getMessage());
    }

    @Test
    void message13RejectsAMissingRequiredParameter() {
        refused("model.wind-shear", "{toM: 100, alpha: 0.2}", "node n1: parameter fromM is required");
    }

    @Test
    void message14RejectsTheWrongParameterType() {
        refused("model.wind-shear", "{fromM: ten, toM: 100, alpha: 0.2}", "node n1: parameter fromM must be a number");
    }

    @Test
    void message15RejectsAParameterOutsideItsCatalogBounds() {
        refused("feature.lag", "{steps: 0}", "node n1: parameter steps must be between 1 and 100000");
    }

    @Test
    void message16RejectsAnUnknownEnumValue() {
        refused("dq.checks", "{min: 0, max: 10, mode: other}", "node n1: parameter mode must be one of [gap, clip]");
        refused("align.vector", "{every: PT1M, fill: asof}", "node n1: parameter fill must be one of [none, as-of]");
    }

    @Test
    void appliesOnlyOptionalDefaultsAndKeepsTheInputUnchanged() throws Exception {
        ObjectNode input = dag("convert.unit", "{}");
        DagToWayang compiled = DagToWayang.compile(input, LAYOUT, "spec", CATALOG);
        JsonNode params = compiled.dag().path("nodes").get(1).path("params");
        assertEquals(1, params.path("factor").asInt());
        assertEquals(0, params.path("offset").asInt());
        assertEquals("", params.path("unit").asText());
        assertTrue(input.path("nodes").get(1).path("params").isEmpty());
        refused("dq.checks", "{max: 10}", "node n1: parameter min is required");
        refused("dq.checks", "{min: null, max: 10}", "node n1: parameter min must be a number");
        refused("feature.lag", "{steps: 1.5}", "node n1: parameter steps must be a integer");
        refused("resample", "{every: ten}", "node n1: parameter every must be a duration");
        refused("resample", "{every: PT0S}", "node n1: parameter every must be a duration");
    }

    @Test
    void readsBoundsFromTheProcessorFile(@TempDir Path catalog) throws Exception {
        Path directory = copyCatalog(catalog);
        Files.writeString(directory.resolve("feature.lag.yaml"), Files.readString(CATALOG.resolve("feature.lag.yaml"))
                .replace("min: 1, max: 100000", "min: 2, max: 3"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag("feature.lag", "{steps: 1}"), LAYOUT, "spec", directory));
        assertEquals("node n1: parameter steps must be between 2 and 3", error.getMessage());
    }

    @Test
    void checksStructureBeforeParametersAndPreservesCleanChainErrors() throws Exception {
        ObjectNode duplicate = dag("feature.lag", "{steps: 0}");
        ArrayNode sinks = (ArrayNode) duplicate.path("sinks");
        sinks.add(sinks.get(0).deepCopy());
        assertEquals("duplicate sink identity wind", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(duplicate, LAYOUT, "spec", CATALOG)).getMessage());
        ObjectNode missing = dag("feature.lag", "{steps: 0}");
        ((ArrayNode) missing.path("nodes").get(1).path("in")).set(0, YAML.getNodeFactory().textNode("missing"));
        assertEquals("node n1: missing input missing", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(missing, LAYOUT, "spec", CATALOG)).getMessage());
        refused("dq.checks", "{min: 20, max: 10}", "node n1: min is above max");
        refused("dq.checks", "{min: 0, max: 10, checks: [OTHER]}",
                "node n1: this runtime runs the check PLAUSIBLE_RANGE only, not OTHER");
    }

    @Test
    void validatesEveryCatalogNodeBeforeReportingParameterErrors(@TempDir Path catalog) throws Exception {
        Path directory = copyCatalog(catalog);
        ObjectNode planned = (ObjectNode) YAML.readTree(directory.resolve("align.vector.yaml").toFile());
        ((ObjectNode) planned.path("runtimes").path("wayang")).put("status", "planned");
        YAML.writeValue(directory.resolve("align.vector.yaml").toFile(), planned);
        refused("missing.operator", "{}", "node n1: operator missing.operator is not in catalog tsx-catalog/1");
        assertEquals("node n1: operator align.vector version 1 has no runtime for track A",
                assertThrows(IllegalArgumentException.class,
                        () -> DagToWayang.compile(dag("align.vector", "{every: PT1M}"), LAYOUT, "spec", directory)).getMessage());
        for (String op : List.of("missing.operator", "align.vector")) {
            ObjectNode input = dag("model.wind-shear", "{toM: 100, alpha: 0.2}");
            ((ArrayNode) input.path("nodes")).addObject().put("id", "later").put("op", op)
                    .putArray("in").add("decode");
            String expected = op.equals("missing.operator")
                    ? "node later: operator missing.operator is not in catalog tsx-catalog/1"
                    : "node later: operator align.vector version 1 has no runtime for track A";
            assertEquals(expected, assertThrows(IllegalArgumentException.class,
                    () -> DagToWayang.compile(input, LAYOUT, "spec", directory)).getMessage());
        }
    }

    private static Path copyCatalog(Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("processors"));
        try (var files = Files.list(CATALOG)) {
            for (Path file : files.toList()) Files.copy(file, directory.resolve(file.getFileName()));
        }
        Files.createDirectory(root.resolve("catalogs"));
        Files.copy(CATALOG.resolveSibling("catalogs").resolve("tsx-catalog-1.yaml"), root.resolve("catalogs/tsx-catalog-1.yaml"));
        return directory;
    }

    @Test
    void frozenAlignmentIsAvailableDuringParameterValidation() throws Exception {
        JsonNode normalized = DagToWayang.parameters(dag("align.vector", "{every: PT1M}"), CATALOG);
        assertEquals("MEAN", normalized.path("nodes").get(1).path("params").path("agg").asText());
    }

    @Test
    void refusesTwoVectorInputsWithOneNameWhenItCompiles() throws Exception {
        String vector = """
                dag: flow
                source: { topics: [tsx.wind.episodes.v2], series: [series-1], start: earliest }
                nodes:
                  - { id: decode, op: decode.avro-soe }
                  - { id: n1, op: align.vector, in: [decode, decode], params: { every: PT1M, names: [a, a] } }
                sinks:
                  - { from: n1, topic: tsx.wind.smart.v.vectors.v1, schema: opentsx.vector.v1 }
                """;
        assertEquals("node n1: two inputs of the vector are both named \"a\"; give every input its own name",
                assertThrows(IllegalArgumentException.class,
                        () -> DagToWayang.compile(YAML.readTree(vector), LAYOUT, "spec", CATALOG)).getMessage());
        assertEquals("[\"a\",\"b\"]", DagToWayang.compile(YAML.readTree(vector.replace("[a, a]", "[a, b]")), LAYOUT, "spec", CATALOG)
                .dag().path("nodes").get(1).path("params").path("names").toString());
    }

    @Test
    void zeroAlignmentAgeIsValidForTheCurrentTick() throws Exception {
        JsonNode normalized = DagToWayang.parameters(
                dag("align.vector", "{every: PT1M, lateness: PT1S, maxAge: PT0S}"), CATALOG);
        assertEquals("PT0S", normalized.path("nodes").get(1).path("params").path("maxAge").asText());
    }

    @Test
    void rejectsMalformedNodesBeforeParameterObjectCasts() throws Exception {
        ObjectNode input = dag("convert.unit", "{}");
        ((ArrayNode) input.path("nodes")).set(1, YAML.getNodeFactory().numberNode(1));
        assertEquals("every node needs a non-empty string id", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.parameters(input, CATALOG)).getMessage());
        assertEquals("every node needs a non-empty string id", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(input, LAYOUT, "spec", CATALOG)).getMessage());
    }

    @Test
    void validatesStatisticsParametersWithoutAddingAStatisticsSink() throws Exception {
        JsonNode normalized = DagToWayang.parameters(dag("stats.welford-ddsketch", "{}"), CATALOG);
        JsonNode params = normalized.path("nodes").get(1).path("params");
        assertEquals(0.01, params.path("alpha").asDouble());
        assertEquals(List.of("PT10M", "PT1H"), List.of(params.path("windows").get(0).asText(), params.path("windows").get(1).asText()));
        for (String bad : List.of("{alpha: 0}", "{alpha: 1}", "{alpha: -0.1}", "{alpha: 1.1}")) {
            assertEquals("node n1: parameter alpha must be between 0 and 1", assertThrows(IllegalArgumentException.class,
                    () -> DagToWayang.parameters(dag("stats.welford-ddsketch", bad), CATALOG)).getMessage());
        }
        for (String bad : List.of("{windows: [PT0S]}", "{windows: [bad]}", "{windows: []}", "{windows: PT1H}")) {
            assertEquals("node n1: parameter windows must be a list", assertThrows(IllegalArgumentException.class,
                    () -> DagToWayang.parameters(dag("stats.welford-ddsketch", bad), CATALOG)).getMessage());
        }
    }

    @Test
    void buildsOneFusedLogicalOperatorWithTheLastNodeAsStateKey() throws Exception {
        ObjectNode input = dag("dq.checks", "{min: 0, max: 10}");
        ((ArrayNode) input.path("nodes")).addObject().put("id", "minute").put("op", "resample")
                .putArray("in").add("n1");
        ((ObjectNode) input.path("nodes").get(2)).putObject("params").put("every", "PT1M");
        ((ObjectNode) input.path("sinks").get(0)).put("from", "minute");
        DagToWayang compiled = DagToWayang.compile(input, LAYOUT, "spec", CATALOG);
        assertEquals(1, compiled.series().size());
        TsxTopicSource source = new TsxTopicSource(new TsxRead("localhost:9092", LAYOUT.episodes(), Map.of(), Set.of(), "job", 1));
        TsxState state = new TsxState(Map.of());
        TsxTopicSink sink = assertInstanceOf(TsxTopicSink.class, compiled.plan("localhost:9092", "wind").build(source, state));
        TsxSeriesChain chain = assertInstanceOf(TsxSeriesChain.class, sink.getInput(0).getOccupant().getOwner());
        assertEquals("minute", chain.node());
        assertEquals(2, chain.nodes().size());
        assertEquals("MEAN", chain.nodes().get(1).path("params").path("agg").asText());
        assertSame(state, chain.state());
        assertSame(source.getOutput(0), chain.getInput(0).getOccupant());
        assertEquals(LAYOUT.clean(), sink.topic());
        assertThrows(IllegalArgumentException.class, () -> compiled.plan("localhost:9092", "missing"));
    }
}
