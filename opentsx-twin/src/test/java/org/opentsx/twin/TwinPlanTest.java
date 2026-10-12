package org.opentsx.twin;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wiring plan validation and the operating-rules atom. */
class TwinPlanTest {

    private static TwinPlan plan(List<String> wiring) {
        return new TwinPlan("t", Instant.parse("2026-10-01T00:00:00Z"), Duration.ofMinutes(1), 10, List.of(
                new TwinPlan.AtomSpec("park", "generation", Map.of(), null),
                new TwinPlan.AtomSpec("ely", "electrolyzer", Map.of(), null),
                new TwinPlan.AtomSpec("grid", "balance", Map.of(), null),
                new TwinPlan.AtomSpec("line", "h2-line", Map.of(), null)), wiring);
    }

    @Test
    void rejectsBrokenWiring() {
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(plan(List.of("park.nope -> grid.p_gen_kw"))));
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(plan(List.of("park.p_gen_kw -> nobody.x"))));
        // unit/kind mismatch: power into a pressure input
        assertThrows(IllegalArgumentException.class,
                () -> new TwinRunner(plan(List.of("park.p_gen_kw -> ely.p_downstream_bar"))));
        // two wires into one input
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(plan(List.of(
                "park.p_gen_kw -> grid.p_gen_kw", "ely.p_el_kw -> grid.p_gen_kw"))));
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(plan(List.of("park.p_gen_kw grid.p_gen_kw"))));
    }

    @Test
    void feedbackWiresAreDelayedByOneSuperstep() {
        TwinPlan p = plan(List.of("ely.m_h2_kgph -> line.m_in_kgph", "line.p_bar -> ely.p_downstream_bar"));
        List<TwinPlan.Wire> wires = p.resolve(p.instantiate(AtomCatalog.standard()));
        assertEquals(false, wires.get(0).delayed());   // ASSETS -> FLOWS, same superstep
        assertEquals(true, wires.get(1).delayed());    // FLOWS -> ASSETS, previous superstep
    }

    @Test
    void rejectsBadBindingsPoliciesAndInterventions() {
        TwinRunner r = new TwinRunner(plan(List.of("park.p_gen_kw -> grid.p_gen_kw")));
        SeriesInputs wired = new SeriesInputs().constant("grid.p_gen_kw", 1, Origin.MEASURED);
        assertThrows(IllegalArgumentException.class,
                () -> r.run(new TwinRunner.RunSpec(Mode.SHADOW, wired, null, null, 0, null, null)));
        SeriesInputs unknown = new SeriesInputs().constant("park.nope", 1, Origin.MEASURED);
        assertThrows(IllegalArgumentException.class,
                () -> r.run(new TwinRunner.RunSpec(Mode.SHADOW, unknown, null, null, 0, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> r.run(new TwinRunner.RunSpec(Mode.SHADOW, null, null, null, 0, Map.of("ely", "magic"), null)));
        Intervention bad = Intervention.set(Instant.parse("2026-10-01T00:05:00Z"), "ely", "nope", 1, "x", "y");
        assertThrows(IllegalArgumentException.class,
                () -> r.run(new TwinRunner.RunSpec(Mode.SHADOW, null, List.of(bad), null, 0, null, null)));
    }

    @Test
    void loadsThePlanFromYamlAndHashesIt() {
        TwinPlan p = S1.plan();
        assertEquals("h2-chain", p.twin());
        assertEquals(60_000_000L, p.baseStepMicros());
        assertEquals(p.hash(), TwinPlan.resource("twin/h2-chain.yaml").hash());
        assertTrue(AtomCatalog.standard().types().containsAll(
                p.atoms().stream().map(TwinPlan.AtomSpec::type).toList()));
    }

    @Test
    void operatingRulesCurtailGeneration() throws IOException {
        String yaml = """
                twin: rules
                start: 2026-10-01T00:00:00Z
                steps: 240
                atoms:
                  - id: rules
                    type: operating-rules
                    params:
                      windows:
                        - { from: "2026-10-01T02:00:00Z", to: "2026-10-01T03:00:00Z", limitKw: 4000, reason: bat curtailment }
                        - { from: "2026-10-01T02:30:00Z", to: "2026-10-01T02:45:00Z", limitKw: 1000, reason: field work }
                  - id: park
                    type: generation
                    params: { ratedKw: 12000 }
                  - id: grid
                    type: balance
                wiring:
                  - rules.limit_kw -> park.limit_kw
                  - park.p_gen_kw -> grid.p_gen_kw
                """;
        TwinPlan p = TwinPlan.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        SeriesInputs in = new SeriesInputs().constant("park.p_avail_kw", 8000, Origin.MEASURED);
        RunResult r = new Twin(p).shadow(in, List.of());
        double[] gen = r.series("park.p_gen_kw");
        assertEquals(8000, gen[119]);
        assertEquals(4000, gen[120]);
        assertEquals(1000, gen[150]);
        assertEquals(4000, gen[165]);
        assertEquals(8000, gen[180]);
        assertEquals(4000 * 0.75 + 7000 * 0.25, r.kpis().get("park.curtailed_kwh"), 1e-6);
        assertEquals(1.0, r.kpis().get("rules.restricted_h"), 1e-9);
        assertEquals("bat curtailment", r.firstEvent("restriction_start").orElseThrow().attrs().get("reason"));
        assertEquals(Origin.REPORTED, r.origin("park.p_gen_kw"));   // a rule is reported, not measured
    }
}
