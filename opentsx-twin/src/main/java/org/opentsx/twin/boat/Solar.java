package org.opentsx.twin.boat;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solar panels on a boat (flat on deck or bimini), DC power offered to the charge controller.
 * <p>
 * {@code p_avail_w = wp · ghi / 1000 · derate · (1 − shade(mode))}. The irradiance {@code ghi_wm2} comes from a bound
 * series (measurement or forecast) or, if none is bound, from a deterministic synthetic clear-sky model for
 * {@code latitude}/{@code longitude} (Meinel air-mass model, no equation of time) times a daily clearness index
 * ({@code clearness}, cycled per local day from {@code dayZero}). A bound {@code p_meas_w} (PV power measured at the
 * power station) replaces the model; in a what-if it is scaled by {@code wp/wp₀ · derate/derate₀} and then counts
 * as {@link Origin#ESTIMATED}. (A measured PV power is what the station took, so it is curtailed while the battery
 * is full; the scaling is an approximation.)
 * <p>
 * Controls {@code wp} (installed peak power) and {@code derate} (temperature, soiling, wiring, MPPT) default to their
 * parameters, so a what-if or a calibration can change them by intervention. Shading per mode:
 * {@code shadeHarbour}, {@code shadeSailing}, {@code shadeAnchor}, {@code shadeAway}.
 */
public final class Solar extends Atom {

    private static final List<Double> DEFAULT_CLEARNESS = List.of(0.85, 0.6, 0.9, 0.45, 0.75, 0.95, 0.55);

    private final double latitude;
    private final double longitude;
    private final double utcOffsetH;
    private final long dayZero;
    private final double[] shade;
    private final List<Double> clearness = new ArrayList<>();
    private double yieldWh;
    private double peakW;

    public Solar(String id, Params params) {
        super(id, params);
        latitude = params.d("latitude", 54.3);
        longitude = params.d("longitude", 10.1);
        utcOffsetH = params.d("utcOffsetH", 2);
        dayZero = BoatClock.epochDay(params);
        shade = new double[]{params.d("shadeHarbour", 0.15), params.d("shadeSailing", 0.35),
                params.d("shadeAnchor", 0.10), params.d("shadeAway", 0.15)};
        Object c = params.asMap().get("clearness");
        if (c instanceof List<?> l && !l.isEmpty()) {
            l.forEach(x -> clearness.add(x instanceof Number n ? n.doubleValue() : Double.parseDouble(x.toString())));
        } else {
            clearness.addAll(DEFAULT_CLEARNESS);
        }
    }

    @Override
    public String type() {
        return "solar";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("mode", "1"), PortSpec.signal("ghi_wm2", "W/m2"), PortSpec.flow("p_meas_w", "W"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_avail_w", "W"), PortSpec.signal("ghi_wm2", "W/m2"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("wp", params.d("wp", 200));
        c.put("derate", params.d("derate", 0.75));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("yield_wh", yieldWh);
        s.put("peak_w", peakW);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        yieldWh = s.get("yield_wh");
        peakW = s.get("peak_w");
    }

    @Override
    public void step(StepContext ctx) {
        double p;
        if (ctx.has("p_meas_w")) {
            // a what-if on history scales the measurement by the changed controls (ratio 1 in the reference)
            double ratio = ctx.control("wp") / params.d("wp", 200) * ctx.control("derate") / params.d("derate", 0.75);
            p = Math.max(0, ctx.in("p_meas_w").getAsDouble()) * ratio;
            if (ratio == 1) {
                ctx.out("p_avail_w", p);
            } else {
                ctx.out("p_avail_w", p, Origin.ESTIMATED);
            }
        } else {
            boolean synthetic = !ctx.has("ghi_wm2");
            double ghi = synthetic ? syntheticGhi(ctx.tMicros() + (long) (ctx.dtSeconds() * 5e5))
                    : Math.max(0, ctx.in("ghi_wm2").getAsDouble());
            int mode = BoatClock.modeOf(ctx.inOr("mode", BoatClock.HARBOUR));
            p = ctx.control("wp") * ghi / 1000.0 * ctx.control("derate") * (1 - shade[mode]);
            p = Math.max(0, p);
            if (synthetic) {
                ctx.out("ghi_wm2", ghi, Origin.SYNTHETIC);
                ctx.out("p_avail_w", p, Origin.SYNTHETIC);
            } else {
                ctx.out("ghi_wm2", ghi);
                ctx.out("p_avail_w", p);
            }
        }
        yieldWh += p * ctx.dtHours();
        peakW = Math.max(peakW, p);
    }

    /** Synthetic global horizontal irradiance at {@code tMicros} (W/m²): clear sky × daily clearness. */
    public double syntheticGhi(long tMicros) {
        long day = BoatClock.dayIndex(tMicros, utcOffsetH, dayZero);
        double kt = clearness.get((int) Math.floorMod(day, clearness.size()));
        return clearSkyGhi(tMicros, latitude, longitude) * kt;
    }

    /** Clear-sky GHI (W/m²) after Meinel: DNI = 1353 · 0.7^(AM^0.678), GHI ≈ 1.1 · DNI · sin(h). */
    public static double clearSkyGhi(long tMicros, double latitudeDeg, double longitudeDeg) {
        double days = tMicros / 86_400e6;
        int doy = java.time.LocalDate.ofEpochDay((long) Math.floor(days)).getDayOfYear();
        double utcHours = (days - Math.floor(days)) * 24;
        double decl = Math.toRadians(23.45 * Math.sin(2 * Math.PI * (284 + doy) / 365.0));
        double omega = Math.toRadians(15 * (utcHours + longitudeDeg / 15.0 - 12));
        double phi = Math.toRadians(latitudeDeg);
        double sinH = Math.sin(phi) * Math.sin(decl) + Math.cos(phi) * Math.cos(decl) * Math.cos(omega);
        if (sinH <= 0.01) {
            return 0;
        }
        double am = 1 / sinH;
        double dni = 1353 * Math.pow(0.7, Math.pow(am, 0.678));
        return 1.1 * dni * sinH;
    }
}
