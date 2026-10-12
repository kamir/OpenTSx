package org.opentsx.twin.boat;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * A portable power station (LiFePO4 battery with MPPT solar input, AC charger and 12 V / USB-C / 230 V outputs) as the
 * energy hub of an island system. It is a stock and a balance node at once, solved within the superstep.
 * <p>
 * <b>Inputs.</b> {@code p_solar_w} and {@code p_shore_w} (DC power on offer), and one load port {@code p_<name>_w}
 * per entry of parameter {@code loads}. The order of {@code loads} is the supply priority when energy is short
 * (first = most important, e.g. the VHF radio).
 * <p>
 * <b>Dispatch per step.</b> Loads plus the station's own idle draw are served from solar first, then shore power,
 * then the battery. Surplus charges the battery (solar before shore) up to {@code max_charge_w} and {@code soc_max}.
 * The battery discharges down to {@code soc_min} at up to {@code maxDischargeW}. What is still missing is unmet
 * demand, taken from the loads in reverse priority. Solar input above {@code maxSolarW} or not needed is curtailed.
 * <p>
 * <b>Energy balance (DC side, per step and cumulative).</b>
 * {@code solar_wh + shore_wh − served_wh − loss_wh + nudge_wh = soc_wh − soc_start_wh}, where {@code loss_wh} holds
 * the idle draw and the charge/discharge losses ({@code etaCharge}, {@code etaDischarge}).
 * <p>
 * <b>Sensor view.</b> Bound {@code soc_meas} (0..1) gives {@code soc_residual = measured − model}; bound
 * {@code p_load_meas_w} (total output) gives {@code load_residual_w = measured − served}. Control {@code nudge}
 * (0..1, default 0) pulls the model SoC towards the measurement each step; the correction is booked as
 * {@code nudge_wh}.
 * <p>
 * Controls (defaults from parameters): {@code capacity_wh}, {@code soc_min}, {@code soc_max}, {@code max_charge_w},
 * {@code nudge}. A capacity set before the first step keeps the initial SoC fraction {@code soc0}; a later change
 * keeps the stored energy (the added capacity starts empty).
 */
public final class PowerStation extends Atom {

    private final List<String> loads;
    private final double soc0;
    private final double maxSolarW;
    private final double maxDischargeW;
    private final double etaCharge;
    private final double etaDischarge;
    private final double idleW;
    private final double warnSoc;

    private double capacityWh;
    private double socWh;
    private double socStartWh;
    private double socMin = 1;
    private double solarWh;
    private double shoreWh;
    private double servedWh;
    private double demandWh;
    private double unmetWh;
    private double unmetH;
    private double curtailedWh;
    private double lossWh;
    private double dischargeWh;
    private double nudgeWh;
    private double avgLoadW;
    private double elapsedH;
    private double autonomyH;
    private double low;
    private double shortNow;
    private double stepped;
    private final Map<String, Double> unmetByLoad = new LinkedHashMap<>();

    public PowerStation(String id, Params params) {
        super(id, params);
        loads = List.copyOf(params.list("loads", List.of()));
        for (String l : loads) {
            if (l.isBlank() || l.contains(".") || unmetByLoad.containsKey(l)) {
                throw new IllegalArgumentException(id + ": invalid or duplicate load '" + l + "'");
            }
            unmetByLoad.put(l, 0.0);
        }
        capacityWh = params.d("capacityWh", 1024);
        soc0 = params.d("soc0", 0.9);
        maxSolarW = params.d("maxSolarW", 500);
        maxDischargeW = params.d("maxDischargeW", 1800);
        etaCharge = params.d("etaCharge", 0.95);
        etaDischarge = params.d("etaDischarge", 0.95);
        idleW = params.d("idleW", 2);
        warnSoc = params.d("warnSoc", 0.2);
        socWh = soc0 * capacityWh;
        socStartWh = socWh;
    }

    @Override
    public String type() {
        return "power-station";
    }

    @Override
    public Phase phase() {
        return Phase.BALANCE;
    }

