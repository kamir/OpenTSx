package org.opentsx.twin;

import org.opentsx.model.XxHash64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Execution variant L: every atom is its own processor with its own superstep clock, coupled only through port
 * messages (the {@link PortMessage} topic contract). There is no global barrier. An atom may step as soon as each
 * wired input has a <em>visible</em> upstream value that is at most {@code staleness} supersteps older than the one
 * variant T would use:
 *
 * <ul>
 *   <li>{@code staleness = 0}: bulk-synchronous. The atom waits for exactly the value T uses; the result is
 *       bit-identical to T for any latency, jitter or outage (only the wall time differs).</li>
 *   <li>{@code staleness = s > 0}: stale-synchronous (SSP). The atom uses the newest visible value, at most
 *       {@code s} steps old, and waits only if none is.</li>
 *   <li>{@link Coupling#async()}: never waits; uses the newest visible value or the resting output.</li>
 * </ul>
 *
 * Wall time is simulated in ticks: a real (non-held) step costs {@code stepTicks} of its atom, and a message
 * produced in tick {@code w} becomes visible to consumers in tick {@code w + 1 + lag (+ jitter)}. Visibility never
 * depends on the order in which atoms are visited within a tick, so a run is deterministic for a given
 * {@link Coupling}. The atom code, policies, interventions, run record and hash are those of variant T
 * ({@link RunSetup}); a coupling that can change the result is part of the run id.
 */
public final class LooseRunner {

    /** How loosely the atoms are coupled and how the transport behaves. Ticks are simulated wall time. */
    public record Coupling(int staleness, int defaultLag, Map<String, Integer> lag, int jitter, long seed,
                           Map<String, Integer> stepTicks, Map<String, List<long[]>> outages,
                           Map<String, String> groups, boolean colocateCycles) {

        /** Staleness value meaning "never wait". */
        public static final int ASYNC = -1;

        public Coupling {
            if (staleness < ASYNC) {
                throw new IllegalArgumentException("staleness must be >= 0 or ASYNC");
            }
            if (defaultLag < 0 || jitter < 0) {
                throw new IllegalArgumentException("lag and jitter must be >= 0");
            }
            lag = Map.copyOf(lag);
            stepTicks = Map.copyOf(stepTicks);
            Map<String, List<long[]>> o = new TreeMap<>();
            outages.forEach((a, l) -> o.put(a, List.copyOf(l)));
            outages = Collections.unmodifiableMap(o);
            groups = Collections.unmodifiableMap(new TreeMap<>(groups));
        }

        public Coupling(int staleness, int defaultLag, Map<String, Integer> lag, int jitter, long seed,
                        Map<String, Integer> stepTicks, Map<String, List<long[]>> outages) {
            this(staleness, defaultLag, lag, jitter, seed, stepTicks, outages, Map.of(), false);
        }

        /** Bulk-synchronous: same result as variant T. */
        public static Coupling bsp() {
            return new Coupling(0, 0, Map.of(), 0, 0, Map.of(), Map.of());
        }

        /** Stale-synchronous with bound {@code s}. */
        public static Coupling ssp(int s) {
            if (s < 0) {
                throw new IllegalArgumentException("s must be >= 0");
            }
            return new Coupling(s, 0, Map.of(), 0, 0, Map.of(), Map.of());
        }

        public static Coupling async() {
            return new Coupling(ASYNC, 0, Map.of(), 0, 0, Map.of(), Map.of());
        }

        public boolean isAsync() {
            return staleness == ASYNC;
        }

        /** Transport latency in ticks for all atoms' outputs. */
        public Coupling withLag(int ticks) {
            return new Coupling(staleness, ticks, lag, jitter, seed, stepTicks, outages, groups, colocateCycles);
        }

        /** Transport latency in ticks for one atom's outputs (e.g. an atom run by another owner). */
        public Coupling withLag(String atom, int ticks) {
            Map<String, Integer> m = new TreeMap<>(lag);
            m.put(atom, ticks);
            return new Coupling(staleness, defaultLag, m, jitter, seed, stepTicks, outages, groups, colocateCycles);
        }

        /** Adds a uniformly random extra latency of 0..{@code max} ticks per message, reproducible via the seed. */
        public Coupling withJitter(int max, long seed) {
            return new Coupling(staleness, defaultLag, lag, max, seed, stepTicks, outages, groups, colocateCycles);
        }

        /** Ticks one real step of the atom takes (a slow processor). */
        public Coupling withStepTicks(String atom, int ticks) {
            if (ticks < 1) {
                throw new IllegalArgumentException("step ticks must be >= 1");
            }
            Map<String, Integer> m = new TreeMap<>(stepTicks);
            m.put(atom, ticks);
            return new Coupling(staleness, defaultLag, lag, jitter, seed, m, outages, groups, colocateCycles);
        }

        /** The atom's processor is down in ticks {@code [fromTick, toTick)}. */
        public Coupling withOutage(String atom, long fromTick, long toTick) {
            Map<String, List<long[]>> m = new TreeMap<>(outages);
            List<long[]> l = new ArrayList<>(m.getOrDefault(atom, List.of()));
            l.add(new long[]{fromTick, toTick});
            m.put(atom, l);
            return new Coupling(staleness, defaultLag, lag, jitter, seed, stepTicks, m, groups, colocateCycles);
        }

        /**
         * Runs the given atoms in one processor: wires between them are read without staleness and without
         * transport latency, like in variant T.
         */
        public Coupling withGroup(String name, String... atoms) {
            Map<String, String> m = new TreeMap<>(groups);
            for (String a : atoms) {
                m.put(a, name);
            }
            return new Coupling(staleness, defaultLag, lag, jitter, seed, stepTicks, outages, m, colocateCycles);
        }

        /**
         * Hybrid: every feedback cycle of the wiring plan (strongly connected component) runs in one processor;
         * only the acyclic wires between them are loose. Explicit groups take precedence.
         */
        public Coupling colocatingCycles() {
            return new Coupling(staleness, defaultLag, lag, jitter, seed, stepTicks, outages, groups, true);
        }

        int lagOf(String atom) {
            return lag.getOrDefault(atom, defaultLag);
        }

        int stepTicksOf(String atom) {
            return stepTicks.getOrDefault(atom, 1);
        }

        boolean down(String atom, long tick) {
            for (long[] o : outages.getOrDefault(atom, List.of())) {
                if (tick >= o[0] && tick < o[1]) {
                    return true;
                }
            }
            return false;
        }

        /** Null if the result equals variant T's (staleness 0), else a canonical description for the run id. */
        String variant() {
            if (staleness == 0) {
                return null; // groups only change who waits, never the values read
            }
            StringBuilder sb = new StringBuilder("L;s=").append(isAsync() ? "async" : Integer.toString(staleness))
                    .append(";lag=").append(defaultLag).append(new TreeMap<>(lag))
                    .append(";jitter=").append(jitter).append('@').append(seed)
                    .append(";stepTicks=").append(new TreeMap<>(stepTicks)).append(";outages=");
            outages.forEach((a, l) -> l.forEach(o -> sb.append(a).append('[').append(o[0]).append(',')
                    .append(o[1]).append(')')));
            sb.append(";groups=").append(groups).append(";cycles=").append(colocateCycles);
            return sb.toString();
        }
    }

    /** Staleness actually used on one wire: how many reads, how old on average and at most (in supersteps). */
    public record WireStats(String wire, long reads, long staleReads, double meanAge, int maxAge) {
    }

    /**
     * What the coupling cost and bought: simulated wall time ({@code ticks}), ticks each atom waited for inputs
     * or was down, and the staleness per wire.
     */
    public record Stats(long ticks, Map<String, Long> waitTicks, Map<String, Long> downTicks, List<WireStats> wires) {

        public long staleReads() {
            return wires.stream().mapToLong(WireStats::staleReads).sum();
        }

        public long reads() {
            return wires.stream().mapToLong(WireStats::reads).sum();
        }

        public int maxAge() {
            return wires.stream().mapToInt(WireStats::maxAge).max().orElse(0);
        }
    }

    public record LooseRun(RunResult result, Stats stats) {
    }

    private final TwinRunner tight;

    public LooseRunner(TwinPlan plan) {
        this(new TwinRunner(plan));
    }

    /** Uses the plan, catalog and policies of an existing variant-T runner. */
    public LooseRunner(TwinRunner tight) {
        this.tight = tight;
    }

    public LooseRun run(TwinRunner.RunSpec spec, Coupling c) {
        TwinRunner.RunSpec s = spec.withVariant(c.variant());
        RunSetup r = RunSetup.prepare(tight.plan(), tight.catalog(), tight.policies(), s);
        int na = r.atoms.size();
        int n = r.n;
        Map<String, Integer> index = new HashMap<>();
        for (int a = 0; a < na; a++) {
            index.put(r.atoms.get(a).id(), a);
        }

        // per atom and step: outputs, visibility tick, state, overrides in force, events
        @SuppressWarnings("unchecked")
        Map<String, Value>[][] outs = new Map[na][n];
        long[][] visible = new long[na][n];
        @SuppressWarnings("unchecked")
        Map<String, Double>[][] states = new Map[na][n];
        @SuppressWarnings("unchecked")
        Map<String, Double>[][] ovs = new Map[na][n];
        @SuppressWarnings("unchecked")
        List<TwinEvent>[][] evs = new List[na][n];
        // intervention events per step with their position in the sorted intervention list (variant T order)
        List<List<long[]>> ivAt = new ArrayList<>();
        List<List<TwinEvent>> ivEv = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ivAt.add(new ArrayList<>());
            ivEv.add(new ArrayList<>());
        }

        int[] next = new int[na];
        long[] busyUntil = new long[na];
        long[] wait = new long[na];
        long[] down = new long[na];
        List<Map<String, Map<String, Double>>> overrides = new ArrayList<>();
        List<List<Integer>> ivOf = new ArrayList<>();
        int[] ivNext = new int[na];
        for (Atom a : r.atoms) {
            Map<String, Map<String, Double>> m = new TreeMap<>();
            Map<String, Double> init = r.initialOverrides.get(a.id());
            if (init != null) {
                m.put(a.id(), new TreeMap<>(init));
            }
            overrides.add(m);
            List<Integer> mine = new ArrayList<>();
            for (int x = 0; x < r.interventions.size(); x++) {
                if (r.interventions.get(x).atom().equals(a.id())) {
                    mine.add(x);
                }
            }
            ivOf.add(mine);
        }
        Map<String, long[]> wireStats = new LinkedHashMap<>(); // reads, staleReads, ageSum, maxAge

        int[] group = groups(r, c, index);
        // visit in variant-T order so that a group member can read a same-step value produced in the same tick
        int[] visit = r.order.stream().mapToInt(x -> index.get(x.id())).toArray();

        int finished = 0;
        long tick = 0;
        while (finished < na) {
            boolean progress = false;
            boolean pendingWork = false;
            for (int a : visit) {
                Atom atom = r.atoms.get(a);
                if (next[a] >= n) {
                    continue;
                }
                if (c.down(atom.id(), tick)) {
                    down[a]++;
                    pendingWork = true;
                    continue;
                }
                if (busyUntil[a] > tick) {
                    pendingWork = true;
                    continue;
                }
                boolean stepped = false;
                while (next[a] < n && !stepped) {
                    int i = next[a];
                    int k = r.k0 + i;
                    long t = r.time(k);
                    Map<String, Value> in = null;
                    Set<String> delayed = null;
                    if (!RunSetup.held(atom, k)) {
                        in = new HashMap<>();
                        delayed = new HashSet<>();
                        if (!gather(r, c, atom, index, group, i, tick, outs, visible, next, in, delayed, wireStats, s)) {
                            wait[a]++;
                            break;
                        }
                    }
                    // interventions in force from this superstep on (same rule as variant T)
                    List<Integer> mine = ivOf.get(a);
                    while (ivNext[a] < mine.size()
                            && r.interventions.get(mine.get(ivNext[a])).tMicros() < t + r.step) {
                        int x = mine.get(ivNext[a]++);
                        ivAt.get(i).add(new long[]{x, ivEv.get(i).size()});
                        ivEv.get(i).add(RunSetup.apply(r.interventions.get(x), overrides.get(a), k, t));
                    }
                    Map<String, Double> ov = overrides.get(a).get(atom.id());
                    if (in == null) {
                        outs[a][i] = i == 0 ? r.initialOutputs.getOrDefault(atom.id(), Map.of()) : outs[a][i - 1];
                        evs[a][i] = List.of();
                    } else {
                        StepContext ctx = r.execute(atom, k, t, in, delayed, ov);
                        Map<String, Value> out = Collections.unmodifiableMap(new LinkedHashMap<>(ctx.outputs()));
                        outs[a][i] = out;
                        evs[a][i] = List.copyOf(ctx.events());
                        for (Map.Entry<String, Value> e : out.entrySet()) {
                            s.sink().message(new PortMessage(r.runId, k, atom.phase(), atom.id(), e.getKey(), t,
                                    e.getValue().v(), e.getValue().origin()));
                        }
                        busyUntil[a] = tick + c.stepTicksOf(atom.id());
                        stepped = true;
                    }
                    visible[a][i] = tick + 1 + c.lagOf(atom.id()) + jitter(c, a, i);
                    states[a][i] = Collections.unmodifiableMap(new LinkedHashMap<>(atom.state()));
                    ovs[a][i] = ov == null ? null : Collections.unmodifiableMap(new TreeMap<>(ov));
                    next[a]++;
                    progress = true;
                    if (next[a] == n) {
                        finished++;
                    }
                }
                if (next[a] < n) {
                    pendingWork = true;
                }
            }
            tick++;
            if (!progress && pendingWork && !anyBusyOrDown(r, c, next, busyUntil, tick, n)
                    && !anyBecomesVisible(visible, next, tick, na)) {
                throw new IllegalStateException("variant L deadlock at tick " + tick + " (plan has a same-step cycle?)");
            }
        }

        // assemble the run record in variant-T order
        List<RunResult.StepRecord> records = new ArrayList<>(n);
        List<TwinEvent> events = new ArrayList<>();
        long chain = r.chainSeed();
        for (int i = 0; i < n; i++) {
            int k = r.k0 + i;
            List<long[]> at = new ArrayList<>(ivAt.get(i));
            at.sort((x, y) -> Long.compare(x[0], y[0]));
            for (long[] e : at) {
                events.add(ivEv.get(i).get((int) e[1]));
            }
            for (Atom atom : r.order) {
                events.addAll(evs[index.get(atom.id())][i]);
            }
            Map<String, Map<String, Double>> st = new LinkedHashMap<>();
            Map<String, Map<String, Value>> out = new LinkedHashMap<>();
            Map<String, Map<String, Double>> ov = new TreeMap<>();
            for (int a = 0; a < na; a++) {
                String id = r.atoms.get(a).id();
                st.put(id, states[a][i]);
                out.put(id, outs[a][i]);
                if (ovs[a][i] != null) {
                    ov.put(id, ovs[a][i]);
                }
            }
            records.add(new RunResult.StepRecord(k, r.time(k), Collections.unmodifiableMap(st),
                    Collections.unmodifiableMap(out), Collections.unmodifiableMap(ov)));
            chain = RunSetup.chain(chain, k, r.atoms, st, out);
            s.sink().barrier(new Barrier(r.runId, k, null, 0, XxHash64.hex(chain)));
        }

        Map<String, Long> waitTicks = new LinkedHashMap<>();
        Map<String, Long> downTicks = new LinkedHashMap<>();
        for (int a = 0; a < na; a++) {
            waitTicks.put(r.atoms.get(a).id(), wait[a]);
            downTicks.put(r.atoms.get(a).id(), down[a]);
        }
        List<WireStats> ws = new ArrayList<>();
        wireStats.forEach((w, v) -> ws.add(new WireStats(w, v[0], v[1], v[0] == 0 ? 0 : (double) v[2] / v[0],
                (int) v[3])));
        return new LooseRun(r.result(tight.plan(), s.mode(), records, events, chain),
                new Stats(tick, waitTicks, downTicks, ws));
    }

    /**
     * Resolves the inputs of {@code atom} for its step {@code i}. Returns false if a wired input has no value that
     * is visible now and recent enough.
     */
    private static boolean gather(RunSetup r, Coupling c, Atom atom, Map<String, Integer> index, int[] group,
                                  int i, long tick,
                                  Map<String, Value>[][] outs, long[][] visible, int[] next, Map<String, Value> in,
                                  Set<String> delayed, Map<String, long[]> wireStats, TwinRunner.RunSpec spec) {
        int k = r.k0 + i;
        long t = r.time(k);
        Map<String, TwinPlan.Wire> wires = r.wireTo.getOrDefault(atom.id(), Map.of());
        Map<String, Value> found = new HashMap<>();
        Map<String, Integer> ages = new HashMap<>();
        for (PortSpec p : atom.inputs()) {
            TwinPlan.Wire w = wires.get(p.name());
            if (w == null) {
                continue;
            }
            int b = index.get(w.fromAtom());
            int target = w.delayed() ? i - 1 : i;
            if (group[b] == group[index.get(atom.id())]) {
                // same processor: exact value, no transport
                if (target >= next[b]) {
                    return false;
                }
                Map<String, Value> src = target < 0 ? r.initialOutputs.get(w.fromAtom()) : outs[b][target];
                found.put(p.name(), src == null ? null : src.get(w.fromPort()));
                ages.put(p.name(), 0);
                continue;
            }
            int lowest = c.isAsync() ? -1 : Math.max(-1, target - c.staleness());
            int j = Math.min(target, next[b] - 1);
            while (j >= lowest && j >= 0 && visible[b][j] > tick) {
                j--;
            }
            if (j < lowest) {
                return false;
            }
            Map<String, Value> src = j < 0 ? r.initialOutputs.get(w.fromAtom()) : outs[b][j];
            found.put(p.name(), src == null ? null : src.get(w.fromPort()));
            ages.put(p.name(), target - j);
        }
        for (PortSpec p : atom.inputs()) {
            TwinPlan.Wire w = wires.get(p.name());
            Value v;
            if (w != null) {
                v = found.get(p.name());
                if (w.delayed()) {
                    delayed.add(p.name());
                }
                int age = ages.get(p.name());
                long[] st = wireStats.computeIfAbsent(w.fromAtom() + "." + w.fromPort() + "->" + atom.id() + "."
                        + p.name(), x -> new long[4]);
                st[0]++;
                if (age > 0) {
                    st[1]++;
                }
                st[2] += age;
                st[3] = Math.max(st[3], age);
            } else {
                v = spec.inputs().value(atom.id() + "." + p.name(), t).orElse(null);
            }
            if (v != null && !Double.isNaN(v.v())) {
                in.put(p.name(), v);
            }
        }
        return true;
    }

    /**
     * Processor of each atom: explicit groups, then (if requested) the strongly connected components of the wire
     * graph with more than one atom, else one processor per atom.
     */
    private static int[] groups(RunSetup r, Coupling c, Map<String, Integer> index) {
        int na = r.atoms.size();
        int[] g = new int[na];
        for (int a = 0; a < na; a++) {
            g[a] = a;
        }
        if (c.colocateCycles()) {
            List<List<Integer>> succ = new ArrayList<>();
            for (int a = 0; a < na; a++) {
                succ.add(new ArrayList<>());
            }
            r.wireTo.forEach((to, m) -> m.values().forEach(w -> succ.get(index.get(w.fromAtom())).add(index.get(to))));
            int[] comp = Tarjan.components(succ);
            for (int a = 0; a < na; a++) {
                g[a] = na + comp[a];
            }
        }
        Map<String, Integer> named = new TreeMap<>();
        for (Map.Entry<String, String> e : c.groups().entrySet()) {
            Integer a = index.get(e.getKey());
            if (a == null) {
                throw new IllegalArgumentException("group member is not an atom: " + e.getKey());
            }
            g[a] = 3 * na + named.computeIfAbsent(e.getValue(), x -> named.size());
        }
        return g;
    }

    /** Strongly connected components (iterative Tarjan); returns the component index of every node. */
    static final class Tarjan {

        static int[] components(List<List<Integer>> succ) {
            int n = succ.size();
            int[] idx = new int[n];
            int[] low = new int[n];
            int[] comp = new int[n];
            boolean[] on = new boolean[n];
            java.util.Arrays.fill(idx, -1);
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            int counter = 0;
            int comps = 0;
            for (int root = 0; root < n; root++) {
                if (idx[root] >= 0) {
                    continue;
                }
                java.util.ArrayDeque<int[]> call = new java.util.ArrayDeque<>();
                call.push(new int[]{root, 0});
                idx[root] = low[root] = counter++;
                stack.push(root);
                on[root] = true;
                while (!call.isEmpty()) {
                    int[] f = call.peek();
                    int v = f[0];
                    if (f[1] < succ.get(v).size()) {
                        int w = succ.get(v).get(f[1]++);
                        if (idx[w] < 0) {
                            idx[w] = low[w] = counter++;
                            stack.push(w);
                            on[w] = true;
                            call.push(new int[]{w, 0});
                        } else if (on[w]) {
                            low[v] = Math.min(low[v], idx[w]);
                        }
                    } else {
                        call.pop();
                        if (!call.isEmpty()) {
                            int u = call.peek()[0];
                            low[u] = Math.min(low[u], low[v]);
                        }
                        if (low[v] == idx[v]) {
                            int w;
                            do {
                                w = stack.pop();
                                on[w] = false;
                                comp[w] = comps;
                            } while (w != v);
                            comps++;
                        }
                    }
                }
            }
            return comp;
        }
    }

    private static int jitter(Coupling c, int atom, int i) {
        if (c.jitter() == 0) {
            return 0;
        }
        return new SplittableRandom(c.seed() * 0x9E3779B97F4A7C15L + atom * 1_000_003L + i).nextInt(c.jitter() + 1);
    }

    private static boolean anyBusyOrDown(RunSetup r, Coupling c, int[] next, long[] busyUntil, long tick, int n) {
        for (int a = 0; a < next.length; a++) {
            if (next[a] >= n) {
                continue;
            }
            if (busyUntil[a] > tick - 1) {
                return true;
            }
            String id = r.atoms.get(a).id();
            for (long[] o : c.outages().getOrDefault(id, List.of())) {
                if (o[1] >= tick) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyBecomesVisible(long[][] visible, int[] next, long tick, int na) {
        for (int a = 0; a < na; a++) {
            for (int j = Math.max(0, next[a] - 1); j >= 0 && j >= next[a] - 64; j--) {
                if (visible[a][j] >= tick) {
                    return true;
                }
            }
        }
        return false;
    }
}
