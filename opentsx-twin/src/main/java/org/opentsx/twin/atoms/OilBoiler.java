package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * H2: oil boiler, a converter from heating oil to heat (the house counterpart of the electrolyzer). It covers the
 * hot-water draw first (priority) and then the space-heat request of the building, up to {@code ratedKw}.
 * <ul>
 *   <li>Efficiency {@code eta} at or above the minimum load; below {@code minLoad} the single-stage burner cycles
 *       and the efficiency drops linearly by up to {@code cyclingLoss}.</li>
 *   <li>Standby loss {@code standbyKw} (fuel) whenever the boiler is kept hot, all year.</li>
 *   <li>Electricity: circulation pump ({@code pump_w}) during the heating season, burner fan/pump {@code burnerW}
 *       while firing.</li>
 *   <li>Fuel: {@code kwhPerLitre} (lower heating value of heating oil EL, assumption 10 kWh/l).</li>
 *   <li>Empty tank (feedback from the tank level): no heat; event {@code fuel_out} (the counterpart of the
 *       electrolyzer's protective shutdown).</li>
 * </ul>
 * Controls: {@code space_enabled} (0 = summer mode, hot water only), {@code pump_w}.
 */
public final class OilBoiler extends Atom {

    private final double ratedKw;
    private final double eta;
    private final double minLoad;
    private final double cyclingLoss;
    private final double standbyKw;
    private final double burnerKw;
    private final double kwhPerLitre;

    private double heatKwh;
    private double spaceKwh;
    private double dhwKwh;
    private double fuelKwh;
    private double fuelDhwKwh;
    private double standbyKwh;
    private double elKwh;
    private double pumpKwh;
    private double burnerHours;
    private double unmetKwh;
    private double fuelOut;

    public OilBoiler(String id, Params params) {
        super(id, params);
        ratedKw = params.d("ratedKw", 18);
        eta = params.d("eta", 0.90);
        minLoad = params.d("minLoad", 0.35);
        cyclingLoss = params.d("cyclingLoss", 0.15);
        standbyKw = params.d("standbyKw", 0.2);
        burnerKw = params.d("burnerW", 120) / 1000.0;
        kwhPerLitre = params.d("kwhPerLitre", 10.0);
    }

    @Override
    public String type() {
        return "oil-boiler";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.signal("q_req_kw", "kW"),
                PortSpec.flow("q_dhw_kw", "kW"),
                PortSpec.signal("season", "1"),
                PortSpec.signal("tank_level_l", "l"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.flow("q_space_kw", "kW"),
                PortSpec.flow("fuel_lph", "l/h"),
                PortSpec.flow("p_el_kw", "kW"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("space_enabled", 1.0);
        c.put("pump_w", params.d("pumpW", 65));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("heat_kwh", heatKwh);
        s.put("space_kwh", spaceKwh);
        s.put("dhw_kwh", dhwKwh);
        s.put("fuel_kwh", fuelKwh);
        s.put("fuel_l", fuelKwh / kwhPerLitre);
        s.put("fuel_dhw_kwh", fuelDhwKwh);
        s.put("standby_kwh", standbyKwh);
        s.put("el_kwh", elKwh);
        s.put("pump_kwh", pumpKwh);
        s.put("burner_h", burnerHours);
        s.put("unmet_kwh", unmetKwh);
        s.put("fuel_out", fuelOut);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        heatKwh = s.get("heat_kwh");
        spaceKwh = s.get("space_kwh");
        dhwKwh = s.get("dhw_kwh");
        fuelKwh = s.get("fuel_kwh");
        fuelDhwKwh = s.get("fuel_dhw_kwh");
        standbyKwh = s.get("standby_kwh");
        elKwh = s.get("el_kwh");
        pumpKwh = s.get("pump_kwh");
        burnerHours = s.get("burner_h");
        unmetKwh = s.get("unmet_kwh");
        fuelOut = s.get("fuel_out");
    }

    @Override
    public void step(StepContext ctx) {
        double dt = ctx.dtHours();
        OptionalDouble level = ctx.in("tank_level_l");
        boolean empty = level.isPresent() && level.getAsDouble() <= 0;
        if (empty && fuelOut == 0) {
            ctx.event("fuel_out", Map.of("tank_level_l", Electrolyzer.fmt(level.getAsDouble())));
        } else if (!empty && fuelOut == 1) {
            ctx.event("fuel_restored", Map.of());
        }
        fuelOut = empty ? 1 : 0;

        double dhwReq = Math.max(0, ctx.inOr("q_dhw_kw", 0));
        double spaceReq = ctx.control("space_enabled") > 0 ? Math.max(0, ctx.inOr("q_req_kw", 0)) : 0;
        boolean season = ctx.inOr("season", 1) > 0;

        double dhw = empty ? 0 : Math.min(dhwReq, ratedKw);
        double space = empty ? 0 : Math.min(spaceReq, ratedKw - dhw);
        double heat = dhw + space;
        double fuel = 0;
        double firing = 0;
        if (heat > 0) {
            double load = heat / ratedKw;
            double e = load >= minLoad ? eta : eta * (1 - cyclingLoss * (1 - load / minLoad));
            fuel = heat / e;
            fuelDhwKwh += dhw / e * dt;
            firing = Math.min(1, load / minLoad);
        }
        double standby = empty ? 0 : standbyKw;
        fuel += standby;
        double pump = season && ctx.control("space_enabled") > 0 ? ctx.control("pump_w") / 1000.0 : 0;
        double el = pump + burnerKw * firing;

        heatKwh += heat * dt;
        spaceKwh += space * dt;
        dhwKwh += dhw * dt;
        fuelKwh += fuel * dt;
        standbyKwh += standby * dt;
        elKwh += el * dt;
        pumpKwh += pump * dt;
        burnerHours += firing * dt;
        unmetKwh += (dhwReq + spaceReq - heat) * dt;

        ctx.out("q_space_kw", space);
        ctx.out("fuel_lph", fuel / kwhPerLitre);
        ctx.out("p_el_kw", el);
    }

    public double kwhPerLitre() {
        return kwhPerLitre;
    }
}
