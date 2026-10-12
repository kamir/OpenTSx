package org.opentsx.twin.studio;

import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.demo.BoatDemo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Etap 22 as an island energy system (P9.8) for the Studio: a 14-day summer trip, what-ifs as alternatives. */
public final class BoatStudioScenario implements StudioScenario {

    @Override
    public String id() {
        return "boot";
    }

    @Override
    public String title() {
        return "Boot Etap 22 (Powerstation, Solar, Landstrom)";
    }

    @Override
    public String description() {
        return "14-tägiger Sommertörn in der Kieler Bucht im 15-Minuten-Takt: Hafen, Segeln, Ankern. Verbraucher: "
                + "2 Laptops, Pixel 10, Starlink Mini, Raspberry Pi, UKW. Alle Zahlen sind Annahmen; Hebel sind modelliert.";
    }

    @Override
    public TwinPlan plan() {
        return BoatDemo.plan();
    }

    @Override
    public Inputs observed() {
        return BoatDemo.observed();
    }

    @Override
    public List<Intervention> reference() {
        return BoatDemo.reference();
    }

    @Override
    public List<Alternative> alternatives() {
        List<Alternative> out = new ArrayList<>();
        for (BoatDemo.Alternative a : BoatDemo.alternatives()) {
            out.add(new Alternative(a.name(), LABELS.getOrDefault(a.name(), a.label()), a.interventions(),
                    a.policies(), a.inputs()));
        }
        return out;
    }

    private static final Map<String, String> LABELS = Map.of(
            "solar300", "+100 Wp Solar (300 Wp)",
            "starlink3h", "Starlink nur 3 h am Tag",
            "station2kwh", "Powerstation 2 kWh",
            "shore3d", "Landstrom nur jeden 3. Tag",
            "laptopsSun", "Laptops laden nur 10–16 Uhr",
            "starlinkAc", "Starlink über Wechselrichter (230 V)",
            "starlinkStd", "Starlink Standard statt Mini",
            "weekends", "Wochenendnutzung statt Urlaubstörn");

    @Override
    public List<String> smartMetrics() {
        return List.of("station.soc", "station.p_unmet_w", "station.autonomy_h", "solar.p_avail_w",
                "station.p_load_w");
    }

    @Override
    public List<String> compareKeys() {
        return List.of("station.soc", "station.p_unmet_w", "station.shore_used_w");
    }

    @Override
    public Map<String, Double> derivedKpis(RunResult run) {
        Map<String, Double> out = new LinkedHashMap<>();
        BoatDemo.kpis(run).forEach((k, v) -> out.put("kpi." + k, v));
        return out;
    }

    @Override
    public List<Kpi> kpis() {
        return List.of(
                new Kpi("kpi.unmet_wh", "Ungedeckter Bedarf", "Wh", 0, -1),
                new Kpi("kpi.soc_min_pct", "niedrigster Ladezustand", "%", 1, 1),
                new Kpi("kpi.autonomy_h_mean", "mittlere Autonomie", "h", 1, 1),
                new Kpi("kpi.solar_used_wh", "genutzte Solarenergie", "Wh", 0, 1),
                new Kpi("kpi.shore_wh", "Landstrom", "Wh", 0, -1),
                new Kpi("kpi.solar_share_pct", "Solaranteil", "%", 0, 1),
                new Kpi("kpi.load_wh_per_day", "Verbrauch je Tag", "Wh/d", 0, -1),
                new Kpi("kpi.starlink_wh_per_day", "davon Starlink je Tag", "Wh/d", 0, -1),
                new Kpi("kpi.cycles", "Vollzyklen", "", 2, -1));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("station.soc", "Ladezustand Powerstation");
        m.put("station.p_unmet_w", "Ungedeckte Last");
        m.put("station.autonomy_h", "Autonomie bei aktueller Last");
        m.put("solar.p_avail_w", "Solar verfügbar");
        m.put("station.p_load_w", "Last gesamt");
        m.put("station.solar_used_w", "Solar genutzt");
        m.put("station.shore_used_w", "Landstrom genutzt");
        m.put("starlink.p_w", "Starlink");
        m.put("boat.mode", "Betriebsmodus");
        return m;
    }

    @Override
    public List<String> markerEvents() {
        return List.of("soc_low", "supply_short");
    }
}
