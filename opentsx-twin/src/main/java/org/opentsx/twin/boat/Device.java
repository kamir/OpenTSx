package org.opentsx.twin.boat;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic electrical consumer fed by the power station (laptop, phone, Starlink, Raspberry Pi, VHF radio, ...).
 * <p>
 * <b>Usage profile.</b> The device is active inside its local hour {@code windows} for the current mode (parameter
 * map mode → list of {@code "HH-HH"}), otherwise in standby. Power while active is
 * {@code scale · (activeW · (1 − burstDuty) + burstW · burstDuty)}; {@code burstW}/{@code burstDuty} model short
 * high-power phases such as VHF transmission. Standby draws {@code standbyW}.
 * <p>
 * <b>Own battery (optional).</b> With {@code batteryWh > 0} (laptop, phone) the usage drains the device battery, and
 * the device draws power from the station only while plugged in: between {@code charge_from_h} and
 * {@code charge_to_h} (local), at up to {@code chargeW}, tapered above 80 % state of charge. If the device battery
 * runs empty, the missing usage is recorded as {@code unmet_use_wh}.
 * <p>
 * <b>Path.</b> {@code p_w} is the draw at the power station: device power divided by {@code path_eff} (USB-C PD or
 * 12 V outlet ≈ 0.9; inverter and power supply ≈ 0.8).
 * <p>
 * <b>Measurement.</b> A bound {@code p_meas_w} (device-side power, e.g. from the Starlink app or a USB-C meter)
 * replaces the model in that step. In a what-if the controls {@code on}, {@code scale} and {@code max_on_h} still
 * apply to it; a changed value then counts as {@link Origin#ESTIMATED}.
 * <p>
 * In the modes listed in {@code offModes} (default: away) the device is not on board and draws nothing.
 * <p>
 * Controls (defaults from parameters): {@code on}, {@code scale}, {@code max_on_h} (active hours per local day),
 * {@code path_eff}, {@code charge_from_h}, {@code charge_to_h}.
 * <p>
 * Simplification: the device does not learn whether the station could serve it; energy the station cannot deliver
 * appears there as unmet demand.
 */
public final class Device extends Atom {

    private final List<List<BoatClock.Window>> windows;
    private final double activeW;
    private final double standbyW;
    private final double burstW;
    private final double burstDuty;
    private final double batteryWh;
    private final double chargeW;
    private final double utcOffsetH;
    private final boolean[] offModes = new boolean[BoatClock.MODES.size()];

    private double energyWh;
    private double deviceWh;
    private double activeH;
    private double day = Double.NaN;
    private double onTodayH;
    private double socWh;
    private double emptyH;
    private double unmetUseWh;

    public Device(String id, Params params) {
        super(id, params);
        windows = BoatClock.windows(params, "windows");
        activeW = params.d("activeW", 10);
        standbyW = params.d("standbyW", 0);
        burstW = params.d("burstW", 0);
        burstDuty = params.d("burstDuty", 0);
        batteryWh = params.d("batteryWh", 0);
        chargeW = params.d("chargeW", 0);
        utcOffsetH = params.d("utcOffsetH", 2);
        socWh = params.d("soc0", 0.8) * batteryWh;
        for (String m : params.list("offModes", List.of("away"))) {
            int i = BoatClock.mode(m);
            if (i < 0) {
                throw new IllegalArgumentException(id + ": unknown mode '" + m + "' in offModes");
            }
            offModes[i] = true;
        }
        if (batteryWh > 0 && chargeW <= 0) {
            throw new IllegalArgumentException(id + ": a device with batteryWh needs chargeW");
        }
    }

    @Override
    public String type() {
        return "device";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("mode", "1"), PortSpec.flow("p_meas_w", "W"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_w", "W"), PortSpec.signal("active", "1"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("on", params.d("on", 1));
        c.put("scale", params.d("scale", 1));
        c.put("max_on_h", params.d("maxOnH", 24));
        c.put("path_eff", params.d("pathEff", 0.9));
        c.put("charge_from_h", params.d("chargeFromH", 0));
        c.put("charge_to_h", params.d("chargeToH", 24));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("energy_wh", energyWh);
        s.put("device_wh", deviceWh);
        s.put("active_h", activeH);
        s.put("day", day);
        s.put("on_today_h", onTodayH);
        s.put("soc_wh", socWh);
        s.put("empty_h", emptyH);
        s.put("unmet_use_wh", unmetUseWh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        energyWh = s.get("energy_wh");
        deviceWh = s.get("device_wh");
        activeH = s.get("active_h");
        day = s.get("day");
        onTodayH = s.get("on_today_h");
        socWh = s.get("soc_wh");
        emptyH = s.get("empty_h");
        unmetUseWh = s.get("unmet_use_wh");
    }

    @Override
    public void step(StepContext ctx) {
        double h = ctx.dtHours();
        double eff = Math.max(0.05, Math.min(1, ctx.control("path_eff")));
        double d = BoatClock.localEpochDay(ctx.tMicros(), utcOffsetH);
        if (d != day) {
            day = d;
            onTodayH = 0;
        }
        double hour = BoatClock.localHour(ctx.tMicros(), utcOffsetH);
        boolean on = ctx.control("on") > 0.5;

        if (ctx.has("p_meas_w")) {
            // a what-if on history applies the controls to the measurement (unchanged in the reference)
            double meas = Math.max(0, ctx.in("p_meas_w").getAsDouble());
            double scale = ctx.control("scale");
            boolean wants = meas > standbyW + 0.5;
            boolean capped = wants && onTodayH + h > ctx.control("max_on_h") + 1e-9;
            boolean active = on && wants && !capped;
            double devW = !on ? 0 : capped ? Math.min(meas, standbyW) : wants ? meas * scale : meas;
            if (active) {
                activeH += h;
                onTodayH += h;
            }
            account(devW, eff, h);
            if (devW == meas) {
                ctx.out("p_w", devW / eff);
                ctx.out("active", active ? 1 : 0);
            } else {
                ctx.out("p_w", devW / eff, Origin.ESTIMATED);
                ctx.out("active", active ? 1 : 0, Origin.ESTIMATED);
            }
            return;
        }

        int mode = BoatClock.modeOf(ctx.inOr("mode", BoatClock.HARBOUR));
        if (offModes[mode]) {
            ctx.out("p_w", 0, Origin.SYNTHETIC);
            ctx.out("active", 0, Origin.SYNTHETIC);
            return;
        }
        boolean active = on && BoatClock.inAny(windows.get(mode), hour)
                && onTodayH + h <= ctx.control("max_on_h") + 1e-9;
        if (active) {
            activeH += h;
            onTodayH += h;
        }
        double use = active ? ctx.control("scale") * (activeW * (1 - burstDuty) + burstW * burstDuty)
                : on ? standbyW : 0;
        double devW;
        if (batteryWh <= 0) {
            devW = use;
        } else {
            double from = ctx.control("charge_from_h");
            double to = ctx.control("charge_to_h");
            boolean plugged = on && (to - from >= 24 || new BoatClock.Window(from, to).contains(hour));
            double intake = 0;
            if (plugged) {
                double frac = socWh / batteryWh;
                double rate = frac < 0.8 ? chargeW : chargeW * Math.max(0.15, (1 - frac) / 0.2);
                intake = Math.min(chargeW, use + Math.min(rate, (batteryWh - socWh) / h));
            }
            socWh += (intake - use) * h;
            if (socWh < 0) {
                unmetUseWh += -socWh;
                emptyH += h;
                socWh = 0;
            }
            devW = intake;
        }
        account(devW, eff, h);
        ctx.out("p_w", devW / eff, Origin.SYNTHETIC);
        ctx.out("active", active ? 1 : 0, Origin.SYNTHETIC);
    }

    private void account(double devW, double eff, double h) {
        deviceWh += devW * h;
        energyWh += devW / eff * h;
    }
}
