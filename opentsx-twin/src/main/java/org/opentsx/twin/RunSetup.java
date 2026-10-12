package org.opentsx.twin;

import org.opentsx.model.XxHash64;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What both execution variants share: the instantiated atoms, resolved wires, policies, start point and pending
 * interventions of one run, and the code that executes one atom for one superstep. Variant T ({@link TwinRunner})
 * and variant L ({@link LooseRunner}) differ only in <em>when</em> an atom steps and <em>which</em> upstream values
 * it sees; the step itself is this class.
 */
final class RunSetup {

    final List<Atom> atoms;
    /** Execution order of variant T: phase, then plan order. */
    final List<Atom> order;
    final Map<String, Atom> byId;
    final Map<String, Map<String, TwinPlan.Wire>> wireTo;
    final Map<String, Policy> policyOf;
    final long step;
    final int k0;
    final long t0;
    final int n;
    /** Outputs before the first step: resting outputs or the snapshot's last outputs. */
    final Map<String, Map<String, Value>> initialOutputs;
    /** Interventions in force at the start (from a snapshot). */
    final Map<String, Map<String, Double>> initialOverrides;
    /** Interventions of the run, sorted by time. */
    final List<Intervention> interventions;
    final String runId;

    private RunSetup(TwinPlan plan, AtomCatalog catalog, Policies policies, TwinRunner.RunSpec spec) {
        atoms = plan.instantiate(catalog);
        List<TwinPlan.Wire> wires = plan.resolve(atoms);
        byId = new LinkedHashMap<>();
        atoms.forEach(a -> byId.put(a.id(), a));

        List<Atom> o = new ArrayList<>(atoms);
        o.sort(Comparator.comparingInt(a -> a.phase().ordinal()));
        order = List.copyOf(o);

        wireTo = new HashMap<>();
        for (TwinPlan.Wire w : wires) {
            wireTo.computeIfAbsent(w.toAtom(), x -> new HashMap<>()).put(w.toPort(), w);
        }
        checkBindings(spec.inputs());

        policyOf = new HashMap<>();
        for (TwinPlan.AtomSpec s : plan.atoms()) {
            String name = spec.policyOverrides().getOrDefault(s.id(),
                    s.policy() != null ? s.policy() : byId.get(s.id()).defaultPolicy());
            policyOf.put(s.id(), policies.get(name));
        }
        for (String id : spec.policyOverrides().keySet()) {
            if (!byId.containsKey(id)) {
                throw new IllegalArgumentException("policy override for unknown atom " + id);
            }
        }
        for (Intervention iv : spec.interventions()) {
            Atom a = byId.get(iv.atom());
            if (a == null || !a.controlDefaults().containsKey(iv.control())) {
                throw new IllegalArgumentException("intervention on unknown control " + iv.atom() + "." + iv.control());
            }
        }

        step = plan.baseStepMicros();
        initialOutputs = new HashMap<>();
        initialOverrides = new TreeMap<>();
        if (spec.from() == null) {
            k0 = 0;
            t0 = plan.startMicros();
            for (Atom a : atoms) {
                Map<String, Value> rest = new LinkedHashMap<>();
                a.restingOutputs().forEach((p, v) -> rest.put(p, new Value(v, Origin.SYNTHETIC)));
                initialOutputs.put(a.id(), rest);
            }
        } else {
            Snapshot s = spec.from();
            k0 = s.k();
            t0 = s.tMicros();
            for (Atom a : atoms) {
                Map<String, Double> st = s.states().get(a.id());
                if (st == null) {
                    throw new IllegalArgumentException("snapshot has no state for " + a.id());
                }
                a.restore(st);
                initialOutputs.put(a.id(), new LinkedHashMap<>(s.outputs().getOrDefault(a.id(), Map.of())));
            }
            s.overrides().forEach((a, m) -> initialOverrides.put(a, new TreeMap<>(m)));
        }
        n = spec.steps() > 0 ? spec.steps() : plan.steps();

        List<Intervention> ivs = new ArrayList<>(spec.interventions());
        ivs.sort(Comparator.comparingLong(Intervention::tMicros));
        interventions = List.copyOf(ivs);
        runId = runId(plan, spec, n);
    }

    static RunSetup prepare(TwinPlan plan, AtomCatalog catalog, Policies policies, TwinRunner.RunSpec spec) {
        return new RunSetup(plan, catalog, policies, spec);
    }

    long time(int k) {
        return t0 + (long) (k - k0) * step;
    }

    /** True if the atom is held (multirate) in superstep {@code k} and only repeats its last outputs. */
    static boolean held(Atom a, int k) {
        return Math.floorMod(k, a.every()) != 0;
    }

