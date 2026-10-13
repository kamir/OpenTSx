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
        return "Wasserstoffkette (Windpark, Elektrolyseur, Verdichter, Puffer, H₂-Leitung)";
    }

    @Override
    public String description() {
        return "Die Übergabestation schließt um 01:00 ihr Ventil. Der Puffer füllt sich, der Elektrolyseur schaltet ab, "
                + "der Direktvermarkter muss mehr Strom verkaufen. Sechs Stunden in Minutenschritten, synthetische Daten.";
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
            String pName = policy.equals("follow") ? "Fahrweise unverändert" : "Elektrolyseur drosselt bei steigendem Druck";
            Map<String, String> pol = policy.equals("follow") ? Map.of() : Map.of("ely", policy);
            if (!policy.equals("follow")) {
                out.add(new Alternative("closed-" + policy, "Ventil bleibt zu · " + pName, reference(), pol, null));
            }
            for (int minute : new int[]{80, 100, 120, 140, 160, 180, 200, 240, 300}) {
                List<Intervention> log = new ArrayList<>(reference());
                log.add(Intervention.set(H2ChainDemo.START.plusSeconds(minute * 60L), "handover", "valve", 1,
                        "h2-handover", "valve reopened"));
                out.add(new Alternative("reopen-" + minute + "-" + policy,
                        String.format("Ventil wieder offen um %02d:%02d · %s", minute / 60, minute % 60, pName),
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
            out.add(new Forecast("now-" + now + "-follow", "ab " + t + " · Fahrweise unverändert", now, Map.of()));
            out.add(new Forecast("now-" + now + "-aware", "ab " + t + " · Elektrolyseur drosselt bei steigendem Druck", now, Map.of("ely", "pressure-aware")));
        }
        return out;
    }

    @Override
    public List<String> smartMetrics() {
        return List.of("buffer.ttl_min", "buffer.headroom_kg", "marketer.deviation_kw", "grid.p_direct_kw");
    }

    @Override
    public List<String> compareKeys() {
        return List.of("buffer.p_bar", "ely.p_el_kw", "marketer.deviation_kw");
    }

    @Override
    public List<Kpi> kpis() {
        return List.of(
                new Kpi("ely.trips", "Schutzabschaltungen", "", 0, -1),
                new Kpi("buffer.p_max_bar", "Höchster Druck im Puffer", "bar", 2, -1),
                new Kpi("ely.h2_kg", "Erzeugter Wasserstoff", "kg", 1, 1),
                new Kpi("handover.delivered_kg", "An Abnehmer geliefert", "kg", 1, 1),
                new Kpi("handover.shortfall_kg", "Nicht geliefert", "kg", 1, -1),
                new Kpi("compressor.energy_kwh", "Strom für die Verdichtung", "kWh", 0, 0),
                new Kpi("marketer.extra_kwh", "Ungeplante Mehreinspeisung", "kWh", 0, -1),
                new Kpi("marketer.revenue_eur", "Erlös Einspeisung (ohne Ausgleichsenergie und H₂-Erlös)", "€", 0, 0));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("buffer.ttl_min", "Zeit bis Druckgrenze");
        m.put("buffer.headroom_kg", "Restkapazität Puffer");
        m.put("marketer.deviation_kw", "Fahrplanabweichung");
        m.put("grid.p_direct_kw", "Direkt genutzte Erzeugung");
        m.put("buffer.p_bar", "Druck im Puffer");
        m.put("buffer.m_out_kgph", "Abgabe des Puffers an die Leitung");
        m.put("buffer.m_net_kgph", "Netto-Zufluss Puffer");
        m.put("line.p_in_bar", "Leitungsdruck am Anfang");
        m.put("line.p_out_bar", "Leitungsdruck am Ende");
        m.put("line.linepack_kg", "Wasserstoff in der Leitung");
        m.put("compressor.p_el_kw", "Leistung Verdichter");
        m.put("compressor.m_out_kgph", "Verdichteter Wasserstoff");
        m.put("handover.m_out_kgph", "H₂-Lieferung");
        m.put("handover.shortfall_kgph", "Fehlmenge");
        m.put("handover.p_delivery_bar", "Lieferdruck");
        m.put("offtake.request_kgph", "H₂-Anforderung");
        m.put("park.p_avail_kw", "Verfügbare Leistung Windpark");
        m.put("ely.p_el_kw", "Leistung Elektrolyseur");
        m.put("ely.m_h2_kgph", "H₂-Erzeugung");
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
    public List<String> headline() {
        return List.of("ely.trips", "marketer.extra_kwh", "handover.delivered_kg", "buffer.p_max_bar");
    }

    @Override
    public String decision() {
        return "Bis wann muss das Ventil der Übergabestation wieder offen sein, und wann wird der Direktvermarkter informiert?";
    }

    @Override
    public Map<String, List<Limit>> limits() {
        TwinPlan p = plan();
        return Map.of("buffer.p_bar", List.of(
                new Limit("Höchstdruck", param(p, "buffer", "pMaxBar")),
                new Limit("Wiedereinschalten", param(p, "ely", "pResumeBar"))));
    }

    private static double param(TwinPlan plan, String atom, String name) {
        return plan.atoms().stream().filter(a -> a.id().equals(atom)).findFirst()
                .map(a -> ((Number) a.params().get(name)).doubleValue()).orElseThrow();
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
