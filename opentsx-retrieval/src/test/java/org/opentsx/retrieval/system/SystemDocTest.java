package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The system documents of the repository are valid: against their JSON schemas and in every reference they make.
 * The second half changes the document in one place at a time and expects the one problem that belongs to it.
 */
class SystemDocTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path H2 = ROOT.resolve("systems/h2-chain.system.yaml");
    static final Path SCHEMAS = ROOT.resolve("docs/datamodel-2026/schemas");
    static final Path CATALOG = ROOT.resolve("systems/catalogs/tsx-catalog-1.yaml");
    static final Path RECORDS = ROOT.resolve("systems/decisions/h2-dispatch.records.example.yaml");
    static final Map<String, Integer> CATALOG_1 = Map.ofEntries(
            Map.entry("decode.avro-soe", 1), Map.entry("dq.plausible-range", 1), Map.entry("convert.unit", 1),
            Map.entry("resample", 1), Map.entry("model.wind-shear", 1), Map.entry("model.power-curve", 1),
            Map.entry("feature.lag", 1), Map.entry("feature.rolling", 1), Map.entry("stats.welford-ddsketch", 1),
            Map.entry("align.vector", 1), Map.entry("segment.threshold", 1), Map.entry("segment.extremum", 1),
            Map.entry("sax.word", 1), Map.entry("pair.xcorr", 1), Map.entry("pair.es", 1), Map.entry("dq.result", 1));

    static JsonSchema schema(String name) throws IOException {
        JsonNode node = new ObjectMapper().readTree(SCHEMAS.resolve(name).toFile());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
    }

    @Test
    void theSystemDocumentsMatchTheirSchema() throws IOException {
        JsonSchema schema = schema("opentsx.system.v1.schema.json");
        try (Stream<Path> files = Files.list(ROOT.resolve("systems"))) {
            List<Path> docs = files.filter(f -> f.toString().endsWith(".system.yaml")).toList();
            assertTrue(docs.contains(H2));
            for (Path f : docs) {
                assertEquals(List.of(), List.copyOf(schema.validate(SystemDoc.read(f))), f.toString());
            }
        }
    }

    @Test
    void theProcessorDescriptionsMatchTheirSchemaAndTheirFileName() throws IOException {
        JsonSchema schema = schema("opentsx.processor.v1.schema.json");
        try (Stream<Path> files = Files.list(ROOT.resolve("systems/processors"))) {
            for (Path f : files.toList()) {
                JsonNode doc = SystemDoc.read(f);
                assertEquals(List.of(), List.copyOf(schema.validate(doc)), f.toString());
                assertEquals(doc.path("id").asText() + ".yaml", f.getFileName().toString());
            }
        }
    }

    static Map<String, JsonNode> descriptions() throws IOException {
        Map<String, JsonNode> descriptions = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("systems/processors"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                descriptions.put(file.getFileName().toString().replaceFirst("\\.yaml$", ""), SystemDoc.read(file));
            }
        }
        return descriptions;
    }

    static Set<String> fields(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    static void repositoryFile(String name, String at) throws IOException {
        assertFalse(name.isBlank(), at);
        Path file = ROOT.resolve(name).normalize();
        assertTrue(file.startsWith(ROOT) && !Path.of(name).isAbsolute(), at);
        assertTrue(Files.isRegularFile(file), at);
        assertTrue(file.toRealPath().startsWith(ROOT.toRealPath()), at);
    }

    static void catalogMatches(JsonNode catalog, Map<String, JsonNode> descriptions) throws IOException {
        assertTrue(catalog.isObject(), "catalog must be an object");
        assertEquals(Set.of("schema", "catalog", "operators"), fields(catalog), "catalog fields");
        assertEquals("tsx-catalog/1", catalog.path("catalog").asText(), "catalog identity");
        assertTrue(catalog.path("operators").isArray(), "catalog operators must be an array");
        Map<String, Integer> versions = new LinkedHashMap<>();
        Map<String, String> frozen = new LinkedHashMap<>();
        for (JsonNode entry : catalog.path("operators")) {
            assertTrue(entry.isObject(), "catalog operator must be an object");
            assertEquals(Set.of("id", "version", "sha256"), fields(entry), "catalog operator fields");
            assertTrue(entry.path("id").isTextual() && !entry.path("id").asText().isBlank(), "catalog operator id");
            assertTrue(entry.path("version").isIntegralNumber() && entry.path("version").canConvertToInt(),
                    "catalog operator version");
            String id = entry.path("id").asText();
            assertNull(versions.putIfAbsent(id, entry.path("version").asInt()), "duplicate catalog operator " + id);
            frozen.put(id, entry.path("sha256").asText());
        }
        assertEquals(CATALOG_1, versions, "catalog 1 identity/version membership");
        JsonSchema processorSchema = schema("opentsx.processor.v1.schema.json");
        Set<String> operators = new LinkedHashSet<>();
        for (var entry : versions.entrySet()) {
            String id = entry.getKey();
            JsonNode description = descriptions.get(id);
            assertNotNull(description, "missing processor description " + id);
            assertEquals(List.of(), List.copyOf(processorSchema.validate(description)), "processor schema " + id);
            assertEquals(id, description.path("id").asText(), "processor filename " + id);
            assertEquals(entry.getValue().intValue(), description.path("version").asInt(), "processor version " + id);
            String op = description.path("dag").path("op").asText(id);
            assertTrue(operators.add(op), "duplicate DAG operator " + op);
            String state = description.path("state").path("kind").asText();
            assertTrue(Set.of("none", "keyed", "windowed").contains(state), "processor state " + id);
            if (!state.equals("none")) {
                assertTrue(description.path("state").path("key").isTextual()
                        && !description.path("state").path("key").asText().isBlank(), "processor state key " + id);
            }
            String merge = description.path("merge").path("class").asText();
            assertTrue(Set.of("none", "exact", "approximate").contains(merge), "processor merge " + id);
            if (!merge.equals("none")) {
                assertTrue(Set.of("sum", "stats.chan-ddsketch").contains(description.path("merge").path("function").asText()),
                        "processor merge function " + id);
            }
            if (merge.equals("approximate")) {
                assertTrue(description.path("merge").path("bound").isTextual()
                        && !description.path("merge").path("bound").asText().isBlank(), "processor merge bound " + id);
            }
            JsonNode wayang = description.path("runtimes").path("wayang");
            assertEquals("available", wayang.path("status").asText(), "processor Wayang availability " + id);
            String implementation = wayang.path("class").asText();
            assertTrue(implementation.matches("org\\.opentsx\\.wayang\\.[A-Za-z][A-Za-z0-9]*"),
                    "processor Wayang class " + id);
            repositoryFile("opentsx-wayang/src/main/java/" + implementation.replace('.', '/') + ".java",
                    "processor Wayang source " + id);
            assertTrue(description.path("testvectors").isTextual(), "processor vectors " + id);
            repositoryFile(description.path("testvectors").asText(), "processor vectors " + id);
            // the vectors define the output (S1.1.3): changed bytes need a new version and a new catalog
            assertEquals(frozen.get(id), sha256(Files.readAllBytes(ROOT.resolve(description.path("testvectors").asText()))),
                    "processor vectors changed since the catalog was frozen " + id);
        }
        String spec = Files.readString(ROOT.resolve("docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md"));
        String section = spec.substring(spec.indexOf("### S1.7 Catalog 1"), spec.indexOf("### S1.8"));
        Set<String> specified = new LinkedHashSet<>();
        var rows = Pattern.compile("^\\| `([^`]+)` \\|", Pattern.MULTILINE).matcher(section);
        while (rows.find()) assertTrue(specified.add(rows.group(1)), "duplicate S1.7 operator");
        assertEquals(16, specified.size(), "S1.7 membership");
        assertEquals(specified, operators, "catalog DAG operators must equal S1.7");
        assertEquals(List.of(), List.copyOf(schema("opentsx.catalog.v1.schema.json").validate(catalog)), "catalog schema");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void refusesCatalog(JsonNode catalog, Map<String, JsonNode> descriptions, String message) {
        AssertionError error = assertThrows(AssertionError.class, () -> catalogMatches(catalog, descriptions));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test
    void catalogOneMatchesSpecAndProcessorDescriptions() throws IOException {
        catalogMatches(SystemDoc.read(CATALOG), descriptions());
    }

    @Test
    void catalogOneRejectsMalformedInventory() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        refusesCatalog(new ObjectMapper().createArrayNode(), descriptions(), "catalog must be an object");
        ObjectNode changed = catalog.deepCopy();
        changed.put("extra", true);
        refusesCatalog(changed, descriptions(), "catalog fields");
        changed = catalog.deepCopy();
        changed.put("catalog", "tsx-catalog/2");
        refusesCatalog(changed, descriptions(), "catalog identity");
        changed = catalog.deepCopy();
        changed.put("operators", "not a list");
        refusesCatalog(changed, descriptions(), "operators must be an array");
        changed = catalog.deepCopy();
        ((ArrayNode) changed.path("operators")).set(0, new ObjectMapper().getNodeFactory().numberNode(1));
        refusesCatalog(changed, descriptions(), "operator must be an object");
        changed = catalog.deepCopy();
        changed.remove("schema");
        refusesCatalog(changed, descriptions(), "catalog fields");
        changed = catalog.deepCopy();
        changed.put("schema", "opentsx.catalog.v2");
        refusesCatalog(changed, descriptions(), "catalog schema");
        changed = catalog.deepCopy();
        ((ObjectNode) changed.path("operators").get(0)).put("sha256", "ABC");
        refusesCatalog(changed, descriptions(), "processor vectors changed since the catalog was frozen decode.avro-soe");
        for (String field : List.of("id", "version", "sha256")) {
            changed = catalog.deepCopy();
            ((ObjectNode) changed.path("operators").get(0)).remove(field);
            refusesCatalog(changed, descriptions(), "operator fields");
        }
        changed = catalog.deepCopy();
        ((ObjectNode) changed.path("operators").get(0)).put("extra", true);
        refusesCatalog(changed, descriptions(), "operator fields");
        changed = catalog.deepCopy();
        ((ObjectNode) changed.path("operators").get(0)).put("version", "1");
        refusesCatalog(changed, descriptions(), "operator version");
    }

    @Test
    void catalogOneRejectsMembershipAndVersionDrift() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        ObjectNode changed = catalog.deepCopy();
        ((ArrayNode) changed.path("operators")).remove(0);
        refusesCatalog(changed, descriptions(), "identity/version membership");
        changed = catalog.deepCopy();
        ((ArrayNode) changed.path("operators")).addObject().put("id", "gen.sine").put("version", 1).put("sha256", "0".repeat(64));
        refusesCatalog(changed, descriptions(), "identity/version membership");
        changed = catalog.deepCopy();
        ((ArrayNode) changed.path("operators")).add(changed.path("operators").get(0).deepCopy());
        refusesCatalog(changed, descriptions(), "duplicate catalog operator");
        changed = catalog.deepCopy();
        ((ObjectNode) changed.path("operators").get(0)).put("version", 2);
        refusesCatalog(changed, descriptions(), "identity/version membership");
        Map<String, JsonNode> processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).put("version", 2);
        refusesCatalog(catalog, processors, "processor version sax.word");
    }

    @Test
    void catalogOneRejectsMissingDescriptionsAndDuplicateDagOperators() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        Map<String, JsonNode> processors = descriptions();
        processors.remove("decode.avro-soe");
        refusesCatalog(catalog, processors, "missing processor description decode.avro-soe");
        processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).put("id", "other");
        refusesCatalog(catalog, processors, "processor filename sax.word");
        processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).putObject("dag").put("op", "resample");
        refusesCatalog(catalog, processors, "duplicate DAG operator resample");
        processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).putObject("dag").put("op", "other");
        refusesCatalog(catalog, processors, "catalog DAG operators must equal S1.7");
    }

    @Test
    void catalogOneRequiresStateAndKeys() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        Map<String, JsonNode> processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).remove("state");
        refusesCatalog(catalog, processors, "processor state sax.word");
        for (String id : List.of("feature.lag", "resample")) {
            processors = descriptions();
            ((ObjectNode) processors.get(id).path("state")).remove("key");
            refusesCatalog(catalog, processors, "processor state key " + id);
        }
    }

    @Test
    void catalogOneRequiresMergeMetadata() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        Map<String, JsonNode> processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).remove("merge");
        refusesCatalog(catalog, processors, "processor merge sax.word");
        for (String id : List.of("segment.threshold", "stats.welford-ddsketch")) {
            processors = descriptions();
            ((ObjectNode) processors.get(id).path("merge")).remove("function");
            refusesCatalog(catalog, processors, "processor merge function " + id);
        }
        processors = descriptions();
        ((ObjectNode) processors.get("stats.welford-ddsketch").path("merge")).remove("bound");
        refusesCatalog(catalog, processors, "processor merge bound stats.welford-ddsketch");
    }

    @Test
    void catalogOneRequiresAvailableWayangSources() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        for (String status : List.of("planned", "none")) {
            Map<String, JsonNode> processors = descriptions();
            ((ObjectNode) processors.get("sax.word").path("runtimes").path("wayang")).put("status", status);
            refusesCatalog(catalog, processors, "processor Wayang availability sax.word");
        }
        Map<String, JsonNode> processors = descriptions();
        ((ObjectNode) processors.get("sax.word").path("runtimes")).remove("wayang");
        ((ObjectNode) processors.get("sax.word").path("runtimes")).putObject("java").put("status", "none");
        refusesCatalog(catalog, processors, "processor Wayang availability sax.word");
        processors = descriptions();
        ((ObjectNode) processors.get("sax.word").path("runtimes").path("wayang")).remove("class");
        refusesCatalog(catalog, processors, "processor Wayang class sax.word");
        processors = descriptions();
        ((ObjectNode) processors.get("sax.word").path("runtimes").path("wayang")).put("class", "org.opentsx.wayang.Missing");
        refusesCatalog(catalog, processors, "processor Wayang source sax.word");
    }

    @Test
    void catalogOneRefusesVectorsThatChangedAfterTheFreeze() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        ObjectNode changed = catalog.deepCopy();
        for (JsonNode entry : changed.path("operators")) {
            if (entry.path("id").asText().equals("pair.es")) {
                ((ObjectNode) entry).put("sha256", "0".repeat(64));
            }
        }
        refusesCatalog(changed, descriptions(), "processor vectors changed since the catalog was frozen pair.es");
        Map<String, JsonNode> processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).put("testvectors", "docs/datamodel-2026/testvectors/pair-es.v1.json");
        refusesCatalog(catalog, processors, "processor vectors changed since the catalog was frozen sax.word");
    }

    @Test
    void catalogOneRequiresRepositoryVectors() throws IOException {
        JsonNode catalog = SystemDoc.read(CATALOG);
        Map<String, JsonNode> processors = descriptions();
        ((ObjectNode) processors.get("sax.word")).remove("testvectors");
        refusesCatalog(catalog, processors, "processor vectors sax.word");
        for (String file : List.of("", "missing.json", "../outside.json", ROOT.resolve("pom.xml").toString())) {
            processors = descriptions();
            ((ObjectNode) processors.get("sax.word")).put("testvectors", file);
            refusesCatalog(catalog, processors, "processor vectors sax.word");
        }
    }

    @Test
    void h2ChainResolvesEveryReference() {
        SystemDoc d = SystemDoc.load(H2, ROOT);
        assertEquals(List.of(), d.problems());
        assertEquals("h2-chain", d.system());
        assertEquals("wind", d.domain());
        assertEquals(16, d.signals().size());
        assertEquals(6, d.flows().size());
        // the expectation of a sensor channel comes from the catalog, where the interval is called sampling
        SystemDoc.Signal power = d.signals().get(0);
        assertEquals("park/T02/WTUR.W", power.id());
        assertEquals("kW", power.expect().unit());
        assertEquals(-100.0, power.expect().min());
        assertEquals(10000.0, power.expect().max());
        assertEquals("PT1S", power.expect().interval());
        assertEquals(List.of("lag_1", "lag_10", "mean_1h", "std_1h"), power.featureNames());
        // the plausible range is the sensor's, from the catalog; the operating range is the system's, from the document
        assertEquals(0.0, power.expect().operatingMin());
        assertEquals(3300.0, power.expect().operatingMax());
        assertEquals("observation", power.role());
        assertTrue(power.meaning().contains("curtailed"));
        // actual generation and available power are two signals with two roles (DECISION-CARD-SPEC.md, section 5)
        assertEquals("observation", d.signals().stream().filter(s -> s.id().equals("park.p_gen_kw")).findFirst().orElseThrow().role());
        assertEquals("estimate", d.signals().stream().filter(s -> s.id().equals("park.p_potential_kw")).findFirst().orElseThrow().role());
        // SYSTEM-DOCUMENT-SPEC.md, section 2.5: the inventory lists the power of T02 but not its wind speed or its state; that is a note, not a problem
        assertEquals(List.of("park/T02/WMET.HorWdSpd: the inventory does not list this series for h2-chain/park/T02",
                "park/T02/WTUR.TurSt: the inventory does not list this series for h2-chain/park/T02"), d.notes());
        assertEquals(16, d.hash().length());
        assertEquals(d.hash(), SystemDoc.load(H2, ROOT).hash());
    }

    /** Loads the document with one piece of text replaced; the references stay relative to the repository. */
    static SystemDoc changed(Path dir, String old, String with) throws IOException {
        String text = Files.readString(H2);
        assertTrue(text.contains(old), "the document no longer holds: " + old);
        return SystemDoc.load(Files.writeString(dir.resolve("changed.system.yaml"), text.replace(old, with)), ROOT);
    }

    static void assertProblem(SystemDoc d, String part) {
        assertTrue(d.problems().stream().anyMatch(p -> p.contains(part)), "expected a problem with \"" + part + "\" in " + d.problems());
    }

    @Test
    void aMistakeInTheDocumentIsNamed(@TempDir Path dir) throws IOException {
        assertProblem(changed(dir, "id: offtake.demand_kgph", "id: offtake.demand_tons"), "has no such port");
        assertProblem(changed(dir, "id: offtake.demand_kgph", "id: pipeline.demand_kgph"), "the plan has no atom pipeline");
        assertProblem(changed(dir, "{ unit: kg/h, min: 0, max: 120", "{ unit: t/h, min: 0, max: 120"), "the plan gives the unit kg/h");
        assertProblem(changed(dir, "channel: wind/WTUR.W", "channel: wind/WTUR.Nope"), "is not in a catalog");
        assertProblem(changed(dir, "channel: wind/WMET.HorWdSpd", "channel: wind/WTUR.W"), "must end on the channel");
        assertProblem(changed(dir, "id: park/T02/WMET.HorWdSpd", "id: park/T99/WMET.HorWdSpd"), "the inventory has no asset h2-chain/park/T99");
        assertProblem(changed(dir, "flow: park-power, output: sum", "flow: no-such-flow, output: sum"), "is not in the list flows");
        assertProblem(changed(dir, "role: estimate", "role: guess"), "role must be one of");
        assertProblem(changed(dir, "operating: { min: 0, max: 3300 }", "operating: { min: 5000, max: 3300 }"), "operating range has its min above its max");
        assertProblem(changed(dir, "flow: park-power, output: sum", "flow: park-power, output: total"), "does not write it from total");
        assertProblem(changed(dir, "names: [lag_1, lag_10", "names: [lag_2, lag_10"), "does not write it from lag_2");
        assertProblem(changed(dir, "id: ely.setpoint_kw", "id: park.p_potential_kw"), "used twice");
        assertProblem(changed(dir, "kind: ANALOG, origin: ESTIMATED", "kind: STATE, origin: ESTIMATED"), "kind must be one of");
        assertProblem(changed(dir, "interval: PT1M, kind: ANALOG, origin: ESTIMATED", "interval: 1min, kind: ANALOG, origin: ESTIMATED"), "is no duration");
        assertProblem(changed(dir, "origin: SYNTHETIC\n        select: { metric: wind.met.wind_speed", "origin: REAL\n        select: { metric: wind.met.wind_speed"), "origin REAL");
        assertProblem(changed(dir, "version: 1.0.0", "version: 9.9.9"), "version 1.0.0 found, 9.9.9 expected");
        assertProblem(changed(dir, "ref: opentsx-twin/src/main/resources/twin/h2-chain.yaml", "ref: ../outside.yaml"), "does not exist below");
    }

    /** Decision cards and their records match their schemas, and a card only names what its system has. */
    @Test
    void theDecisionCardsMatchTheirSchemaAndTheirSystem() throws IOException {
        JsonSchema card = schema("opentsx.decision.v1.schema.json");
        JsonSchema record = schema("opentsx.decision-record.v1.schema.json");
        SystemDoc system = SystemDoc.load(H2, ROOT);
        java.util.Set<String> known = new java.util.TreeSet<>();
        system.signals().forEach(s -> known.add(s.id()));
        system.atoms().forEach(a -> {
            a.inputs().forEach(p -> known.add(a.id() + "." + p.name()));
            a.outputs().forEach(p -> known.add(a.id() + "." + p.name()));
        });
        JsonNode doc = SystemDoc.read(ROOT.resolve("systems/decisions/h2-dispatch.decision.yaml"));
        assertEquals(List.of(), List.copyOf(card.validate(doc)));
        assertEquals(system.system(), doc.path("system").asText());
        for (String part : List.of("levers", "constraints", "needs")) {
            for (JsonNode n : doc.path(part)) {
                assertTrue(known.contains(n.path("signal").asText()), part + " names " + n.path("signal").asText() + ", which the system does not have");
            }
        }
        java.util.Set<String> analyses = new java.util.TreeSet<>();
        doc.path("analyses").forEach(a -> analyses.add(a.path("id").asText()));
        for (JsonNode a : doc.path("alternatives")) {
            assertTrue(analyses.contains(a.path("analysis").asText()), "alternative " + a.path("id").asText() + " names no analysis of the card");
        }
        // the four records of one period follow each other
        JsonNode records = SystemDoc.read(RECORDS);
        assertEquals(4, records.size());
        String previous = null;
        for (JsonNode r : records) {
            assertEquals(List.of(), List.copyOf(record.validate(r)), r.path("id").asText());
            assertEquals(previous, r.path("follows").asText(null), r.path("id").asText());
            previous = r.path("id").asText();
        }
        nobodyActsInShadowOperation(records);
    }

    /** In shadow operation nobody acts on the recommendation: no approval or action follows it, the outcome does. */
    static void nobodyActsInShadowOperation(JsonNode records) {
        Set<String> shadow = new LinkedHashSet<>();
        for (JsonNode r : records) {
            String id = r.path("id").asText();
            if (r.path("shadow").asBoolean(false) || shadow.contains(r.path("follows").asText(""))) {
                assertFalse(Set.of("approval", "action").contains(r.path("kind").asText()), id + " acts on a recommendation in shadow operation");
                shadow.add(id);
            }
        }
    }

    @Test
    void aRecommendationInShadowOperationIsNotActedOn() throws IOException {
        ArrayNode records = (ArrayNode) SystemDoc.read(RECORDS);
        String recommendation = records.get(0).path("id").asText();
        ((ObjectNode) records.get(0)).put("shadow", true);
        AssertionError approved = assertThrows(AssertionError.class, () -> nobodyActsInShadowOperation(records));
        assertTrue(approved.getMessage().contains("apr-h2-dispatch-20261001T0200-v1 acts on a recommendation in shadow operation"), approved.getMessage());
        records.remove(1);
        ((ObjectNode) records.get(1)).put("follows", recommendation);
        AssertionError acted = assertThrows(AssertionError.class, () -> nobodyActsInShadowOperation(records));
        assertTrue(acted.getMessage().contains("act-h2-dispatch-20261001T0200-v1 acts on a recommendation in shadow operation"), acted.getMessage());
        // the outcome compares the recommendation with what happened without it
        records.remove(1);
        ((ObjectNode) records.get(1)).put("follows", recommendation);
        nobodyActsInShadowOperation(records);
    }

    @Test
    void theHashFollowsTheDocument(@TempDir Path dir) throws IOException {
        assertNotEquals(SystemDoc.load(H2, ROOT).hash(), changed(dir, "seed: 1", "seed: 8").hash());
    }

    /** A generator source the runtime cannot run is a problem when the document loads, not first when it is generated. */
    @Test
    void aGeneratorTheRuntimeCannotRunIsAProblem(@TempDir Path dir) throws IOException {
        String params = "params: { mean: 60, amplitude: 30, period: PT6H }";
        assertProblem(changed(dir, "processor: gen.sine", "processor: gen.nope"),
                "offtake.demand_kgph@raw: generator gen.nope is not available in this runtime: it runs [gen.ou, gen.sine]");
        assertProblem(changed(dir, params, "params: { mean: 60, period: PT6H }"), "offtake.demand_kgph@raw: gen.sine needs the parameter amplitude");
        assertProblem(changed(dir, "        " + params + "\n", ""), "offtake.demand_kgph@raw: gen.sine needs the parameter mean");
        assertProblem(changed(dir, params, "params: { mean: \"60\", amplitude: 30, period: PT6H }"), "offtake.demand_kgph@raw: gen.sine needs the parameter mean");
        assertProblem(changed(dir, params, "params: { mean: 60, amplitude: 30, period: 6h }"), "offtake.demand_kgph@raw: gen.sine: period must be a duration like P1D");
        assertProblem(changed(dir, "{ unit: kg/h, min: 0, max: 120, interval: PT1M,", "{ unit: kg/h, min: 0, max: 120,"),
                "offtake.demand_kgph: needs a sampling interval to be generated (expect.interval)");
        assertProblem(changed(dir, "seed: 1\n", "seed: 1\n        select: { metric: wind.turbine.power_active }\n"),
                "offtake.demand_kgph@raw: a generator source has no select");
        // a working copy goes through the same checks
        assertEquals(List.of("marketer.schedule_kw@raw: gen.ou needs the parameter sigma"), generatorBound("{\"mean\":3000}").problems());
        assertEquals(List.of(), generatorBound("{\"mean\":3000,\"sigma\":50}").problems());
    }

    static SystemDoc generatorBound(String params) throws IOException {
        JsonNode source = new ObjectMapper().readTree("{\"kind\":\"generator\",\"processor\":\"gen.ou\",\"params\":" + params + "}");
        return SystemDoc.load(H2, ROOT, List.of(new SystemDoc.Binding("marketer.schedule_kw", "raw", source)));
    }

    /** A period or an interval the generator cannot count in microseconds is a problem, not an exception of the load. */
    @Test
    void aDurationTheGeneratorCannotCountIsAProblem(@TempDir Path dir) throws IOException {
        assertProblem(changed(dir, "period: PT6H", "period: P200000D"),
                "offtake.demand_kgph@raw: gen.sine: period must be above zero and at most PT2562047H47M16.854775807S, about 292 years");
        String expect = "{ unit: kg/h, min: 0, max: 120, interval: PT1M,";
        assertProblem(changed(dir, expect, "{ unit: kg/h, min: 0, max: 120, interval: PT0S,"),
                "offtake.demand_kgph: expect.interval PT0S is below one microsecond");
        assertProblem(changed(dir, expect, "{ unit: kg/h, min: 0, max: 120, interval: P200000D,"),
                "offtake.demand_kgph: expect.interval P200000D is above PT2562047H47M16.854775807S, about 292 years");
    }
}
