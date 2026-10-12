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

    /** What to run. {@code from} may be null (start of the plan); {@code steps <= 0} means the plan's steps. */
    public record RunSpec(Mode mode, Inputs inputs, List<Intervention> interventions, Snapshot from, int steps,
                          Map<String, String> policyOverrides, MessageSink sink) {
        public RunSpec {
            inputs = inputs == null ? Inputs.NONE : inputs;
            interventions = interventions == null ? List.of() : List.copyOf(interventions);
            policyOverrides = policyOverrides == null ? Map.of() : Map.copyOf(policyOverrides);
            sink = sink == null ? MessageSink.NONE : sink;
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

    public RunResult run(RunSpec spec) {
        List<Atom> atoms = plan.instantiate(catalog);
        List<TwinPlan.Wire> wires = plan.resolve(atoms);
        Map<String, Atom> byId = new LinkedHashMap<>();
        atoms.forEach(a -> byId.put(a.id(), a));

        // execution order: phase, then plan order
        List<Atom> order = new ArrayList<>(atoms);
        order.sort(Comparator.comparingInt(a -> a.phase().ordinal()));

        Map<String, Map<String, TwinPlan.Wire>> wireTo = new HashMap<>();
        for (TwinPlan.Wire w : wires) {
            wireTo.computeIfAbsent(w.toAtom(), x -> new HashMap<>()).put(w.toPort(), w);
        }
        checkBindings(spec.inputs(), byId, wireTo);

        Map<String, Policy> policyOf = new HashMap<>();
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

        long step = plan.baseStepMicros();
        int k0;
        long t0;
        Map<String, Map<String, Value>> prev = new HashMap<>();
        Map<String, Map<String, Double>> overrides = new TreeMap<>();
        if (spec.from() == null) {
            k0 = 0;
            t0 = plan.startMicros();
            for (Atom a : atoms) {
                Map<String, Value> rest = new LinkedHashMap<>();
                a.restingOutputs().forEach((p, v) -> rest.put(p, new Value(v, Origin.SYNTHETIC)));
                prev.put(a.id(), rest);
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
                prev.put(a.id(), new LinkedHashMap<>(s.outputs().getOrDefault(a.id(), Map.of())));
            }
            s.overrides().forEach((a, m) -> overrides.put(a, new TreeMap<>(m)));
        }
        int n = spec.steps() > 0 ? spec.steps() : plan.steps();
        String runId = runId(spec, n);

        List<Intervention> pending = new ArrayList<>(spec.interventions());
        pending.sort(Comparator.comparingLong(Intervention::tMicros));
        List<RunResult.StepRecord> records = new ArrayList<>(n);
        List<TwinEvent> events = new ArrayList<>();
        long chain = XxHash64.hash(runId.getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < n; i++) {
            int k = k0 + i;
            long t = t0 + i * step;
            while (!pending.isEmpty() && pending.get(0).tMicros() < t + step) {
                Intervention iv = pending.remove(0);
                Map<String, Double> m = overrides.computeIfAbsent(iv.atom(), x -> new TreeMap<>());
                if (iv.value() == null) {
                    m.remove(iv.control());
                } else {
                    m.put(iv.control(), iv.value());
                }
                events.add(new TwinEvent(Math.max(iv.tMicros(), t), k, iv.atom(), "intervention", Map.of(
                        "control", iv.control(), "value", iv.value() == null ? "release" : iv.value().toString(),
                        "author", iv.author(), "reason", iv.reason())));
            }

            Map<String, Map<String, Value>> now = new HashMap<>();
            Phase current = null;
            int count = 0;
            for (Atom a : order) {
                if (a.phase() != current) {
                    if (current != null) {
                        spec.sink().barrier(new Barrier(runId, k, current, count, null));
                    }
                    current = a.phase();
                    count = 0;
                }
                if (Math.floorMod(k, a.every()) != 0) {
                    now.put(a.id(), prev.getOrDefault(a.id(), Map.of()));
                    continue;
                }
                Map<String, Value> in = new HashMap<>();
                Set<String> delayed = new HashSet<>();
                for (PortSpec p : a.inputs()) {
                    TwinPlan.Wire w = wireTo.getOrDefault(a.id(), Map.of()).get(p.name());
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
                StepContext ctx = new StepContext(a.id(), k, t, (double) step * a.every() / 1e6, in, delayed);
                policyOf.get(a.id()).decide(a, ctx);
                overrides.getOrDefault(a.id(), Map.of()).forEach(ctx::setControl);
                a.step(ctx);
                Map<String, Value> out = Collections.unmodifiableMap(new LinkedHashMap<>(ctx.outputs()));
                now.put(a.id(), out);
                events.addAll(ctx.events());
                for (Map.Entry<String, Value> e : out.entrySet()) {
                    spec.sink().message(new PortMessage(runId, k, a.phase(), a.id(), e.getKey(), t,
                            e.getValue().v(), e.getValue().origin()));
                    count++;
                }
            }
            spec.sink().barrier(new Barrier(runId, k, current, count, null));

            Map<String, Map<String, Double>> states = new LinkedHashMap<>();
            for (Atom a : atoms) {
                states.put(a.id(), Collections.unmodifiableMap(new LinkedHashMap<>(a.state())));
            }
            Map<String, Map<String, Double>> ov = new TreeMap<>();
            overrides.forEach((a, m) -> ov.put(a, Collections.unmodifiableMap(new TreeMap<>(m))));
            Map<String, Map<String, Value>> outputs = new LinkedHashMap<>();
            for (Atom a : atoms) {
                outputs.put(a.id(), now.getOrDefault(a.id(), Map.of()));
            }
            records.add(new RunResult.StepRecord(k, t, Collections.unmodifiableMap(states),
                    Collections.unmodifiableMap(outputs), Collections.unmodifiableMap(ov)));
            chain = chain(chain, k, atoms, states, outputs);
            spec.sink().barrier(new Barrier(runId, k, null, 0, XxHash64.hex(chain)));
            prev = now;
        }

        events.sort(Comparator.comparingLong(TwinEvent::tMicros).thenComparingInt(TwinEvent::k));
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Map<String, String>> units = new LinkedHashMap<>();
        for (Atom a : atoms) {
            types.put(a.id(), a.type());
            Map<String, String> u = new LinkedHashMap<>();
            a.outputs().forEach(p -> u.put(p.name(), p.unit()));
            units.put(a.id(), u);
        }
        return new RunResult(runId, spec.mode(), plan, types, units, records, events, XxHash64.hex(chain));
    }

    private void checkBindings(Inputs inputs, Map<String, Atom> byId, Map<String, Map<String, TwinPlan.Wire>> wireTo) {
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

    private String runId(RunSpec spec, int n) {
        StringBuilder sb = new StringBuilder(VERSION).append('|').append(plan.hash()).append('|').append(spec.mode())
                .append('|').append(spec.inputs().fingerprint()).append('|').append(n);
        spec.interventions().forEach(iv -> sb.append('|').append(iv.canonical()));
        new TreeMap<>(spec.policyOverrides()).forEach((a, p) -> sb.append("|policy:").append(a).append('=').append(p));
        if (spec.from() != null) {
            sb.append("|from:").append(spec.from().sourceRunId()).append('@').append(spec.from().k());
        }
        return plan.twin() + "-" + XxHash64.hex(XxHash64.hash(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static long chain(long prev, int k, List<Atom> atoms, Map<String, Map<String, Double>> states,
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
