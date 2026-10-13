package org.opentsx.retrieval.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Micros;
import org.opentsx.twin.Atom;
import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Snapshot;
import org.opentsx.twin.TwinEvent;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs of a twin on request, and the register that keeps them (docs/datamodel-2026/16-DEMO-UND-SIMULATIONSEXPERTEN.md,
 * section 4, points 1 and 2). A run takes the plan and the inputs of the scenario of a system, the decisions of a
 * person (interventions on controls, a policy for an atom), and gives back a run with an identity: its events, its
 * KPIs and its series can be read again by that identity, and two runs can be compared.
 *
 * <p>What a run is not: it does not read the signals of the system. The inputs are those of the scenario, which are
 * invented; every run says so in {@code inputs} and {@code status}. Binding a run to the series of the signals is a
 * later stage.
 *
 * <p>The register lives in memory and keeps the last {@value #KEEP} runs per system. A run id follows from plan,
 * inputs and decisions, so asking the same question again gives the same run.
 */
public final class Runs {

    public static final String SCHEMA = "opentsx.run.v1";
    static final int KEEP = 100;
    static final int MAX_INTERVENTIONS = 50;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** A run with what was asked for. */
    record Kept(RunResult result, String label, String kind, List<Intervention> log, Map<String, String> policies, String baseline,
                Instant createdAt) {
    }

    private final Map<String, StudioScenario> scenarios = new TreeMap<>();
    private final Map<String, Map<String, Kept>> kept = new TreeMap<>();
    private final Map<String, String> reference = new TreeMap<>();

    /** The scenarios the twin module ships, by the id of their plan; a system has runs when its id is one of them. */
    public static Runs standard() {
        Runs r = new Runs();
        Scenarios.all().forEach(s -> r.scenarios.put(s.id(), s));
        return r;
    }

    public boolean has(String system) {
        return scenarios.containsKey(system);
    }

    /** What a person can ask of the scenario of a system: its controls, policies, reference decisions and KPIs. */
    public synchronized JsonNode scenario(String system) {
        StudioScenario s = scenarios.get(system);
        ObjectNode o = JSON.objectNode();
        o.put("schema", "opentsx.run-scenario.v1");
        o.put("system", system);
        o.put("title", s.title());
        o.put("description", s.description());
        o.put("decision", s.decision());
        o.put("start", s.plan().start().toString());
        o.put("stepSeconds", s.plan().baseStepMicros() / 1_000_000);
        o.put("steps", s.plan().steps());
        o.put("planHash", s.plan().hash());
        o.put("reference", reference(system));
        ArrayNode controls = o.putArray("controls");
        for (Atom a : s.plan().instantiate(AtomCatalog.standard())) {
            new TreeMap<>(a.controlDefaults()).forEach((control, dflt) -> {
                ObjectNode c = controls.addObject();
                c.put("atom", a.id());
                c.put("type", a.type());
                c.put("control", control);
                c.put("default", dflt);
                a.inputs().stream().filter(p -> p.name().equals(control)).findFirst().ifPresent(p -> c.put("unit", p.unit()));
                String label = s.labels().get(a.id() + "." + control);
                if (label != null) {
                    c.put("label", label);
                }
            });
        }
        ArrayNode policies = o.putArray("policies");
        policies.addObject().put("atom", "ely").put("policy", "follow").put("label", "follows its setpoint");
        if (s.plan().atoms().stream().anyMatch(a -> a.id().equals("ely") && a.type().equals("electrolyzer"))) {
            policies.addObject().put("atom", "ely").put("policy", "pressure-aware").put("label", "throttles before the buffer is full");
        } else {
            policies.removeAll();
        }
        ArrayNode ref = o.putArray("referenceDecisions");
        s.reference().forEach(iv -> ref.add(intervention(iv, s)));
        ArrayNode kpis = o.putArray("kpis");
        s.kpis().forEach(k -> kpis.addObject().put("key", k.key()).put("label", k.label()).put("unit", k.unit()).put("decimals", k.decimals()).put("better", k.better()));
        ArrayNode compare = o.putArray("compareKeys");
        s.compareKeys().forEach(compare::add);
        ObjectNode labels = o.putObject("labels");
        s.labels().forEach(labels::put);
        ObjectNode limits = o.putObject("limits");
        s.limits().forEach((key, list) -> {
            ArrayNode l = limits.putArray(key);
            list.forEach(x -> l.addObject().put("label", x.label()).put("value", x.value()));
        });
        return o;
    }

    /** The id of the reference run of a system: the scenario as it is told, computed on first use. */
    public synchronized String reference(String system) {
        return reference.computeIfAbsent(system, id -> {
            StudioScenario s = scenarios.get(id);
            RunResult r = new TwinRunner(s.plan()).run(new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(), null, 0, Map.of(), null));
            keep(id, new Kept(r, "Reference: the scenario as it is told", "reference", s.reference(), Map.of(), null, Instant.now()));
            return r.runId();
        });
    }

    /**
     * Computes a run and keeps it.
     *
     * @param body {@code {label?, base?: "reference"|"empty", interventions?: [{at, atom, control, value|"release", reason?}],
     *             policies?: {atom: policy}, from?: minute}}. With {@code from} the run is a forecast that continues the
     *             reference run from that minute; without it the whole horizon is computed.
     * @throws IllegalArgumentException with a message for the person who asked
     */
    public synchronized JsonNode run(String system, JsonNode body) {
        StudioScenario s = scenarios.get(system);
        String ref = reference(system);
        boolean onReference = !"empty".equals(body.path("base").asText("reference"));
        List<Intervention> asked = interventions(s, body.path("interventions"));
        Map<String, String> policies = new TreeMap<>();
        body.path("policies").fields().forEachRemaining(e -> policies.put(e.getKey(), e.getValue().asText()));
        policies.values().removeIf(p -> p.equals("follow"));
        List<Intervention> log = new ArrayList<>(onReference ? s.reference() : List.of());
        log.addAll(asked);
        TwinRunner runner = new TwinRunner(s.plan());
        RunResult r;
        String kind;
        if (body.hasNonNull("from")) {
            int from = body.path("from").asInt();
            if (from < 1 || from >= s.plan().steps()) {
                throw new IllegalArgumentException("from is a minute inside the run: 1 to " + (s.plan().steps() - 1));
            }
            Snapshot at = kept.get(system).get(ref).result().snapshotAfter(from - 1);
            long t = Micros.of(s.plan().start()) + from * s.plan().baseStepMicros();
            List<Intervention> planned = asked.stream().filter(iv -> iv.tMicros() >= t).toList();
            if (planned.size() != asked.size()) {
                throw new IllegalArgumentException("a forecast from minute " + from + " cannot change what was decided before it");
            }
            Inputs in = s.observed() instanceof SeriesInputs series ? series.as(Origin.FORECAST) : s.observed();
            r = runner.run(new TwinRunner.RunSpec(Mode.FORECAST, in, planned, at, s.plan().steps() - from, policies, null));
            log = planned;
            kind = "forecast";
        } else {
            r = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, s.observed(), log, null, 0, policies, null));
            kind = "what-if";
        }
        String label = body.path("label").asText("").strip();
        keep(system, new Kept(r, label.isEmpty() ? describe(asked, policies, body) : label, kind, log, policies, ref, Instant.now()));
        return doc(system, r.runId());
    }

    private static String describe(List<Intervention> asked, Map<String, String> policies, JsonNode body) {
        List<String> parts = new ArrayList<>();
        asked.forEach(iv -> parts.add(iv.atom() + "." + iv.control() + " = " + (iv.value() == null ? "released" : num(iv.value())) + " at " + clock(iv.tMicros())));
        policies.forEach((atom, p) -> parts.add(atom + " runs " + p));
        if (body.hasNonNull("from")) {
            parts.add(0, "forecast from minute " + body.path("from").asInt());
        }
        return parts.isEmpty() ? "No change against the reference" : String.join("; ", parts);
    }

    private List<Intervention> interventions(StudioScenario s, JsonNode list) {
        if (list.size() > MAX_INTERVENTIONS) {
            throw new IllegalArgumentException("at most " + MAX_INTERVENTIONS + " interventions in one run");
        }
        List<Intervention> out = new ArrayList<>();
        for (JsonNode n : list) {
            Instant at = time(s, n.path("at"));
            String atom = n.path("atom").asText("");
            String control = n.path("control").asText("");
            String reason = n.path("reason").asText("asked by a person");
            if (atom.isBlank() || control.isBlank()) {
                throw new IllegalArgumentException("an intervention names the atom and the control it sets");
            }
            JsonNode v = n.path("value");
            if (v.isTextual() && v.asText().equals("release")) {
                out.add(Intervention.release(at, atom, control, "run-service", reason));
            } else if (v.isNumber()) {
                out.add(Intervention.set(at, atom, control, v.asDouble(), "run-service", reason));
            } else {
                throw new IllegalArgumentException("the value of " + atom + "." + control + " is a number, or \"release\"");
            }
        }
        return out;
    }

    /** A time of the run: minutes after the start as a number, {@code HH:MM} of the run's day, or an instant. */
    private static Instant time(StudioScenario s, JsonNode at) {
        Instant start = s.plan().start();
        long horizon = s.plan().steps() * (s.plan().baseStepMicros() / 1_000_000);
        Instant t;
        try {
            if (at.isNumber()) {
                t = start.plusSeconds(Math.round(at.asDouble() * 60));
            } else if (at.asText("").matches("\\d{1,2}:\\d{2}")) {
                String[] hm = at.asText().split(":");
                t = start.plusSeconds(Integer.parseInt(hm[0]) * 3600L + Integer.parseInt(hm[1]) * 60L);
            } else {
                t = Instant.parse(at.asText(""));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the time of an intervention is minutes after the start, HH:MM, or an instant; got " + at);
        }
        if (t.isBefore(start) || !t.isBefore(start.plusSeconds(horizon))) {
            throw new IllegalArgumentException("the run goes from " + start + " for " + horizon / 60 + " minutes; " + at + " lies outside");
        }
        return t;
    }

    private void keep(String system, Kept k) {
        Map<String, Kept> runs = kept.computeIfAbsent(system, x -> new LinkedHashMap<>());
        runs.remove(k.result().runId());
        runs.put(k.result().runId(), k);
        String ref = reference.get(system);
        while (runs.size() > KEEP) {
            String oldest = runs.keySet().stream().filter(id -> !id.equals(ref)).findFirst().orElseThrow();
            runs.remove(oldest);
        }
    }

    private Kept find(String system, String run) {
        reference(system);
        Kept k = kept.get(system).get(run);
        if (k == null) {
            throw new java.util.NoSuchElementException("no run " + run + " of " + system + "; the register keeps the last " + KEEP + " and forgets them when the server restarts");
        }
        return k;
    }

    /** The runs kept for a system, newest first. */
    public synchronized JsonNode list(String system) {
        reference(system);
        StudioScenario s = scenarios.get(system);
        ObjectNode out = JSON.objectNode();
        out.put("schema", "opentsx.run-list.v1");
        out.put("system", system);
        out.put("reference", reference.get(system));
        ArrayNode runs = out.putArray("runs");
        List<Kept> all = new ArrayList<>(kept.get(system).values());
        for (int i = all.size() - 1; i >= 0; i--) {
            Kept k = all.get(i);
            ObjectNode o = runs.addObject();
            head(o, system, k);
            ObjectNode kpis = o.putObject("kpis");
            s.headline().forEach(key -> kpis.put(key, k.result().kpis().getOrDefault(key, Double.NaN)));
        }
        return out;
    }

    private static void head(ObjectNode o, String system, Kept k) {
        o.put("run", k.result().runId());
        o.put("system", system);
        o.put("kind", k.kind());
        o.put("mode", k.result().mode().name());
        o.put("label", k.label());
        o.put("hash", k.result().hash());
        o.put("createdAt", k.createdAt().toString());
        if (k.baseline() != null) {
            o.put("baseline", k.baseline());
        }
    }

    /** One run: what was asked, what happened, and how far its results may be trusted. */
    public synchronized JsonNode doc(String system, String run) {
        Kept k = find(system, run);
        StudioScenario s = scenarios.get(system);
        RunResult r = k.result();
        ObjectNode o = JSON.objectNode();
        o.put("schema", SCHEMA);
        head(o, system, k);
        o.put("planHash", s.plan().hash());
        o.put("start", Micros.toInstant(r.timeMicros(0)).toString());
        o.put("firstStep", r.firstK());
        o.put("steps", r.size());
        o.put("stepSeconds", r.stepMicros() / 1_000_000);
        ArrayNode decisions = o.putArray("decisions");
        k.log().forEach(iv -> decisions.add(intervention(iv, s)));
        ObjectNode policies = o.putObject("policies");
        k.policies().forEach(policies::put);
        // where the inputs come from: the scenario, with the origin it gives each
        ArrayNode inputs = o.putArray("inputs");
        long t0 = r.timeMicros(0);
        for (String port : new java.util.TreeSet<>(s.observed().ports())) {
            ObjectNode in = inputs.addObject();
            in.put("port", port);
            in.put("source", "scenario " + s.id());
            String origin = s.observed().value(port, t0).map(v -> v.origin().name()).orElse(null);
            if (origin != null) {
                in.put("origin", r.mode() == Mode.FORECAST ? Origin.FORECAST.name() : origin);
            }
        }
        ObjectNode status = o.putObject("status");
        status.put("usability", "restricted");
        ArrayNode reasons = status.putArray("reasons");
        reasons.add("the inputs are those of the scenario, which are invented: the run shows the mechanism, not an effect");
        reasons.add("sizes, pressures and the order on the hydrogen side are assumed values of the plan");
        ArrayNode events = o.putArray("events");
        for (TwinEvent e : r.events()) {
            ObjectNode ev = events.addObject();
            ev.put("t", Micros.toInstant(e.tMicros()).toString());
            ev.put("clock", clock(e.tMicros()));
            ev.put("step", e.k());
            ev.put("atom", e.atom());
            ev.put("type", e.type());
            ObjectNode attrs = ev.putObject("attrs");
            e.attrs().forEach(attrs::put);
        }
        ObjectNode kpis = o.putObject("kpis");
        new TreeMap<>(r.kpis()).forEach(kpis::put);
        ArrayNode keys = o.putArray("series");
        r.keys().forEach(keys::add);
        return o;
    }

    private static ObjectNode intervention(Intervention iv, StudioScenario s) {
        ObjectNode n = JSON.objectNode();
        n.put("at", Micros.toInstant(iv.tMicros()).toString());
        n.put("clock", clock(iv.tMicros()));
        n.put("minute", (iv.tMicros() - Micros.of(s.plan().start())) / 60_000_000L);
        n.put("atom", iv.atom());
        n.put("control", iv.control());
        if (iv.value() == null) {
            n.put("value", "release");
        } else {
            n.put("value", iv.value());
        }
        n.put("author", iv.author());
        n.put("reason", iv.reason());
        return n;
    }

    /** Series of a run by key; a key the run does not have is an error, not an empty list. */
    public synchronized JsonNode series(String system, String run, List<String> keys) {
        Kept k = find(system, run);
        RunResult r = k.result();
        ObjectNode o = JSON.objectNode();
        o.put("schema", "opentsx.run-series.v1");
        o.put("run", run);
        o.put("start", Micros.toInstant(r.timeMicros(0)).toString());
        o.put("firstStep", r.firstK());
        o.put("stepSeconds", r.stepMicros() / 1_000_000);
        ObjectNode series = o.putObject("series");
        for (String key : keys) {
            if (!r.keys().contains(key)) {
                throw new IllegalArgumentException("run " + run + " has no series " + key);
            }
            ObjectNode one = series.putObject(key);
            one.put("origin", r.origin(key).name());
            ArrayNode values = one.putArray("values");
            for (double v : r.series(key)) {
                if (Double.isNaN(v)) {
                    values.addNull();
                } else {
                    values.add(v);
                }
            }
        }
        return o;
    }

    /**
     * Two runs next to each other: the KPIs of the scenario with their difference (the run minus the base), and the
     * series the scenario compares. Both runs have the same plan and the same inputs; they differ in the decisions.
     */
    public synchronized JsonNode compare(String system, String run, String base) {
        Kept a = find(system, run);
        Kept b = find(system, base);
        StudioScenario s = scenarios.get(system);
        ObjectNode o = JSON.objectNode();
        o.put("schema", "opentsx.run-compare.v1");
        o.put("system", system);
        ObjectNode left = o.putObject("base");
        head(left, system, b);
        ObjectNode right = o.putObject("run");
        head(right, system, a);
        ArrayNode kpis = o.putArray("kpis");
        for (StudioScenario.Kpi k : s.kpis()) {
            Double vb = b.result().kpis().get(k.key());
            Double va = a.result().kpis().get(k.key());
            if (vb == null || va == null) {
                continue;
            }
            ObjectNode row = kpis.addObject();
            row.put("key", k.key()).put("label", k.label()).put("unit", k.unit()).put("decimals", k.decimals()).put("better", k.better());
            row.put("base", vb).put("run", va).put("delta", va - vb);
            // better, worse or the same, by the direction the scenario gives the KPI
            double d = (va - vb) * k.better();
            row.put("verdict", Math.abs(va - vb) < 1e-9 || k.better() == 0 ? "same" : d > 0 ? "better" : "worse");
        }
        ArrayNode ends = o.putArray("endStocks");
        // what is left in the stores at the end: a run must not look better because it emptied them
        for (String key : List.of("buffer.state.mass_kg", "line.state.mass_kg", "battery.soc")) {
            if (a.result().keys().contains(key) && b.result().keys().contains(key)) {
                ends.addObject().put("key", key).put("base", b.result().last(key)).put("run", a.result().last(key));
            }
        }
        ObjectNode series = o.putObject("series");
        o.put("start", s.plan().start().toString());
        o.put("stepSeconds", s.plan().baseStepMicros() / 1_000_000);
        for (String key : s.compareKeys()) {
            ObjectNode one = series.putObject(key);
            one.put("label", s.labels().getOrDefault(key, key));
            one.set("base", whole(b.result(), key, s.plan().steps()));
            one.set("run", whole(a.result(), key, s.plan().steps()));
            ArrayNode limits = one.putArray("limits");
            s.limits().getOrDefault(key, List.of()).forEach(l -> limits.addObject().put("label", l.label()).put("value", l.value()));
        }
        return o;
    }

    /** A series over the whole horizon of the plan; the steps before a forecast begins are empty. */
    private static ArrayNode whole(RunResult r, String key, int steps) {
        ArrayNode out = JSON.arrayNode();
        double[] v = r.keys().contains(key) ? r.series(key) : new double[0];
        for (int i = 0; i < steps; i++) {
            int j = i - r.firstK();
            if (j < 0 || j >= v.length || Double.isNaN(v[j])) {
                out.addNull();
            } else {
                out.add(v[j]);
            }
        }
        return out;
    }

    private static String clock(long tMicros) {
        return Micros.toInstant(tMicros).toString().substring(11, 19);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%s", v);
    }
}
