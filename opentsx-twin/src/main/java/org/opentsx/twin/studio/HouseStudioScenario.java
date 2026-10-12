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

    /** German names of the levers (ids from {@code HouseScenario.levers}). */
    private static final Map<String, String> LEVERS = Map.of(
            "minus-1K", "Raumtemperatur 1 °C niedriger (21 → 20 °C)",
            "night-setback-5K", "Tiefere Nachtabsenkung (3 → 5 K, 22 bis 6 Uhr)",
            "unused-rooms-17C", "Selten genutzte Räume 4 K kühler (20 % der Fläche)",
            "more-wood", "Mehr Holz: Ofen 7 statt 4 Stunden je Abend",
            "dhw-50C", "Warmwasserspeicher 50 statt 60 °C",
            "he-pump", "Hocheffizienzpumpe (8 statt 65 W)",
            "new-freezer", "Neue Gefriertruhe (60 % weniger Strom)",
            "standby-off", "Standby abschalten (Steckerleisten)",
            "less-tv", "30 % weniger Fernsehzeit",
            "combined", "Alle Hebel außer „mehr Holz“");

    private final TwinPlan plan = HouseDemo.plan();

    @Override
    public String id() {
        return "haus";
    }

    @Override
    public String title() {
        return "Einfamilienhaus im Erzgebirge (Öl-Holz-Heizung)";
    }

    @Override
    public String description() {
        return "Zwei Bewohner, Ölkessel und Kaminofen, kein PV, keine Wärmepumpe. Ein Jahr in Stundenschritten auf "
                + "synthetischem Klima; alle Zahlen sind Annahmen, bis die Abrechnungen eingespielt sind. Hebel sind modelliert.";
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
                new Kpi("kpi.oil_l", "Heizöl", "l", 0, -1),
                new Kpi("kpi.wood_ster", "Holz", "Ster", 1, 0),
                new Kpi("kpi.electricity_kwh", "Strom", "kWh", 0, -1),
                new Kpi("kpi.final_heat_kwh", "Endenergie Wärme", "kWh", 0, -1),
                new Kpi("kpi.final_heat_kwh_m2", "Endenergie je m²", "kWh/m²a", 0, -1),
                new Kpi("kpi.boiler_loss_kwh", "Kesselverluste", "kWh", 0, -1),
                new Kpi("kpi.mean_indoor_c", "mittlere Raumtemperatur", "°C", 1, 0),
                new Kpi("kpi.under_h", "Stunden unter Komfortgrenze", "h", 0, -1),
                new Kpi("kpi.cost_eur", "Energiekosten im Jahr", "€", 0, -1),
                new Kpi("kpi.co2_kg", "CO₂ im Jahr", "kg", 0, -1));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("building.t_in_c", "Raumtemperatur");
        m.put("weather.t_out_c", "Außentemperatur");
        m.put("building.q_req_kw", "Wärmebedarf Gebäude");
        m.put("boiler.q_space_kw", "Heizleistung Ölkessel");
        m.put("boiler.fuel_lph", "Ölverbrauch");
        m.put("tank.level_l", "Füllstand Öltank");
        m.put("stove.q_heat_kw", "Heizleistung Kaminofen");
        m.put("woodstore.stock_ster", "Holzvorrat");
        m.put("meter.p_import_kw", "Strombezug");
        return m;
    }

    @Override
    public List<String> headline() {
        return List.of("kpi.cost_eur", "kpi.oil_l", "kpi.co2_kg", "kpi.final_heat_kwh_m2");
    }

    @Override
    public String decision() {
        return "Welcher Hebel lohnt sich, und zu welchem Verlust an Komfort?";
    }

    @Override
    public Map<String, List<Limit>> limits() {
        return Map.of("tank.level_l", List.of(new Limit("Bestellschwelle",
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
