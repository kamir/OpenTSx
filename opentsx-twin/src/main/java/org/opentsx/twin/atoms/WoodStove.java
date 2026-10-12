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
 * H3: wood stove (Kaminofen) in the living room, a second converter that heats the zone directly. It is fired in
 * batches: during the heating season for {@code hours_per_day} hours ending at {@code endH} (local time), at a fuel
 * power {@code fuelKw}, with efficiency {@code eta}. Wood is counted in Ster (stacked cubic metres, Raummeter) with
 * {@code kwhPerSter} (assumption: spruce, air-dry, about 1500 kWh per Ster; beech about 2000).
 * <p>
 * If {@code wood_kwh_per_day} is bound (e.g. derived from the wood quantities on the bills), the stove burns exactly
 * that energy per day, spread over its burn hours, regardless of the season signal.
 * <p>
 * Controls: {@code hours_per_day}, {@code fuel_kw}.
 */
public final class WoodStove extends Atom {

    private final double eta;
    private final double endH;
    private final double kwhPerSter;
    private final LocalClock clock;

    private double fuelKwh;
    private double heatKwh;
    private double burnHours;

    public WoodStove(String id, Params params) {
        super(id, params);
        eta = params.d("eta", 0.70);
        endH = params.d("endH", 22);
        kwhPerSter = params.d("kwhPerSter", 1500);
        clock = new LocalClock(params.s("zone", "Europe/Berlin"));
    }

    @Override
    public String type() {
        return "wood-stove";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("season", "1"), PortSpec.signal("wood_kwh_per_day", "kWh/d"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("q_heat_kw", "kW"), PortSpec.flow("wood_ster_ph", "Ster/h"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("hours_per_day", params.d("hoursPerDay", 4));
        c.put("fuel_kw", params.d("fuelKw", 7));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("fuel_kwh", fuelKwh);
        s.put("wood_ster", fuelKwh / kwhPerSter);
        s.put("heat_kwh", heatKwh);
        s.put("burn_h", burnHours);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        fuelKwh = s.get("fuel_kwh");
        heatKwh = s.get("heat_kwh");
        burnHours = s.get("burn_h");
    }

    @Override
    public void step(StepContext ctx) {
        double n = Math.max(0, Math.min(24, ctx.control("hours_per_day")));
        double h = clock.hour(ctx.tMicros());
        double start = endH - n;
        boolean window = n > 0 && (start >= 0 ? h >= start && h < endH : h < endH || h >= start + 24);
        OptionalDouble daily = ctx.in("wood_kwh_per_day");
        double fuel;
        if (daily.isPresent()) {
            fuel = window ? Math.max(0, daily.getAsDouble()) / n : 0;
        } else {
            fuel = window && ctx.inOr("season", 1) > 0 ? Math.max(0, ctx.control("fuel_kw")) : 0;
        }
        double dt = ctx.dtHours();
        fuelKwh += fuel * dt;
        heatKwh += eta * fuel * dt;
        if (fuel > 0) {
            burnHours += dt;
        }
        ctx.out("q_heat_kw", eta * fuel);
        ctx.out("wood_ster_ph", fuel / kwhPerSter);
    }

    public double kwhPerSter() {
        return kwhPerSter;
    }
}
