package org.opentsx.twin.studio;

import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.demo.H2ChainDemo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scenario S1, the H2 chain, for the Studio. */
public final class H2ChainScenario implements StudioScenario {

    @Override
    public String id() {
        return "h2-chain";
    }

    @Override
    public String title() {
        return "Wasserstoffkette (Windpark, Elektrolyseur, H₂-Leitung)";
    }

    @Override
    public String description() {
        return "Der Abnehmer schließt um 01:00 den Schieber. Die Leitung füllt sich, der Elektrolyseur schaltet ab, "
                + "der Direktvermarkter muss mehr Strom verkaufen. 6 h im 1-Minuten-Takt, synthetische Daten.";
    }

    @Override
    public TwinPlan plan() {
        return H2ChainDemo.plan();
    }

    @Override
    public Inputs observed() {
        return H2ChainDemo.observed();
    }

    @Override
    public List<Intervention> reference() {
        return H2ChainDemo.valveClosed();
    }

    @Override
    public List<Alternative> alternatives() {
        List<Alternative> out = new ArrayList<>();
        for (String policy : new String[]{"follow", "pressure-aware"}) {
            String pName = policy.equals("follow") ? "dem Fahrplan folgen" : "druckbewusst";
            Map<String, String> pol = policy.equals("follow") ? Map.of() : Map.of("ely", policy);
            if (!policy.equals("follow")) {
                out.add(new Alternative("closed-" + policy, "Schieber bleibt zu · " + pName, reference(), pol, null));
            }
            for (int minute : new int[]{80, 100, 120, 140, 160, 180, 200, 240, 300}) {
                List<Intervention> log = new ArrayList<>(reference());
                log.add(Intervention.set(H2ChainDemo.START.plusSeconds(minute * 60L), "offtake", "valve", 1,
                        "h2-offtake", "valve reopened"));
                out.add(new Alternative("reopen-" + minute + "-" + policy,
                        String.format("Schieber wieder offen um %02d:%02d · %s", minute / 60, minute % 60, pName),
                        log, pol, null));
            }
        }
        return out;
    }

    @Override
    public List<Forecast> forecasts() {
        List<Forecast> out = new ArrayList<>();
        for (int now : new int[]{70, 90, 110, 130}) {
            String t = String.format("%02d:%02d", now / 60, now % 60);
            out.add(new Forecast("now-" + now + "-follow", "ab " + t + " · dem Fahrplan folgen", now, Map.of()));
            out.add(new Forecast("now-" + now + "-aware", "ab " + t + " · druckbewusst", now, Map.of("ely", "pressure-aware")));
        }
        return out;
    }

    @Override
    public List<String> smartMetrics() {
        return List.of("line.ttl_min", "line.headroom_kg", "marketer.deviation_kw", "grid.p_direct_kw");
    }

    @Override
    public List<String> compareKeys() {
        return List.of("line.p_bar", "ely.p_el_kw", "marketer.deviation_kw");
    }

    @Override
    public List<Kpi> kpis() {
        return List.of(
                new Kpi("ely.trips", "Schutzabschaltungen", "", 0, -1),
                new Kpi("line.p_max_bar", "Höchster Leitungsdruck", "bar", 2, -1),
                new Kpi("ely.h2_kg", "Erzeugter Wasserstoff", "kg", 1, 1),
                new Kpi("offtake.delivered_kg", "An Abnehmer geliefert", "kg", 1, 1),
                new Kpi("marketer.extra_kwh", "Mehrvermarktung über Fahrplan", "kWh", 0, -1),
                new Kpi("marketer.revenue_eur", "Erlös Einspeisung (Modell)", "€", 0, 0));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("line.ttl_min", "Zeit bis Druckgrenze");
        m.put("line.headroom_kg", "Restkapazität Leitung");
        m.put("marketer.deviation_kw", "Fahrplanabweichung");
        m.put("grid.p_direct_kw", "Direkt genutzte Erzeugung");
        m.put("line.p_bar", "Leitungsdruck");
        m.put("ely.p_el_kw", "Leistung Elektrolyseur");
        m.put("ely.m_h2_kgph", "H₂-Erzeugung");
        m.put("offtake.m_out_kgph", "H₂-Abnahme");
        m.put("park.p_gen_kw", "Erzeugung Windpark");
        m.put("grid.p_grid_kw", "Einspeisung Netz");
        m.put("grid.residual_kw", "Residuum zur Zählermessung");
        m.put("market.price_eur_mwh", "Strompreis");
        m.put("battery.p_bat_kw", "Leistung Batterie");
        m.put("battery.soc", "Ladezustand Batterie");
        m.put("line.m_net_kgph", "Netto-Zufluss Leitung");
        m.put("rules.limit_kw", "Leistungsgrenze (Betriebsvorgaben)");
        return m;
    }

    @Override
    public StudioGuide guide() {
        return StudioGuide.resource("twin/h2-chain.guide.yaml");
    }

    @Override
    public List<String> markerEvents() {
        return List.of("intervention", "pressure_limit", "protective_shutdown");
    }
}
