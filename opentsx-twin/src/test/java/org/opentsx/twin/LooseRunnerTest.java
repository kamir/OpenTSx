package org.opentsx.twin;

import org.junit.jupiter.api.Test;
import org.opentsx.twin.LooseRunner.Coupling;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LooseRunnerTest {

    private static TwinRunner.RunSpec reference(StudioScenario s, MessageSink sink) {
        return new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(), null, 0, null, sink);
    }

    private static List<String> messages(MessageLog log) {
        return log.messages().stream().sorted(Comparator.comparing(PortMessage::id))
                .map(m -> m.id() + "=" + Double.doubleToLongBits(m.value()) + "/" + m.origin())
                .collect(Collectors.toList());
    }

    /** Conformance: L with staleness 0 equals T bit for bit, whatever the transport does. */
    @Test
    void bspIsBitIdenticalToTightForAllScenariosAndTransports() {
        List<Coupling> transports = List.of(
                Coupling.bsp(),
                Coupling.bsp().withLag(3),
                Coupling.bsp().withJitter(5, 42).withLag("ely", 7),
                Coupling.bsp().withStepTicks("balance", 3).withOutage("market", 10, 60));
        for (StudioScenario s : Scenarios.all()) {
            TwinRunner tight = new TwinRunner(s.plan());
            MessageLog tLog = new MessageLog();
            RunResult t = tight.run(reference(s, tLog));
            for (Coupling c : transports) {
                MessageLog lLog = new MessageLog();
                LooseRunner.LooseRun l = new LooseRunner(tight).run(reference(s, lLog), c);
                assertEquals(t.runId(), l.result().runId(), s.id());
                assertEquals(t.hash(), l.result().hash(), s.id() + " " + c);
                assertEquals(t.events(), l.result().events(), s.id());
                assertEquals(t.kpis(), l.result().kpis(), s.id());
                assertEquals(messages(tLog), messages(lLog), s.id());
                assertEquals(commits(tLog), commits(lLog), s.id());
                assertEquals(0, l.stats().staleReads(), s.id());
            }
        }
    }

    @Test
    void bspAlsoHoldsForWhatIfAndForecastFromSnapshot() {
        StudioScenario s = Scenarios.all().get(0);
        TwinRunner tight = new TwinRunner(s.plan());
        LooseRunner loose = new LooseRunner(tight);
        for (StudioScenario.Alternative a : s.alternatives()) {
            TwinRunner.RunSpec spec = new TwinRunner.RunSpec(Mode.WHAT_IF, a.inputs() == null ? s.observed() : a.inputs(),
                    a.interventions(), null, 0, a.policies(), null);
            assertEquals(tight.run(spec).hash(), loose.run(spec, Coupling.bsp().withLag(2).withJitter(3, 7)).result().hash(),
                    a.id());
        }
        RunResult ref = tight.run(reference(s, null));
        Snapshot snap = ref.snapshotAfter(ref.firstK() + ref.size() / 2);
        TwinRunner.RunSpec fc = new TwinRunner.RunSpec(Mode.FORECAST, s.observed(), List.of(), snap, 60, null, null);
        assertEquals(tight.run(fc).hash(), loose.run(fc, Coupling.bsp().withLag(4)).result().hash());
    }

    @Test
    void lagCostsWallTimeButNotResultUnderBsp() {
        StudioScenario s = Scenarios.all().get(0);
        TwinRunner tight = new TwinRunner(s.plan());
        LooseRunner.LooseRun fast = new LooseRunner(tight).run(reference(s, null), Coupling.bsp());
        LooseRunner.LooseRun slow = new LooseRunner(tight).run(reference(s, null), Coupling.bsp().withLag(2));
        assertEquals(fast.result().hash(), slow.result().hash());
        assertTrue(slow.stats().ticks() > fast.stats().ticks());
    }

    @Test
    void staleCouplingIsFasterDiffersAndIsReproducible() {
        StudioScenario s = Scenarios.all().get(0);
        TwinRunner tight = new TwinRunner(s.plan());
        LooseRunner loose = new LooseRunner(tight);
        Coupling base = Coupling.bsp().withLag(2);
        LooseRunner.LooseRun bsp = loose.run(reference(s, null), base);
        Coupling ssp = new Coupling(2, 2, java.util.Map.of(), 0, 0, java.util.Map.of(), java.util.Map.of());
        LooseRunner.LooseRun a = loose.run(reference(s, null), ssp);
        LooseRunner.LooseRun b = loose.run(reference(s, null), ssp);
        assertEquals(a.result().hash(), b.result().hash());
        assertNotEquals(bsp.result().runId(), a.result().runId());
        assertTrue(a.stats().ticks() < bsp.stats().ticks(), a.stats().ticks() + " vs " + bsp.stats().ticks());
        assertTrue(a.stats().staleReads() > 0);
        assertTrue(a.stats().maxAge() <= 2 + 1, "age bounded by s (+1 for feedback wires)");
    }

    @Test
    void asyncNeverWaitsEvenWhenAnAtomIsDown() {
        StudioScenario s = Scenarios.all().get(0);
        TwinRunner tight = new TwinRunner(s.plan());
        String first = s.plan().atoms().get(0).id();
        LooseRunner.LooseRun r = new LooseRunner(tight).run(reference(s, null),
                Coupling.async().withOutage(first, 0, 50));
        r.stats().waitTicks().values().forEach(w -> assertEquals(0L, w));
        assertEquals(50L, r.stats().downTicks().get(first));
    }

    /** Hybrid: feedback cycles colocated, BSP between processors: T's result at (almost) full pipeline speed. */
    @Test
    void colocatedCyclesKeepTheResultAndReachPipelineThroughput() {
        for (StudioScenario s : Scenarios.all()) {
            TwinRunner tight = new TwinRunner(s.plan());
            RunResult t = tight.run(reference(s, null));
            LooseRunner loose = new LooseRunner(tight);
            LooseRunner.LooseRun plain = loose.run(reference(s, null), Coupling.bsp().withLag(2));
            LooseRunner.LooseRun hybrid = loose.run(reference(s, null), Coupling.bsp().withLag(2).colocatingCycles());
            assertEquals(t.hash(), hybrid.result().hash(), s.id());
            assertTrue(hybrid.stats().ticks() <= t.size() + 3L * s.plan().atoms().size(),
                    s.id() + ": " + hybrid.stats().ticks() + " ticks for " + t.size() + " steps");
            assertTrue(hybrid.stats().ticks() <= plain.stats().ticks(), s.id());
        }
    }

    @Test
    void explicitGroupIsReadWithoutStaleness() {
        StudioScenario s = Scenarios.all().get(0);
        TwinRunner tight = new TwinRunner(s.plan());
        List<String> ids = s.plan().atoms().stream().map(TwinPlan.AtomSpec::id).collect(Collectors.toList());
        LooseRunner.LooseRun all = new LooseRunner(tight).run(reference(s, null),
                Coupling.ssp(3).withLag(2).withGroup("one", ids.toArray(new String[0])));
        assertEquals(0, all.stats().staleReads(), "one processor for everything is variant T");
        assertEquals(tight.run(reference(s, null)).kpis(), all.result().kpis());
    }

    @Test
    void tarjanFindsCycles() {
        // 0 -> 1 -> 2 -> 0, 2 -> 3, 3 -> 4 -> 3, 5 alone
        List<List<Integer>> succ = List.of(List.of(1), List.of(2), List.of(0, 3), List.of(4), List.of(3), List.of());
        int[] c = LooseRunner.Tarjan.components(succ);
        assertEquals(c[0], c[1]);
        assertEquals(c[1], c[2]);
        assertEquals(c[3], c[4]);
        assertNotEquals(c[0], c[3]);
        assertNotEquals(c[5], c[0]);
        assertNotEquals(c[5], c[3]);
    }

    private static List<String> commits(MessageLog log) {
        return log.barriers().stream().filter(Barrier::isCommit).map(b -> b.k() + ":" + b.stateHash())
                .collect(Collectors.toList());
    }
}
