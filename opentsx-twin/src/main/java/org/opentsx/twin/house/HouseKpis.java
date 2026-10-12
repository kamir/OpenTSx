package org.opentsx.twin.house;

import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinPlan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Efficiency KPIs of a house-twin run (concept 08 §5). Final energy of heating = oil litres × lower heating value +
 * wood Ster × energy per Ster. The weather-normalized value scales only the weather-dependent part (space heating)
 * with {@code G_ref / G} (heating degree days G20/15); hot water and boiler standby stay as they are.
 * CO2 uses the emission factors of GEG Annex 9 (heating oil 310, electricity 560, wood 20 g/kWh) as assumptions.
 *
 * @param origin worst origin of the metered quantities (electricity import and oil flow) over the run
 */
public record HouseKpis(double electricityKwh, double heatingElectricityKwh, double standbyKwh, double baseLoadW,
                        double peakKw, double oilLitres, double oilKwh, double woodSter, double woodKwh,
                        double finalHeatKwh, double finalHeatKwhPerM2, double hddKd, double normalizedFinalHeatKwh,
                        double usefulHeatKwh, double boilerLossKwh, double boilerEfficiency, double hotWaterKwh,
                        double underHours, double meanIndoorC, double costEur, double co2Kg, Origin origin) {

    public static final double CO2_OIL = 0.310;
    public static final double CO2_EL = 0.560;
    public static final double CO2_WOOD = 0.020;

    /** KPIs of a run; {@code hddRef} is the long-term reference G20/15 of the site (Kd), NaN to skip normalization. */
    public static HouseKpis of(TwinPlan plan, RunResult r, double hddRef) {
        Map<String, Double> k = r.kpis();
        String meter = HouseScenario.id(plan, "meter");
        String boiler = HouseScenario.id(plan, "oil-boiler");
        String stove = HouseScenario.id(plan, "wood-stove");
        String building = HouseScenario.id(plan, "building");
        String weather = HouseScenario.id(plan, "weather");
        String app = HouseScenario.id(plan, "appliances");
        String tank = HouseScenario.id(plan, "oil-tank");
        String store = HouseScenario.id(plan, "wood-store");
        double area = HouseScenario.param(plan, building, "areaM2", 140);

        double el = k.get(meter + ".import_kwh");
        double oilL = k.get(boiler + ".fuel_l");
        double oilKwh = k.get(boiler + ".fuel_kwh");
        double woodKwh = k.get(stove + ".fuel_kwh");
        double fin = oilKwh + woodKwh;
        double hdd = k.get(weather + ".hdd_kd");
        double fixed = k.get(boiler + ".fuel_dhw_kwh") + k.get(boiler + ".standby_kwh");
        double norm = Double.isNaN(hddRef) || hdd <= 0 ? Double.NaN : (fin - fixed) * hddRef / hdd + fixed;
        double boilerHeat = k.get(boiler + ".heat_kwh");
        double baseH = k.get(meter + ".base_h");
        double cost = k.get(meter + ".cost_eur") + k.get(tank + ".cost_eur") + k.get(store + ".cost_eur");
        double co2 = oilKwh * CO2_OIL + el * CO2_EL + woodKwh * CO2_WOOD;
        Origin origin = Origin.worst(r.origin(meter + ".p_import_kw"), r.origin(boiler + ".fuel_lph"));
        return new HouseKpis(el, k.get(boiler + ".el_kwh"), k.get(app + ".standby_kwh"),
                baseH > 0 ? k.get(meter + ".base_kwh") / baseH * 1000 : Double.NaN, k.get(meter + ".peak_kw"),
                oilL, oilKwh, k.get(stove + ".wood_ster"), woodKwh, fin, fin / area, hdd, norm,
                boilerHeat + k.get(stove + ".heat_kwh"), oilKwh - boilerHeat,
                oilKwh > 0 ? boilerHeat / oilKwh : Double.NaN, k.get(boiler + ".dhw_kwh"),
                k.get(building + ".under_h"), k.get(building + ".t_in_sum") / k.get(building + ".hours"), cost, co2,
                origin);
    }

    /** The KPIs as an ordered map (name -> value), for printing and differences. */
    public Map<String, Double> asMap() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("electricity_kwh", electricityKwh);
        m.put("heating_electricity_kwh", heatingElectricityKwh);
        m.put("standby_kwh", standbyKwh);
        m.put("base_load_w", baseLoadW);
        m.put("peak_kw", peakKw);
        m.put("oil_l", oilLitres);
        m.put("wood_ster", woodSter);
        m.put("final_heat_kwh", finalHeatKwh);
        m.put("final_heat_kwh_m2", finalHeatKwhPerM2);
        m.put("hdd_kd", hddKd);
        m.put("final_heat_normalized_kwh", normalizedFinalHeatKwh);
        m.put("useful_heat_kwh", usefulHeatKwh);
        m.put("boiler_loss_kwh", boilerLossKwh);
        m.put("boiler_efficiency", boilerEfficiency);
        m.put("hot_water_kwh", hotWaterKwh);
        m.put("under_h", underHours);
        m.put("mean_indoor_c", meanIndoorC);
        m.put("cost_eur", costEur);
        m.put("co2_kg", co2Kg);
        return m;
    }

    /** Alternative minus reference, per KPI. */
    public Map<String, Double> minus(HouseKpis reference) {
        Map<String, Double> a = asMap();
        Map<String, Double> b = reference.asMap();
        Map<String, Double> out = new LinkedHashMap<>();
        a.forEach((key, v) -> out.put(key, v - b.get(key)));
        return out;
    }
}
