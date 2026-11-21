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
        return "Boat Etap 22 (power station, solar, shore power)";
    }

    @Override
    public String description() {
        return "A 14-day summer trip in Kiel Bay in 15-minute steps: harbour, sailing, anchoring. Consumers: "
                + "2 laptops, Pixel 10, Starlink Mini, Raspberry Pi, VHF radio. All numbers are assumptions; levers are "
                + "modelled.";
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
            "solar300", "+100 Wp solar (300 Wp)",
            "starlink3h", "Starlink only 3 h a day",
            "station2kwh", "Power station 2 kWh",
            "shore3d", "Shore power only every 3rd day",
            "laptopsSun", "Laptops charge only 10:00 to 16:00",
            "starlinkAc", "Starlink via inverter (230 V)",
            "starlinkStd", "Starlink Standard instead of Mini",
            "weekends", "Weekend use instead of a holiday trip");

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
                new Kpi("kpi.unmet_wh", "Unmet demand", "Wh", 0, -1),
                new Kpi("kpi.soc_min_pct", "Lowest state of charge", "%", 1, 1),
                new Kpi("kpi.autonomy_h_mean", "Mean autonomy", "h", 1, 1),
                new Kpi("kpi.solar_used_wh", "Solar energy used", "Wh", 0, 1),
                new Kpi("kpi.shore_wh", "Shore power", "Wh", 0, -1),
                new Kpi("kpi.solar_share_pct", "Solar share", "%", 0, 1),
                new Kpi("kpi.load_wh_per_day", "Consumption per day", "Wh/d", 0, -1),
                new Kpi("kpi.starlink_wh_per_day", "Of which Starlink per day", "Wh/d", 0, -1),
                new Kpi("kpi.cycles", "Full cycles", "", 2, -1));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("station.soc", "Power station state of charge");
        m.put("station.p_unmet_w", "Unmet load");
        m.put("station.autonomy_h", "Autonomy at the current load");
        m.put("solar.p_avail_w", "Solar available");
        m.put("station.p_load_w", "Total load");
        m.put("station.solar_used_w", "Solar used");
        m.put("station.shore_used_w", "Shore power used");
        m.put("starlink.p_w", "Starlink");
        m.put("boat.mode", "Operating mode");
        return m;
    }

    @Override
    public List<String> markerEvents() {
        return List.of("soc_low", "supply_short");
    }
}