    @Override
    public List<PortSpec> inputs() {
        List<PortSpec> in = new ArrayList<>();
        in.add(PortSpec.flow("p_solar_w", "W"));
        in.add(PortSpec.flow("p_shore_w", "W"));
        loads.forEach(l -> in.add(PortSpec.flow("p_" + l + "_w", "W")));
        in.add(PortSpec.signal("soc_meas", "1"));
        in.add(PortSpec.flow("p_load_meas_w", "W"));
        return in;
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.signal("soc", "1"),
                PortSpec.flow("p_batt_w", "W"),
                PortSpec.flow("solar_used_w", "W"),
                PortSpec.flow("shore_used_w", "W"),
                PortSpec.flow("p_load_w", "W"),
                PortSpec.flow("p_unmet_w", "W"),
                PortSpec.flow("p_curtailed_w", "W"),
                PortSpec.signal("autonomy_h", "h"),
                PortSpec.signal("soc_residual", "1"),
                PortSpec.flow("load_residual_w", "W"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("capacity_wh", params.d("capacityWh", 1024));
        c.put("soc_min", params.d("socMin", 0.05));
        c.put("soc_max", params.d("socMax", 1.0));
        c.put("max_charge_w", params.d("maxChargeW", 800));
        c.put("nudge", params.d("nudge", 0));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("capacity_wh", capacityWh);
        s.put("soc_wh", socWh);
        s.put("soc_start_wh", socStartWh);
        s.put("soc_min", socMin);
        s.put("solar_wh", solarWh);
        s.put("shore_wh", shoreWh);
        s.put("demand_wh", demandWh);
        s.put("served_wh", servedWh);
        s.put("unmet_wh", unmetWh);
        s.put("unmet_h", unmetH);
        s.put("curtailed_wh", curtailedWh);
        s.put("loss_wh", lossWh);
        s.put("discharge_wh", dischargeWh);
        s.put("cycles", capacityWh > 0 ? dischargeWh / capacityWh : 0);
        s.put("nudge_wh", nudgeWh);
        s.put("avg_load_w", avgLoadW);
        s.put("elapsed_h", elapsedH);
        s.put("autonomy_h", autonomyH);
        s.put("low", low);
        s.put("short", shortNow);
        s.put("stepped", stepped);
        unmetByLoad.forEach((l, v) -> s.put("unmet_" + l + "_wh", v));
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        capacityWh = s.get("capacity_wh");
        socWh = s.get("soc_wh");
        socStartWh = s.get("soc_start_wh");
        socMin = s.get("soc_min");
        solarWh = s.get("solar_wh");
        shoreWh = s.get("shore_wh");
        demandWh = s.get("demand_wh");
        servedWh = s.get("served_wh");
        unmetWh = s.get("unmet_wh");
        unmetH = s.get("unmet_h");
        curtailedWh = s.get("curtailed_wh");
        lossWh = s.get("loss_wh");
        dischargeWh = s.get("discharge_wh");
        nudgeWh = s.get("nudge_wh");
        avgLoadW = s.get("avg_load_w");
        elapsedH = s.get("elapsed_h");
        autonomyH = s.get("autonomy_h");
        low = s.get("low");
        shortNow = s.get("short");
        stepped = s.get("stepped");
        unmetByLoad.replaceAll((l, v) -> s.get("unmet_" + l + "_wh"));
    }

    @Override
    public void step(StepContext ctx) {
        double h = ctx.dtHours();
        double cap = ctx.control("capacity_wh");
        if (cap > 0 && cap != capacityWh) {
            if (stepped == 0) {
                socWh = soc0 * cap;
                socStartWh = socWh;
            } else if (socWh > cap) {
                nudgeWh += cap - socWh;
                socWh = cap;
            }
            capacityWh = cap;
        }
        stepped = 1;
        double lo = clamp01(ctx.control("soc_min"));
        double hi = Math.max(lo, clamp01(ctx.control("soc_max")));
        double maxCharge = Math.max(0, ctx.control("max_charge_w"));

        double solarRaw = ctx.has("p_solar_w") ? Math.max(0, ctx.in("p_solar_w").getAsDouble()) : 0;
        double solar = Math.min(solarRaw, maxSolarW);
        double shore = ctx.has("p_shore_w") ? Math.max(0, ctx.in("p_shore_w").getAsDouble()) : 0;
        double[] load = new double[loads.size()];
        double demand = 0;
        for (int i = 0; i < load.length; i++) {
            String port = "p_" + loads.get(i) + "_w";
            load[i] = ctx.has(port) ? Math.max(0, ctx.in(port).getAsDouble()) : 0;
            demand += load[i];
        }
        double need = demand + idleW;

        // direct supply: solar, then shore
        double solarDirect = Math.min(solar, need);
        double rem = need - solarDirect;
        double shoreDirect = Math.min(shore, rem);
        rem -= shoreDirect;

        // surplus charges the battery
        double room = Math.max(0, hi * capacityWh - socWh);
        double chargeMax = Math.min(maxCharge, room / (etaCharge * h));
        double solarCharge = Math.min(solar - solarDirect, chargeMax);
        double shoreCharge = Math.min(shore - shoreDirect, chargeMax - solarCharge);
        double charge = solarCharge + shoreCharge;

        // deficit from the battery
        double usable = Math.max(0, socWh - lo * capacityWh);
        double discharge = Math.min(rem, Math.min(maxDischargeW, usable * etaDischarge / h));
        double unmet = rem - discharge;
        if (unmet < 1e-12) {
            unmet = 0;
        }

        // unmet demand hits the loads in reverse priority, the station's own draw last
        double left = unmet;
        for (int i = load.length - 1; i >= 0 && left > 0; i--) {
            double cut = Math.min(load[i], left);
            left -= cut;
            unmetByLoad.merge(loads.get(i), cut * h, Double::sum);
        }
        double unmetLoads = unmet - left;
        double idleServed = idleW - left;

        socWh += charge * etaCharge * h - discharge / etaDischarge * h;
        socWh = Math.max(0, Math.min(capacityWh, socWh));
        double solarUsed = solarDirect + solarCharge;
        double shoreUsed = shoreDirect + shoreCharge;
        double served = demand - unmetLoads;

        solarWh += solarUsed * h;
        shoreWh += shoreUsed * h;
        demandWh += demand * h;
        servedWh += served * h;
        unmetWh += unmetLoads * h;
        curtailedWh += (solarRaw - solarUsed) * h;
        lossWh += (idleServed + charge * (1 - etaCharge) + discharge * (1 / etaDischarge - 1)) * h;
        dischargeWh += discharge * h;
        if (unmetLoads > 0) {
            unmetH += h;
        }

        double soc = capacityWh > 0 ? socWh / capacityWh : 0;
        OptionalDouble meas = ctx.in("soc_meas");
        if (meas.isPresent()) {
            ctx.out("soc_residual", meas.getAsDouble() - soc);
            double nudge = clamp01(ctx.control("nudge"));
            if (nudge > 0) {
                double corr = nudge * (clamp01(meas.getAsDouble()) * capacityWh - socWh);
                socWh += corr;
                nudgeWh += corr;
                soc = socWh / capacityWh;
            }
        }
        OptionalDouble loadMeas = ctx.in("p_load_meas_w");
        if (loadMeas.isPresent()) {
            ctx.out("load_residual_w", loadMeas.getAsDouble() - served);
        }
        socMin = Math.min(socMin, soc);

        // mean load (running mean over the first 24 h, then exponential with 24 h time constant) and the hours the
        // usable energy would last at that load without any source
        avgLoadW += (need - avgLoadW) * h / Math.min(24.0, elapsedH + h);
        elapsedH += h;
        autonomyH = Math.max(0, socWh - lo * capacityWh) * etaDischarge / Math.max(1, avgLoadW);

        if (soc < warnSoc && low == 0) {
            ctx.event("soc_low", Map.of("soc", BoatClock.fmt(soc)));
        }
        low = soc < warnSoc ? 1 : 0;
        if (unmetLoads > 0 && shortNow == 0) {
            ctx.event("supply_short", Map.of("unmet_w", BoatClock.fmt(unmetLoads)));
        } else if (unmetLoads == 0 && shortNow == 1) {
            ctx.event("supply_restored", Map.of());
        }
        shortNow = unmetLoads > 0 ? 1 : 0;

        ctx.out("soc", soc);
        ctx.out("p_batt_w", charge - discharge);
        ctx.out("solar_used_w", solarUsed);
        ctx.out("shore_used_w", shoreUsed);
        ctx.out("p_load_w", demand);
        ctx.out("p_unmet_w", unmetLoads);
        ctx.out("p_curtailed_w", solarRaw - solarUsed);
        ctx.out("autonomy_h", autonomyH);
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