    /** Applies an intervention to the overrides and returns its event (time clamped to the superstep start). */
    static TwinEvent apply(Intervention iv, Map<String, Map<String, Double>> overrides, int k, long t) {
        Map<String, Double> m = overrides.computeIfAbsent(iv.atom(), x -> new TreeMap<>());
        if (iv.value() == null) {
            m.remove(iv.control());
        } else {
            m.put(iv.control(), iv.value());
        }
        return new TwinEvent(Math.max(iv.tMicros(), t), k, iv.atom(), "intervention", Map.of(
                "control", iv.control(), "value", iv.value() == null ? "release" : iv.value().toString(),
                "author", iv.author(), "reason", iv.reason()));
    }

    /**
     * Executes one atom for one superstep: policy, interventions, dynamics. {@code in} holds the resolved input
     * values (missing or NaN values already removed), {@code delayed} the ports fed by feedback wires.
     */
    StepContext execute(Atom a, int k, long t, Map<String, Value> in, Set<String> delayed,
                        Map<String, Double> overrides) {
        StepContext ctx = new StepContext(a.id(), k, t, (double) step * a.every() / 1e6, in, delayed);
        policyOf.get(a.id()).decide(a, ctx);
        if (overrides != null) {
            overrides.forEach(ctx::setControl);
        }
        a.step(ctx);
        return ctx;
    }

    /** Copy of the current states of all atoms, in plan order. */
    Map<String, Map<String, Double>> states() {
        Map<String, Map<String, Double>> states = new LinkedHashMap<>();
        for (Atom a : atoms) {
            states.put(a.id(), Collections.unmodifiableMap(new LinkedHashMap<>(a.state())));
        }
        return states;
    }

    long chainSeed() {
        return XxHash64.hash(runId.getBytes(StandardCharsets.UTF_8));
    }

    RunResult result(TwinPlan plan, Mode mode, List<RunResult.StepRecord> records, List<TwinEvent> events, long chain) {
        List<TwinEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(TwinEvent::tMicros).thenComparingInt(TwinEvent::k));
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Map<String, String>> units = new LinkedHashMap<>();
        for (Atom a : atoms) {
            types.put(a.id(), a.type());
            Map<String, String> u = new LinkedHashMap<>();
            a.outputs().forEach(p -> u.put(p.name(), p.unit()));
            units.put(a.id(), u);
        }
        return new RunResult(runId, mode, plan, types, units, records, sorted, XxHash64.hex(chain));
    }

    private void checkBindings(Inputs inputs) {
        for (String port : inputs.ports()) {
            int dot = port.indexOf('.');
            Atom a = dot > 0 ? byId.get(port.substring(0, dot)) : null;
            String p = dot > 0 ? port.substring(dot + 1) : port;
            if (a == null || a.inputs().stream().noneMatch(s -> s.name().equals(p))) {
                throw new IllegalArgumentException("binding to unknown input port " + port);
            }
            if (wireTo.getOrDefault(a.id(), Map.of()).containsKey(p)) {
                throw new IllegalArgumentException("binding to wired input port " + port);
            }
        }
    }

    private static String runId(TwinPlan plan, TwinRunner.RunSpec spec, int n) {
        StringBuilder sb = new StringBuilder(TwinRunner.VERSION).append('|').append(plan.hash()).append('|')
                .append(spec.mode()).append('|').append(spec.inputs().fingerprint()).append('|').append(n);
        spec.interventions().forEach(iv -> sb.append('|').append(iv.canonical()));
        new TreeMap<>(spec.policyOverrides()).forEach((a, p) -> sb.append("|policy:").append(a).append('=').append(p));
        if (spec.from() != null) {
            sb.append("|from:").append(spec.from().sourceRunId()).append('@').append(spec.from().k());
        }
        if (spec.variant() != null) {
            sb.append("|variant:").append(spec.variant());
        }
        return plan.twin() + "-" + XxHash64.hex(XxHash64.hash(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Chains the states and outputs of superstep {@code k} into the run hash. */
    static long chain(long prev, int k, List<Atom> atoms, Map<String, Map<String, Double>> states,
                      Map<String, Map<String, Value>> outputs) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(prev);
            out.writeInt(k);
            for (Atom a : atoms) {
                out.writeUTF(a.id());
                for (Map.Entry<String, Double> e : states.get(a.id()).entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeLong(Double.doubleToLongBits(e.getValue()));
                }
                for (Map.Entry<String, Value> e : outputs.get(a.id()).entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeLong(Double.doubleToLongBits(e.getValue().v()));
                    out.writeByte(e.getValue().origin().ordinal());
                }
            }
            return XxHash64.hash(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
