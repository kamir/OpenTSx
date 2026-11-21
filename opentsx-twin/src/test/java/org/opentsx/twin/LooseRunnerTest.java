package org.opentsx.twin;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentsx.twin.LooseRunner.Coupling;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LooseRunnerTest {

    /** Per scenario: the atom on a slow processor. */
    private static final Map<String, String> SLOW = Map.of("h2-chain", "grid", "haus", "building", "boot", "boat");

    /**
     * Per scenario: the atom whose outputs come late or not at all. {@code boot} is a pipeline, so its slow processor
     * absorbs an outage of every other atom.
     */
    private static final Map<String, String> REMOTE = Map.of("h2-chain", "ely", "haus", "boiler", "boot", "boat");

    private static TwinRunner.RunSpec reference(StudioScenario s, MessageSink sink) {
        return new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(), null, 0, null, sink);
    }

    private static List<String> messages(MessageLog log) {
        return log.messages().stream().sorted(Comparator.comparing(PortMessage::id))
                .map(m -> m.id() + "=" + Double.doubleToLongBits(m.value()) + "/" + m.origin())
                .collect(Collectors.toList());
    }

    /**
     * Lag, jitter, a slow processor and an outage at once. A step of 10 ticks takes longer than a superstep under this
     * lag and jitter alone, so the slow processor still costs ticks.
     */
    private static Coupling combined(StudioScenario s) {
        return Coupling.bsp().withLag(2).withJitter(3, 7).withStepTicks(SLOW.get(s.id()), 10)
                .withOutage(REMOTE.get(s.id()), 10, 60);
    }

    private static List<Coupling> transports(StudioScenario s) {
        return List.of(
                Coupling.bsp(),
                Coupling.bsp().withLag(3),
                Coupling.bsp().withJitter(5, 42).withLag(REMOTE.get(s.id()), 7),
                Coupling.bsp().withStepTicks(SLOW.get(s.id()), 3),
                Coupling.bsp().withOutage(REMOTE.get(s.id()), 10, 60),
                combined(s));
    }

    static Stream<Named<StudioScenario>> scenarios() {
        return Scenarios.all().stream().map(s -> Named.of(s.id(), s));
    }

    /**
     * Conformance: L with staleness 0 equals T bit for bit, whatever the transport does. Every stressor of a transport
     * names an atom of the plan and costs wall time: each transport takes more ticks than {@code bsp()}, and more than
     * the same transport without any one of its stressors.
     */
    @Test
    void bspIsBitIdenticalToTightForAllScenariosAndTransports() {
        for (StudioScenario s : Scenarios.all()) {
            TwinRunner tight = new TwinRunner(s.plan());
            LooseRunner loose = new LooseRunner(tight);
            MessageLog tLog = new MessageLog();
            RunResult t = tight.run(reference(s, tLog));
            List<String> tMessages = messages(tLog);
            List<String> tCommits = commits(tLog);
            Set<String> atoms = s.plan().atoms().stream().map(TwinPlan.AtomSpec::id).collect(Collectors.toSet());
            Map<Coupling, Long> ticks = new HashMap<>();
            Function<Coupling, Long> ticksOf = c -> ticks.computeIfAbsent(c,
                    x -> loose.run(reference(s, null), x).stats().ticks());
            for (Coupling c : transports(s)) {
                MessageLog lLog = new MessageLog();
                LooseRunner.LooseRun l = loose.run(reference(s, lLog), c);
                assertSameRun(t, tMessages, tCommits, l, lLog, s.id() + " " + c);
                ticks.put(c, l.stats().ticks());
                Stream.of(c.lag(), c.stepTicks(), c.outages()).flatMap(m -> m.keySet().stream())
                        .forEach(a -> assertTrue(atoms.contains(a), s.id() + ": " + a + " is not an atom of the plan"));
                Map<String, Coupling> without = withoutEachStressor(c);
                if (without.isEmpty()) {
                    continue;
                }
                long bsp = ticksOf.apply(Coupling.bsp());
                assertTrue(l.stats().ticks() > bsp, s.id() + ": " + without.keySet() + " take " + l.stats().ticks()
                        + " ticks, bsp() " + bsp);
                without.forEach((stressor, rest) -> assertTrue(l.stats().ticks() > ticksOf.apply(rest),
                        s.id() + ": " + stressor + " adds no tick to " + without.keySet()));
            }
        }
    }

    /**
     * Conformance in the other modes under the combined transport: every what-if alternative, a forecast from a
     * mid-run snapshot and every forecast of the scenario.
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    void bspAlsoHoldsForWhatIfAndForecastFromSnapshot(StudioScenario s) {
        TwinRunner tight = new TwinRunner(s.plan());
        LooseRunner loose = new LooseRunner(tight);
        Coupling c = combined(s);
        for (StudioScenario.Alternative a : s.alternatives()) {
            Inputs in = a.inputs() == null ? s.observed() : a.inputs();
            assertLooseEqualsTight(tight, loose, c, log -> new TwinRunner.RunSpec(Mode.WHAT_IF, in, a.interventions(),
                    null, 0, a.policies(), log), s.id() + " " + a.id());
        }
        RunResult ref = tight.run(reference(s, null));
        Snapshot mid = ref.snapshotAfter(ref.firstK() + ref.size() / 2);
        assertLooseEqualsTight(tight, loose, c, log -> new TwinRunner.RunSpec(Mode.FORECAST, s.observed(), List.of(),
                mid, 60, null, log), s.id() + " forecast from mid-run");
        Inputs forecastInputs = s.observed() instanceof SeriesInputs si ? si.as(Origin.FORECAST) : s.observed();
        for (StudioScenario.Forecast f : s.forecasts()) {
            Snapshot from = ref.snapshotAfter(f.nowStep() - 1);
            assertLooseEqualsTight(tight, loose, c, log -> new TwinRunner.RunSpec(Mode.FORECAST, forecastInputs,
                    List.of(), from, ref.size() - f.nowStep(), f.policies(), log), s.id() + " " + f.id());
        }
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

    /** Same run id, hash, events, KPIs, port messages and commit barriers as T, and no stale read. */
    private static void assertSameRun(RunResult t, List<String> tMessages, List<String> tCommits,
                                      LooseRunner.LooseRun l, MessageLog lLog, String what) {
        assertEquals(t.runId(), l.result().runId(), what);
        assertEquals(t.hash(), l.result().hash(), what);
        assertEquals(t.events(), l.result().events(), what);
        assertEquals(t.kpis(), l.result().kpis(), what);
        assertEquals(tMessages, messages(lLog), what);
        assertEquals(tCommits, commits(lLog), what);
        assertEquals(0, l.stats().staleReads(), what);
    }

    private static void assertLooseEqualsTight(TwinRunner tight, LooseRunner loose, Coupling c,
                                               Function<MessageLog, TwinRunner.RunSpec> spec, String what) {
        MessageLog tLog = new MessageLog();
        RunResult t = tight.run(spec.apply(tLog));
        MessageLog lLog = new MessageLog();
        assertSameRun(t, messages(tLog), commits(tLog), loose.run(spec.apply(lLog), c), lLog, what);
    }

    /** The transport without each one of its stressors, keyed by the stressor. */
    private static Map<String, Coupling> withoutEachStressor(Coupling c) {
        Map<String, Coupling> m = new LinkedHashMap<>();
        if (c.defaultLag() > 0) {
            m.put("lag", new Coupling(c.staleness(), 0, c.lag(), c.jitter(), c.seed(), c.stepTicks(), c.outages(),
                    c.groups(), c.colocateCycles()));
        }
        if (c.jitter() > 0) {
            m.put("jitter", new Coupling(c.staleness(), c.defaultLag(), c.lag(), 0, 0, c.stepTicks(), c.outages(),
                    c.groups(), c.colocateCycles()));
        }
        for (String a : c.lag().keySet()) {
            m.put("lag of " + a, new Coupling(c.staleness(), c.defaultLag(), minus(c.lag(), a), c.jitter(), c.seed(),
                    c.stepTicks(), c.outages(), c.groups(), c.colocateCycles()));
        }
        for (String a : c.stepTicks().keySet()) {
            m.put("step ticks of " + a, new Coupling(c.staleness(), c.defaultLag(), c.lag(), c.jitter(), c.seed(),
                    minus(c.stepTicks(), a), c.outages(), c.groups(), c.colocateCycles()));
        }
        for (String a : c.outages().keySet()) {
            m.put("outage of " + a, new Coupling(c.staleness(), c.defaultLag(), c.lag(), c.jitter(), c.seed(),
                    c.stepTicks(), minus(c.outages(), a), c.groups(), c.colocateCycles()));
        }
        return m;
    }

    private static <V> Map<String, V> minus(Map<String, V> m, String key) {
        Map<String, V> r = new TreeMap<>(m);
        r.remove(key);
        return r;
    }

    private static List<String> commits(MessageLog log) {
        return log.barriers().stream().filter(Barrier::isCommit).map(b -> b.k() + ":" + b.stateHash())
                .collect(Collectors.toList());
    }
}
