package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.Policies;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * A9: electrolyzer. Follows a power setpoint within minimum load and ramp limits; converts power to hydrogen with a
 * constant specific consumption. <b>Protective shutdown:</b> when the downstream line pressure reaches
 * {@code pTripBar} it ramps to zero and stays there until the pressure falls to {@code pResumeBar}.
 * <p>
 * Modes (state {@code mode}): 0 off, 1 running, 2 protective shutdown.
 */
public final class Electrolyzer extends Atom {

    static final double OFF = 0;
    static final double RUN = 1;
    static final double PROTECT = 2;

    private final double ratedKw;
    private final double minLoad;
    private final double rampKwPerMin;
    private final double kwhPerKg;
    private final double pTripBar;
    private final double pResumeBar;

    private double pElKw;
    private double mode;
    private double h2Kg;
    private double energyKwh;
    private double trips;

    public Electrolyzer(String id, Params params) {
        super(id, params);
        ratedKw = params.d("ratedKw", 5_000);
        minLoad = params.d("minLoad", 0.1);
        rampKwPerMin = params.d("rampKwPerMin", 500);
        kwhPerKg = params.d("kwhPerKg", 55);
        pTripBar = params.d("pTripBar", 40);
        pResumeBar = params.d("pResumeBar", 36);
        pElKw = params.d("initialKw", 0);
        mode = pElKw > 0 ? RUN : OFF;
    }

    /**
     * Policy {@code pressure-aware}: follows the setpoint, but limits hydrogen production to the offtake plus the
     * line's headroom spread over a horizon ({@code policy.horizonMin}, default 60). Re-evaluated every superstep, it
     * approaches the pressure limit without reaching it (a simple rolling-horizon rule, not a full MPC).
     */
    public static void pressureAware(Atom atom, StepContext ctx) {
        Policies.follow(atom, ctx);
        if (!(atom instanceof Electrolyzer e)) {
            throw new IllegalArgumentException("pressure-aware applies to electrolyzers, not " + atom.type());
        }
        OptionalDouble headroom = ctx.in("headroom_kg");
        if (headroom.isEmpty()) {
            return;
        }
        double horizonH = e.params.d("policy.horizonMin", 60) / 60.0;
        double offtake = ctx.inOr("m_offtake_kgph", 0);
        double allowedKgph = Math.max(0, offtake + Math.max(0, headroom.getAsDouble()) / horizonH);
        ctx.setControl("setpoint_kw", Math.min(ctx.control("setpoint_kw"), allowedKgph * e.kwhPerKg));
    }

    @Override
    public String type() {
        return "electrolyzer";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.signal("setpoint_kw", "kW"),
                PortSpec.signal("p_downstream_bar", "bar"),
                PortSpec.signal("headroom_kg", "kg"),
                PortSpec.flow("m_offtake_kgph", "kg/h"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_el_kw", "kW"), PortSpec.flow("m_h2_kgph", "kg/h"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("setpoint_kw", 0.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("p_el_kw", pElKw);
        s.put("mode", mode);
        s.put("h2_kg", h2Kg);
        s.put("energy_kwh", energyKwh);
        s.put("trips", trips);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        pElKw = s.get("p_el_kw");
        mode = s.get("mode");
        h2Kg = s.get("h2_kg");
        energyKwh = s.get("energy_kwh");
        trips = s.get("trips");
    }

    @Override
    public Map<String, Double> restingOutputs() {
        return Map.of("p_el_kw", pElKw, "m_h2_kgph", pElKw / kwhPerKg);
    }

    @Override
    public void step(StepContext ctx) {
        OptionalDouble p = ctx.in("p_downstream_bar");
        if (p.isPresent()) {
            if (mode != PROTECT && p.getAsDouble() >= pTripBar) {
                mode = PROTECT;
                trips++;
                ctx.event("protective_shutdown", Map.of("p_bar", fmt(p.getAsDouble()), "p_el_kw", fmt(pElKw)));
            } else if (mode == PROTECT && p.getAsDouble() <= pResumeBar) {
                mode = RUN;
                ctx.event("protection_released", Map.of("p_bar", fmt(p.getAsDouble())));
            }
        }
        double setpoint = ctx.control("setpoint_kw");
        double target = mode == PROTECT || Double.isNaN(setpoint) ? 0 : Math.max(0, Math.min(ratedKw, setpoint));
        double min = minLoad * ratedKw;
        if (target > 0 && target < min) {
            target = target < min / 2 ? 0 : min;
        }
        double maxDelta = rampKwPerMin * ctx.dtMinutes();
        pElKw += Math.max(-maxDelta, Math.min(maxDelta, target - pElKw));
        if (mode != PROTECT) {
            mode = pElKw > 0 || target > 0 ? RUN : OFF;
        }
        double m = pElKw / kwhPerKg;
        h2Kg += m * ctx.dtHours();
        energyKwh += pElKw * ctx.dtHours();
        ctx.out("p_el_kw", pElKw);
        ctx.out("m_h2_kgph", m);
    }

    public double kwhPerKg() {
        return kwhPerKg;
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
