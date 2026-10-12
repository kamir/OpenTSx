package org.opentsx.twin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.opentsx.twin.*;

import java.nio.file.*;
import java.util.*;

/**
 * Exports the S1 graph, a shadow run, a what-if grid (valve reopening time x electrolyzer policy) and forecasts from
 * several snapshots as JSON for the Smart Topics Studio prototype ({@code docs/prototypes}).
 * Usage: {@code java -cp "$(bin/opentsx classpath opentsx-twin)" org.opentsx.twin.demo.UiExport out.json}
 */
public final class UiExport {
    static final String[] KEYS = {"park.p_gen_kw","ely.p_el_kw","ely.m_h2_kgph","offtake.m_out_kgph","line.p_bar","line.ttl_min",
        "line.headroom_kg","grid.p_grid_kw","marketer.deviation_kw","market.price_eur_mwh","battery.soc"};
    static final String[] KPIS = {"ely.trips","ely.h2_kg","marketer.extra_kwh","marketer.short_kwh","marketer.revenue_eur","offtake.delivered_kg","line.p_max_bar","grid.export_kwh","grid.direct_ely_kwh"};

    static Map<String,Object> run(RunResult r, boolean full) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId()); m.put("mode", r.mode().name()); m.put("hash", r.hash());
        m.put("firstK", r.firstK());
        Map<String,Object> s = new LinkedHashMap<>();
        List<String> keys = full ? r.keys().stream().filter(k -> !k.contains(".state.") || k.endsWith("mass_kg") || k.endsWith("extra_kwh") || k.endsWith("h2_kg")).toList() : List.of("line.p_bar","ely.p_el_kw","grid.p_grid_kw","marketer.deviation_kw","line.ttl_min");
        for (String k : keys) {
            double[] v = r.series(k); List<Object> l = new ArrayList<>();
            for (double x : v) l.add(Double.isNaN(x) ? null : Math.round(x*1000)/1000.0);
            s.put(k, l);
        }
        m.put("series", s);
        Map<String,Object> o = new LinkedHashMap<>();
        for (String k : r.keys()) { if (k.contains(".state.")) continue; Origin x = r.origin(k); o.put(k, x == null ? null : x.name()); }
        m.put("origin", o);
        Map<String,Object> kp = new LinkedHashMap<>();
        for (String k : KPIS) kp.put(k, Math.round(r.kpis().get(k)*100)/100.0);
        m.put("kpis", kp);
        List<Object> ev = new ArrayList<>();
        for (TwinEvent e : r.events()) ev.add(Map.of("t", (e.tMicros()-H2ChainDemo.T0)/60e6, "atom", e.atom(), "type", e.type(), "attrs", e.attrs()));
        m.put("events", ev);
        return m;
    }

    public static void main(String[] a) throws Exception {
        TwinPlan plan = H2ChainDemo.plan();
        TwinRunner runner = new TwinRunner(plan);
        Twin twin = new Twin(runner);
        Map<String,Object> out = new LinkedHashMap<>();
        // graph
        List<Atom> atoms = plan.instantiate(AtomCatalog.standard());
        List<Object> nodes = new ArrayList<>();
        for (Atom at : atoms) {
            Map<String,Object> n = new LinkedHashMap<>();
            n.put("id", at.id()); n.put("type", at.type()); n.put("phase", at.phase().name()); n.put("every", at.every());
            n.put("inputs", at.inputs().stream().map(p -> Map.of("name",p.name(),"kind",p.kind().name(),"unit",p.unit())).toList());
            n.put("outputs", at.outputs().stream().map(p -> Map.of("name",p.name(),"kind",p.kind().name(),"unit",p.unit())).toList());
            n.put("controls", at.controlDefaults());
            n.put("state", at.state().keySet());
            n.put("policy", plan.atoms().stream().filter(x -> x.id().equals(at.id())).findFirst().get().policy());
            n.put("defaultPolicy", at.defaultPolicy());
            n.put("params", plan.atoms().stream().filter(x -> x.id().equals(at.id())).findFirst().get().params());
            nodes.add(n);
        }
        List<Object> wires = new ArrayList<>();
        for (TwinPlan.Wire w : plan.resolve(atoms)) wires.add(Map.of("from", w.fromAtom(), "fromPort", w.fromPort(), "to", w.toAtom(), "toPort", w.toPort(), "delayed", w.delayed()));
        out.put("graph", Map.of("nodes", nodes, "wires", wires, "bindings", H2ChainDemo.observed().ports(), "planHash", plan.hash()));

        RunResult shadow = twin.shadow(H2ChainDemo.observed(), H2ChainDemo.valveClosed());
        out.put("shadow", run(shadow, true));

        // what-if grid: reopen time x policy
        List<Object> whatIf = new ArrayList<>();
        int[] reopen = {-1, 80, 100, 120, 140, 160, 180, 200, 240, 300};
        for (String pol : new String[]{"follow", "pressure-aware"}) {
            for (int ro : reopen) {
                List<Intervention> log = new ArrayList<>(H2ChainDemo.valveClosed());
                if (ro > 0) log.add(Intervention.set(H2ChainDemo.START.plusSeconds(ro*60L), "offtake", "valve", 1, "h2-offtake", "valve reopened"));
                RunResult r = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, H2ChainDemo.observed(), log, null, 0,
                        pol.equals("follow") ? Map.of() : Map.of("ely", pol), null));
                Map<String,Object> m = run(r, false); m.put("reopen", ro); m.put("policy", pol); whatIf.add(m);
            }
        }
        out.put("whatIf", whatIf);

        // forecasts from several "now" points
        List<Object> fc = new ArrayList<>();
        for (int now : new int[]{70, 90, 110, 130}) {
            Snapshot snap = shadow.snapshotAfter(now - 1);
            for (String pol : new String[]{"follow", "pressure-aware"}) {
                RunResult r = twin.forecast(snap, H2ChainDemo.observed().as(Origin.FORECAST), 360 - now,
                        pol.equals("follow") ? Map.of() : Map.of("ely", pol), List.of());
                Map<String,Object> m = run(r, false); m.put("now", now); m.put("policy", pol); fc.add(m);
            }
        }
        out.put("forecast", fc);
        new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValue(Paths.get(a[0]).toFile(), out);
    }
}
