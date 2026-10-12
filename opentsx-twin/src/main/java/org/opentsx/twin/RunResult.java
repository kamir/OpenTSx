package org.opentsx.twin;

import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.Ulid;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The record of one run: per superstep the atom states and outputs, plus events. Series keys are
 * {@code "atom.port"} for outputs and {@code "atom.state.var"} for state variables. A value at step {@code k} describes
 * the end of the interval {@code [t_k, t_k + dt)}.
 */
public final class RunResult {

    record StepRecord(int k, long tMicros, Map<String, Map<String, Double>> states,
                      Map<String, Map<String, Value>> outputs, Map<String, Map<String, Double>> overrides) {
    }

    private final String runId;
    private final Mode mode;
    private final TwinPlan plan;
    private final Map<String, String> atomTypes;
    private final Map<String, Map<String, String>> outputUnits;
    private final List<StepRecord> steps;
    private final List<TwinEvent> events;
    private final String finalHash;

    RunResult(String runId, Mode mode, TwinPlan plan, Map<String, String> atomTypes,
              Map<String, Map<String, String>> outputUnits, List<StepRecord> steps, List<TwinEvent> events,
              String finalHash) {
        this.runId = runId;
        this.mode = mode;
        this.plan = plan;
        this.atomTypes = atomTypes;
        this.outputUnits = outputUnits;
        this.steps = List.copyOf(steps);
        this.events = List.copyOf(events);
        this.finalHash = finalHash;
    }

    public String runId() {
        return runId;
    }

    public Mode mode() {
        return mode;
    }

    /** Hash chained over all states and outputs; equal for bit-identical runs. */
    public String hash() {
        return finalHash;
    }

    public int size() {
        return steps.size();
    }

    public int firstK() {
        return steps.isEmpty() ? 0 : steps.get(0).k();
    }

    public long timeMicros(int i) {
        return steps.get(i).tMicros();
    }

    public long stepMicros() {
        return plan.baseStepMicros();
    }

    /** All series keys. */
    public Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        for (StepRecord r : steps) {
            r.outputs().forEach((a, m) -> m.keySet().forEach(p -> keys.add(a + "." + p)));
            r.states().forEach((a, m) -> m.keySet().forEach(v -> keys.add(a + ".state." + v)));
        }
        return keys;
    }

    /** Values per step; NaN where the atom did not write the port. */
    public double[] series(String key) {
        String[] k = parse(key);
        double[] out = new double[steps.size()];
        for (int i = 0; i < out.length; i++) {
            StepRecord r = steps.get(i);
            Double v = null;
            if (k[1] != null) {
                Map<String, Double> s = r.states().get(k[0]);
                v = s == null ? null : s.get(k[1]);
            } else {
                Map<String, Value> o = r.outputs().get(k[0]);
                Value val = o == null ? null : o.get(k[2]);
                v = val == null ? null : val.v();
            }
            out[i] = v == null ? Double.NaN : v;
        }
        return out;
    }

    /** Origin per step for an output key (null where absent). */
    public Origin[] origins(String key) {
        String[] k = parse(key);
        Origin[] out = new Origin[steps.size()];
        for (int i = 0; i < out.length; i++) {
            Map<String, Value> o = steps.get(i).outputs().get(k[0]);
            Value v = o == null || k[2] == null ? null : o.get(k[2]);
            out[i] = v == null ? null : v.origin();
        }
        return out;
    }

    /** Worst origin of an output over the run. */
    public Origin origin(String key) {
        Origin w = null;
        for (Origin o : origins(key)) {
            w = Origin.worst(w, o);
        }
        return w;
    }

    public double max(String key) {
        return Arrays.stream(series(key)).filter(v -> !Double.isNaN(v)).max().orElse(Double.NaN);
    }

    public double last(String key) {
        double[] s = series(key);
        return s.length == 0 ? Double.NaN : s[s.length - 1];
    }

    public List<TwinEvent> events() {
        return events;
    }

    public List<TwinEvent> events(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }

    public Optional<TwinEvent> firstEvent(String type) {
        return events.stream().filter(e -> e.type().equals(type)).findFirst();
    }

    /** KPIs: the final value of every state variable, {@code "atom.var"}. */
    public Map<String, Double> kpis() {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!steps.isEmpty()) {
            steps.get(steps.size() - 1).states().forEach((a, m) -> m.forEach((v, x) -> out.put(a + "." + v, x)));
        }
        return out;
    }

    /** State to continue from after the step with absolute index {@code k}. */
    public Snapshot snapshotAfter(int k) {
        int i = k - firstK();
        if (i < 0 || i >= steps.size()) {
            throw new IllegalArgumentException("step " + k + " not in run");
        }
        StepRecord r = steps.get(i);
        return new Snapshot(runId, k + 1, r.tMicros() + stepMicros(), r.states(), r.outputs(), r.overrides());
    }

    /**
     * State and output series as v2 episodes: metric {@code twin.<type>.<var>}, tags {@code twin}, {@code atom},
     * {@code run}; labels carry mode and worst origin. Ids are deterministic per run and series.
     */
    public List<Episode> toEpisodes() {
        List<Episode> out = new ArrayList<>();
        if (steps.isEmpty()) {
            return out;
        }
        long t0 = steps.get(0).tMicros();
        for (String key : keys()) {
            String[] k = parse(key);
            String var = k[1] != null ? k[1] : k[2];
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("twin", plan.twin());
            tags.put("atom", k[0]);
            tags.put("run", runId);
            String unit = k[1] == null ? outputUnits.getOrDefault(k[0], Map.of()).get(k[2]) : null;
            String metric = "twin." + atomTypes.get(k[0]) + "." + var;
            Episodes.Draft d = Episodes.regular(unit == null ? SeriesKeys.of(metric, tags) : SeriesKeys.of(metric, tags, unit),
                            t0, stepMicros(), series(key))
                    .episodeId(Ulid.deterministic(t0 / 1000,
                            XxHash64.hash((runId + "|" + key).getBytes(StandardCharsets.UTF_8))))
                    .source("opentsx-twin")
                    .pipeline("twin:" + plan.twin())
                    .label("twin.mode", mode.name())
                    .label("twin.kind", k[1] != null ? "state" : "output");
            Origin o = k[1] == null ? origin(key) : null;
            if (o != null) {
                d.label("twin.origin", o.name());
            }
            out.add(d.build());
        }
        return Collections.unmodifiableList(out);
    }

    /** {atom, stateVar or null, port or null} */
    private static String[] parse(String key) {
        int dot = key.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("key must be 'atom.port' or 'atom.state.var': " + key);
        }
        String atom = key.substring(0, dot);
        String rest = key.substring(dot + 1);
        if (rest.startsWith("state.")) {
            return new String[]{atom, rest.substring(6), null};
        }
        return new String[]{atom, null, rest};
    }
}
