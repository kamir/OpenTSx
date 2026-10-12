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
import java.util.TreeMap;

/**
 * Execution variant T: all atoms in one process, layered bulk-synchronous supersteps. Within a superstep the phases
 * run in order and each phase ends with a barrier; feedback wires cross the superstep boundary. Same plan, inputs,
 * interventions and code give a bit-identical run (same {@link RunResult#hash()}).
 */
public final class TwinRunner {

    /** Bumped whenever atom dynamics change, so run ids of old and new code differ. */
    public static final String VERSION = "twin-1";

    /**
     * What to run. {@code from} may be null (start of the plan); {@code steps <= 0} means the plan's steps.
     * {@code variant} is null for results that variant T would produce bit-identically; variant L sets it to its
     * coupling when that coupling can change the result, so run ids of different results differ.
     */
    public record RunSpec(Mode mode, Inputs inputs, List<Intervention> interventions, Snapshot from, int steps,
                          Map<String, String> policyOverrides, MessageSink sink, String variant) {
        public RunSpec {
            inputs = inputs == null ? Inputs.NONE : inputs;
            interventions = interventions == null ? List.of() : List.copyOf(interventions);
            policyOverrides = policyOverrides == null ? Map.of() : Map.copyOf(policyOverrides);
            sink = sink == null ? MessageSink.NONE : sink;
        }

        public RunSpec(Mode mode, Inputs inputs, List<Intervention> interventions, Snapshot from, int steps,
                       Map<String, String> policyOverrides, MessageSink sink) {
            this(mode, inputs, interventions, from, steps, policyOverrides, sink, null);
        }

        RunSpec withVariant(String v) {
            return new RunSpec(mode, inputs, interventions, from, steps, policyOverrides, sink, v);
        }
    }

    private final TwinPlan plan;
    private final AtomCatalog catalog;
    private final Policies policies;

    public TwinRunner(TwinPlan plan) {
        this(plan, AtomCatalog.standard(), Policies.standard());
    }

    public TwinRunner(TwinPlan plan, AtomCatalog catalog, Policies policies) {
        this.plan = plan;
        this.catalog = catalog;
        this.policies = policies;
        validate();
    }

    /** Checks the plan once: atoms instantiate, wires resolve, named policies exist. */
    private void validate() {
        List<Atom> atoms = plan.instantiate(catalog);
        plan.resolve(atoms);
        for (TwinPlan.AtomSpec s : plan.atoms()) {
            if (s.policy() != null) {
                policies.get(s.policy());
            }
        }
    }

    public TwinPlan plan() {
        return plan;
    }

    AtomCatalog catalog() {
        return catalog;
    }

    Policies policies() {
        return policies;
    }

    public RunResult run(RunSpec spec) {
        RunSetup r = RunSetup.prepare(plan, catalog, policies, spec);
        Map<String, Map<String, Value>> prev = new HashMap<>(r.initialOutputs);
        Map<String, Map<String, Double>> overrides = new TreeMap<>(r.initialOverrides);
        List<Intervention> pending = new ArrayList<>(r.interventions);
        List<RunResult.StepRecord> records = new ArrayList<>(r.n);
        List<TwinEvent> events = new ArrayList<>();
        long chain = r.chainSeed();

        for (int i = 0; i < r.n; i++) {
            int k = r.k0 + i;
            long t = r.time(k);
            while (!pending.isEmpty() && pending.get(0).tMicros() < t + r.step) {
                events.add(RunSetup.apply(pending.remove(0), overrides, k, t));
            }

            Map<String, Map<String, Value>> now = new HashMap<>();
            Phase current = null;
            int count = 0;
            for (Atom a : r.order) {
                if (a.phase() != current) {
                    if (current != null) {
                        spec.sink().barrier(new Barrier(r.runId, k, current, count, null));
                    }
                    current = a.phase();
                    count = 0;
                }
                if (RunSetup.held(a, k)) {
                    now.put(a.id(), prev.getOrDefault(a.id(), Map.of()));
                    continue;
                }
                Map<String, Value> in = new HashMap<>();
                Set<String> delayed = new HashSet<>();
                for (PortSpec p : a.inputs()) {
                    TwinPlan.Wire w = r.wireTo.getOrDefault(a.id(), Map.of()).get(p.name());
                    Value v;
                    if (w != null) {
                        Map<String, Value> src = w.delayed() ? prev.get(w.fromAtom()) : now.get(w.fromAtom());
                        v = src == null ? null : src.get(w.fromPort());
                        if (w.delayed()) {
                            delayed.add(p.name());
                        }
                    } else {
                        v = spec.inputs().value(a.id() + "." + p.name(), t).orElse(null);
                    }
                    if (v != null && !Double.isNaN(v.v())) {
                        in.put(p.name(), v);
                    }
                }
                StepContext ctx = r.execute(a, k, t, in, delayed, overrides.get(a.id()));
                Map<String, Value> out = Collections.unmodifiableMap(new LinkedHashMap<>(ctx.outputs()));
                now.put(a.id(), out);
                events.addAll(ctx.events());
                for (Map.Entry<String, Value> e : out.entrySet()) {
                    spec.sink().message(new PortMessage(r.runId, k, a.phase(), a.id(), e.getKey(), t,
                            e.getValue().v(), e.getValue().origin()));
                    count++;
                }
            }
            spec.sink().barrier(new Barrier(r.runId, k, current, count, null));

            Map<String, Map<String, Double>> states = r.states();
            Map<String, Map<String, Double>> ov = new TreeMap<>();
            overrides.forEach((a, m) -> ov.put(a, Collections.unmodifiableMap(new TreeMap<>(m))));
            Map<String, Map<String, Value>> outputs = new LinkedHashMap<>();
            for (Atom a : r.atoms) {
                outputs.put(a.id(), now.getOrDefault(a.id(), Map.of()));
            }
            records.add(new RunResult.StepRecord(k, t, Collections.unmodifiableMap(states),
                    Collections.unmodifiableMap(outputs), Collections.unmodifiableMap(ov)));
            chain = RunSetup.chain(chain, k, r.atoms, states, outputs);
            spec.sink().barrier(new Barrier(r.runId, k, null, 0, XxHash64.hex(chain)));
            prev = now;
        }
        return r.result(plan, spec.mode(), records, events, chain);
    }
}
