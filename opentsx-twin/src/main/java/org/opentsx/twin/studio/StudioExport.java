package org.opentsx.twin.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.opentsx.twin.Atom;
import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.Inputs;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Snapshot;
import org.opentsx.twin.TwinEvent;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every {@link StudioScenario} (reference, alternatives, forecasts) and writes one JSON file for the Smart
 * Topics Studio ({@code docs/prototypes}). Long runs are downsampled to at most {@link #MAX_POINTS} points per series
 * (bucket means). The output is deterministic.
 * <p>
 * Usage: {@code java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.studio.StudioExport out.json}
 */
public final class StudioExport {

    public static final int MAX_POINTS = 2000;
    private static final int MAX_EVENTS = 300;

    private StudioExport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: StudioExport out.json");
            System.exit(1);
        }
        write(Scenarios.all(), Path.of(args[0]));
    }

    public static void write(List<StudioScenario> scenarios, Path out) throws IOException {
        List<Object> all = new ArrayList<>();
        for (StudioScenario s : scenarios) {
            all.add(export(s));
        }
        new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValue(out.toFile(), Map.of("scenarios", all));
    }

    static Map<String, Object> export(StudioScenario s) {
        TwinPlan plan = s.plan();
        TwinRunner runner = new TwinRunner(plan);
        RunResult ref = runner.run(new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(), null, 0, Map.of(), null));
        int stride = Math.max(1, (int) Math.ceil(ref.size() / (double) MAX_POINTS));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("title", s.title());
        m.put("description", s.description());
        m.put("t0", plan.start().toString());
        m.put("stepMinutes", plan.baseStepMicros() / 60e6 * stride);
        m.put("steps", (ref.size() + stride - 1) / stride);
        m.put("stride", stride);
        m.put("smartMetrics", s.smartMetrics());
        m.put("compareKeys", s.compareKeys());
        m.put("labels", s.labels());
        m.put("markerEvents", s.markerEvents());
        m.put("kpis", s.kpis().stream().map(k -> Map.of("key", k.key(), "label", k.label(), "unit", k.unit(),
                "decimals", k.decimals(), "better", k.better())).toList());
        m.put("graph", graph(plan, s.observed()));

        List<String> outputs = ref.keys().stream().filter(k -> !k.contains(".state.")).toList();
        m.put("reference", run(ref, outputs, stride, plan, 0, s));

        List<Object> alts = new ArrayList<>();
        for (StudioScenario.Alternative a : s.alternatives()) {
            Inputs in = a.inputs() != null ? a.inputs() : s.observed();
            RunResult r = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, in, a.interventions(), null, 0, a.policies(), null));
            Map<String, Object> x = run(r, s.compareKeys(), stride, plan, 0, s);
            x.put("id", a.id());
            x.put("name", a.name());
            alts.add(x);
        }
        m.put("alternatives", alts);

        List<Object> fcs = new ArrayList<>();
        for (StudioScenario.Forecast f : s.forecasts()) {
            Snapshot snap = ref.snapshotAfter(f.nowStep() - 1);
            Inputs in = s.observed() instanceof SeriesInputs si ? si.as(Origin.FORECAST) : s.observed();
            RunResult r = runner.run(new TwinRunner.RunSpec(Mode.FORECAST, in, List.of(), snap,
                    ref.size() - f.nowStep(), f.policies(), null));
            Map<String, Object> x = run(r, s.compareKeys(), stride, plan, f.nowStep(), s);
            x.put("id", f.id());
            x.put("name", f.name());
            x.put("now", f.nowStep() / stride);
            fcs.add(x);
        }
        m.put("forecasts", fcs);
        return m;
    }

    private static Map<String, Object> graph(TwinPlan plan, Inputs observed) {
        List<Atom> atoms = plan.instantiate(AtomCatalog.standard());
        List<Object> nodes = new ArrayList<>();
        for (Atom at : atoms) {
            TwinPlan.AtomSpec spec = plan.atoms().stream().filter(x -> x.id().equals(at.id())).findFirst().orElseThrow();
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", at.id());
            n.put("type", at.type());
            n.put("phase", at.phase().name());
            n.put("every", at.every());
            n.put("inputs", at.inputs().stream().map(p -> Map.of("name", p.name(), "kind", p.kind().name(), "unit", p.unit())).toList());
            n.put("outputs", at.outputs().stream().map(p -> Map.of("name", p.name(), "kind", p.kind().name(), "unit", p.unit())).toList());
            n.put("controls", at.controlDefaults());
            n.put("state", List.copyOf(at.state().keySet()));
            n.put("policy", spec.policy() != null ? spec.policy() : at.defaultPolicy());
            n.put("params", spec.params());
            nodes.add(n);
        }
        List<Object> wires = new ArrayList<>();
        for (TwinPlan.Wire w : plan.resolve(atoms)) {
            wires.add(Map.of("from", w.fromAtom(), "fromPort", w.fromPort(), "to", w.toAtom(), "toPort", w.toPort(),
                    "delayed", w.delayed()));
        }
        return Map.of("nodes", nodes, "wires", wires, "bindings", List.copyOf(observed.ports()), "planHash", plan.hash());
    }

    private static Map<String, Object> run(RunResult r, List<String> keys, int stride, TwinPlan plan, int offsetSteps,
                                           StudioScenario s) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("runId", r.runId());
        x.put("mode", r.mode().name());
        x.put("hash", r.hash());
        Map<String, Object> series = new LinkedHashMap<>();
        Map<String, Object> origin = new LinkedHashMap<>();
        for (String k : keys) {
            series.put(k, downsample(r.series(k), stride));
            Origin o = r.origin(k);
            origin.put(k, o == null ? null : o.name());
        }
        x.put("series", series);
        x.put("origin", origin);
        Map<String, Double> kpis = new LinkedHashMap<>();
        r.kpis().forEach((k, v) -> kpis.put(k, round(v, 4)));
        s.derivedKpis(r).forEach((k, v) -> kpis.put(k, round(v, 4)));
        x.put("kpis", kpis);
        long t0 = plan.startMicros();
        List<Object> ev = new ArrayList<>();
        for (TwinEvent e : r.events()) {
            if (ev.size() >= MAX_EVENTS) {
                break;
            }
            ev.add(Map.of("t", (e.tMicros() - t0) / 60e6, "atom", e.atom(), "type", e.type(), "attrs", e.attrs()));
        }
        x.put("events", ev);
        x.put("eventCount", r.events().size());
        x.put("offset", offsetSteps / stride);
        return x;
    }

    static List<Double> downsample(double[] v, int stride) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < v.length; i += stride) {
            double sum = 0;
            int n = 0;
            for (int j = i; j < Math.min(v.length, i + stride); j++) {
                if (!Double.isNaN(v[j])) {
                    sum += v[j];
                    n++;
                }
            }
            out.add(n == 0 ? null : round(sum / n, 3));
        }
        return out;
    }

    private static Double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
