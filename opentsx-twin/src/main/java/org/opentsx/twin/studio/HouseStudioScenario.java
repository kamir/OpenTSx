package org.opentsx.twin.studio;

import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.demo.HouseDemo;
import org.opentsx.twin.house.HouseKpis;
import org.opentsx.twin.house.HouseScenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single-family house in the Erzgebirge (P9.7) for the Studio: one synthetic year, levers as alternatives. */
public final class HouseStudioScenario implements StudioScenario {

    /** Display names of the levers (ids from {@code HouseScenario.levers}). */
    private static final Map<String, String> LEVERS = Map.of(
            "minus-1K", "Room temperature 1 °C lower (21 → 20 °C)",
            "night-setback-5K", "Deeper night setback (3 → 5 K, 22:00 to 6:00)",
            "unused-rooms-17C", "Rarely used rooms 4 K cooler (20 % of the area)",
            "more-wood", "More wood: stove 7 instead of 4 hours per evening",
            "dhw-50C", "Hot water cylinder at 50 instead of 60 °C",
            "he-pump", "High-efficiency pump (8 instead of 65 W)",
            "new-freezer", "New freezer (60 % less electricity)",
            "standby-off", "Standby off (power strips)",
            "less-tv", "30 % less TV time",
            "combined", "All levers except \"more wood\"");

    private final TwinPlan plan = HouseDemo.plan();

    @Override
    public String id() {
        return "haus";
    }

    @Override
    public String title() {
        return "Single-family house in the Erzgebirge (oil and wood heating)";
    }

    @Override
    public String description() {
        return "Two residents, oil boiler and wood stove, no PV, no heat pump. One year in hourly steps on a synthetic "
                + "climate; all numbers are assumptions until the bills are loaded. Levers are modelled.";
    }

    @Override
    public TwinPlan plan() {
        return plan;
    }

    @Override
    public Inputs observed() {
        return HouseDemo.observed();
    }

    @Override
    public List<Intervention> reference() {
        return HouseDemo.reference();
    }

    @Override
    public List<Alternative> alternatives() {
        List<Alternative> out = new ArrayList<>();
        HouseDemo.alternativesFor(plan).forEach((name, a) -> out.add(new Alternative(name,
                LEVERS.getOrDefault(name, name + " · " + a.description()), a.interventions(), a.policyOverrides(),
                a.inputOverrides())));
        return out;
    }

    @Override
    public List<String> smartMetrics() {
        return HouseDemo.smartMetrics();
    }

    @Override
    public List<String> compareKeys() {
        return List.of("building.t_in_c", "boiler.fuel_lph", "meter.p_import_kw");
    }

    @Override
    public Map<String, Double> derivedKpis(RunResult run) {
        Map<String, Double> out = new LinkedHashMap<>();
        HouseKpis.of(plan, run, HouseDemo.HDD_REF).asMap().forEach((k, v) -> out.put("kpi." + k, v));
        return out;
    }

    @Override
    public List<Kpi> kpis() {
        return List.of(
                new Kpi("kpi.oil_l", "Heating oil", "l", 0, -1),
                new Kpi("kpi.wood_ster", "Wood", "Ster", 1, 0),
                new Kpi("kpi.electricity_kwh", "Electricity", "kWh", 0, -1),
                new Kpi("kpi.final_heat_kwh", "Final energy for heat", "kWh", 0, -1),
                new Kpi("kpi.final_heat_kwh_m2", "Final energy per m²", "kWh/m²a", 0, -1),
                new Kpi("kpi.boiler_loss_kwh", "Boiler losses", "kWh", 0, -1),
                new Kpi("kpi.mean_indoor_c", "mean room temperature", "°C", 1, 0),
                new Kpi("kpi.under_h", "Hours below the comfort limit", "h", 0, -1),
                new Kpi("kpi.cost_eur", "Energy cost per year", "€", 0, -1),
                new Kpi("kpi.co2_kg", "CO₂ per year", "kg", 0, -1));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("building.t_in_c", "Room temperature");
        m.put("weather.t_out_c", "Outdoor temperature");
        m.put("building.q_req_kw", "Heat demand of the building");
        m.put("boiler.q_space_kw", "Heat output of the oil boiler");
        m.put("boiler.fuel_lph", "Oil consumption");
        m.put("tank.level_l", "Oil tank level");
        m.put("stove.q_heat_kw", "Heat output of the wood stove");
        m.put("woodstore.stock_ster", "Wood stock");
        m.put("meter.p_import_kw", "Electricity drawn");
        return m;
    }

    @Override
    public List<String> headline() {
        return List.of("kpi.cost_eur", "kpi.oil_l", "kpi.co2_kg", "kpi.final_heat_kwh_m2");
    }

    @Override
    public String decision() {
        return "Which lever pays off, and at what loss of comfort?";
    }

    @Override
    public Map<String, List<Limit>> limits() {
        return Map.of("tank.level_l", List.of(new Limit("Reorder threshold",
                HouseScenario.param(plan, "tank", "reorderL", 600))));
    }

    @Override
    public StudioGuide guide() {
        return StudioGuide.resource("twin/haus-erzgebirge.guide.yaml");
    }

    @Override
    public List<String> markerEvents() {
        return List.of("delivery", "reorder_warning", "tank_empty");
    }
}
