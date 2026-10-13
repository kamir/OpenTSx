package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The system documents of the repository are valid: against their JSON schemas and in every reference they make.
 * The second half changes the document in one place at a time and expects the one problem that belongs to it.
 */
class SystemDocTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path H2 = ROOT.resolve("systems/h2-chain.system.yaml");
    static final Path SCHEMAS = ROOT.resolve("docs/datamodel-2026/schemas");

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

    @Test
    void h2ChainResolvesEveryReference() {
        SystemDoc d = SystemDoc.load(H2, ROOT);
        assertEquals(List.of(), d.problems());
        assertEquals("h2-chain", d.system());
        assertEquals("wind", d.domain());
        assertEquals(13, d.signals().size());
        assertEquals(5, d.flows().size());
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
        // actual generation and available power are two signals with two roles (15, section 9)
        assertEquals("observation", d.signals().stream().filter(s -> s.id().equals("park.p_gen_kw")).findFirst().orElseThrow().role());
        assertEquals("estimate", d.signals().stream().filter(s -> s.id().equals("park.p_potential_kw")).findFirst().orElseThrow().role());
        // D47: the inventory lists the power of T02 but not its wind speed or its state; that is a note, not a problem
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
        JsonNode records = SystemDoc.read(ROOT.resolve("systems/decisions/h2-dispatch.records.example.yaml"));
        assertEquals(4, records.size());
        String previous = null;
        for (JsonNode r : records) {
            assertEquals(List.of(), List.copyOf(record.validate(r)), r.path("id").asText());
            assertEquals(previous, r.path("follows").asText(null), r.path("id").asText());
            previous = r.path("id").asText();
        }
    }

    @Test
    void theHashFollowsTheDocument(@TempDir Path dir) throws IOException {
        assertNotEquals(SystemDoc.load(H2, ROOT).hash(), changed(dir, "seed: 7", "seed: 8").hash());
    }
}
