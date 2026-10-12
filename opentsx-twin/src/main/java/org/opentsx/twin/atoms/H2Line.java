package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A10: hydrogen line as a buffer (linepack). Ideal gas, isothermal: {@code p = m R T / V}. Reports the time until
 * the pressure limit is reached at the current net inflow, and the remaining headroom in kg.
 * <p>
 * Events: {@code time_to_limit_warning} (falls below {@code warnMin}), {@code pressure_limit} (exact crossing time
 * of {@code pMaxBar} inside the step), {@code pressure_low}.
 */
public final class H2Line extends Atom {

    /** specific gas constant of hydrogen, J/(kg K) */
    public static final double R_H2 = 4124.2;

    private final double volumeM3;
    private final double tempK;
    private final double pMinBar;
    private final double pMaxBar;
    private final double warnMin;

    private double massKg;
    private double pBar;
    private double pMaxSeenBar;
    private double warned;
    private double low;

    public H2Line(String id, Params params) {
        super(id, params);
        volumeM3 = params.d("volumeM3", 157);
        tempK = params.d("tempK", 288);
        pMinBar = params.d("pMinBar", 20);
        pMaxBar = params.d("pMaxBar", 40);
        warnMin = params.d("warnMin", 60);
        pBar = params.d("p0Bar", 30);
        massKg = massAt(pBar);
        pMaxSeenBar = pBar;
    }

    private double massAt(double bar) {
        return bar * 1e5 * volumeM3 / (R_H2 * tempK);
    }

    private double barPerKg() {
        return R_H2 * tempK / volumeM3 / 1e5;
    }

    @Override
    public String type() {
        return "h2-line";
    }

    @Override
    public Phase phase() {
        return Phase.FLOWS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("m_in_kgph", "kg/h"), PortSpec.flow("m_out_kgph", "kg/h"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.signal("p_bar", "bar"),
                PortSpec.signal("ttl_min", "min"),
                PortSpec.signal("headroom_kg", "kg"),
                PortSpec.flow("m_net_kgph", "kg/h"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("mass_kg", massKg);
        s.put("p_bar", pBar);
        s.put("p_max_bar", pMaxSeenBar);
        s.put("warned", warned);
        s.put("low", low);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        massKg = s.get("mass_kg");
        pBar = s.get("p_bar");
        pMaxSeenBar = s.get("p_max_bar");
        warned = s.get("warned");
        low = s.get("low");
    }

    @Override
    public Map<String, Double> restingOutputs() {
        return Map.of("p_bar", pBar, "headroom_kg", Math.max(0, massAt(pMaxBar) - massKg));
    }

    @Override
    public void step(StepContext ctx) {
        double in = Math.max(0, ctx.inOr("m_in_kgph", 0));
        double out = Math.max(0, ctx.inOr("m_out_kgph", 0));
        double net = in - out;
        double pPrev = pBar;
        massKg = Math.max(0, massKg + net * ctx.dtHours());
        pBar = massKg * barPerKg();
        pMaxSeenBar = Math.max(pMaxSeenBar, pBar);

        double ttl = Double.NaN;
        if (pBar >= pMaxBar) {
            ttl = 0;
        } else if (net > 0) {
            ttl = (pMaxBar - pBar) / (net * barPerKg() / 60.0);
        }
        if (!Double.isNaN(ttl) && ttl < warnMin) {
            if (warned == 0) {
                warned = 1;
                ctx.event("time_to_limit_warning", ctx.tMicros() + (long) (ctx.dtSeconds() * 1e6),
                        Map.of("ttl_min", Electrolyzer.fmt(ttl), "p_bar", Electrolyzer.fmt(pBar)));
            }
        } else {
            warned = 0;
        }
        if (pPrev < pMaxBar && pBar >= pMaxBar) {
            double frac = (pMaxBar - pPrev) / (pBar - pPrev);
            ctx.event("pressure_limit", ctx.tMicros() + (long) (frac * ctx.dtSeconds() * 1e6),
                    Map.of("p_max_bar", Electrolyzer.fmt(pMaxBar)));
        }
        if (pBar < pMinBar) {
            if (low == 0) {
                low = 1;
                ctx.event("pressure_low", Map.of("p_bar", Electrolyzer.fmt(pBar)));
            }
        } else {
            low = 0;
        }
        ctx.out("p_bar", pBar);
        ctx.out("ttl_min", ttl);
        ctx.out("headroom_kg", Math.max(0, massAt(pMaxBar) - massKg));
        ctx.out("m_net_kgph", net);
    }
}
