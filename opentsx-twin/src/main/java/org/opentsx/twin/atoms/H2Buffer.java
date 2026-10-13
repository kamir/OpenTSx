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
 * A18: high-pressure hydrogen buffer with the pressure regulator that feeds the pipeline behind it. Ideal gas,
 * isothermal: {@code p = m R T / V}.
 * <p>
 * The regulator replaces what is drawn from the pipeline ({@code m_draw_kgph}) and corrects the pipeline pressure
 * towards {@code lineSetBar} with a proportional term. It cannot take gas back, and it does not empty the buffer
 * below {@code pMinBar}.
 * <p>
 * <b>Simplifications, on purpose:</b> no compressibility factor (at 200 bar a real vessel holds about a tenth less
 * hydrogen than this one), no temperature change while filling or emptying, one vessel.
 * <p>
 * Events: {@code time_to_limit_warning} (the time until {@code pMaxBar} falls below {@code warnMin}),
 * {@code pressure_limit} (exact crossing time inside the step), {@code buffer_low} (the regulator had to hold back
 * because the buffer is at {@code pMinBar}).
 */
public final class H2Buffer extends Atom {

    private final double volumeM3;
    private final double tempK;
    private final double pMinBar;
    private final double pMaxBar;
    private final double warnMin;
    private final double lineSetBar;
    private final double gainKgphPerBar;
    private final double maxOutKgph;
    private final double initialOutKgph;

    private double massKg;
    private double pBar;
    private double pMaxSeenBar;
    private double outKgph;
    private double warned;
    private double low;

    public H2Buffer(String id, Params params) {
        super(id, params);
        volumeM3 = params.d("volumeM3", 20);
        tempK = params.d("tempK", 288);
        pMinBar = params.d("pMinBar", 50);
        pMaxBar = params.d("pMaxBar", 200);
        warnMin = params.d("warnMin", 60);
        lineSetBar = params.d("lineSetBar", 30);
        gainKgphPerBar = params.d("gainKgphPerBar", 20);
        maxOutKgph = params.d("maxOutKgph", 200);
        initialOutKgph = params.d("initialOutKgph", 0);
        pBar = params.d("p0Bar", 120);
        massKg = massAt(pBar);
        pMaxSeenBar = pBar;
        outKgph = initialOutKgph;
    }

    private double massAt(double bar) {
        return bar * 1e5 * volumeM3 / (H2Line.R_H2 * tempK);
    }

    private double barPerKg() {
        return H2Line.R_H2 * tempK / volumeM3 / 1e5;
    }

    @Override
    public String type() {
        return "h2-buffer";
    }

    @Override
    public Phase phase() {
        return Phase.FLOWS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.flow("m_in_kgph", "kg/h"),
                PortSpec.flow("m_draw_kgph", "kg/h"),
                PortSpec.signal("p_line_bar", "bar"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.flow("m_out_kgph", "kg/h"),
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
        s.put("out_kgph", outKgph);
        s.put("warned", warned);
        s.put("low", low);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        massKg = s.get("mass_kg");
        pBar = s.get("p_bar");
        pMaxSeenBar = s.get("p_max_bar");
        outKgph = s.get("out_kgph");
        warned = s.get("warned");
        low = s.get("low");
    }

    @Override
    public Map<String, Double> restingOutputs() {
        return Map.of("m_out_kgph", outKgph, "p_bar", pBar, "headroom_kg", Math.max(0, massAt(pMaxBar) - massKg));
    }

    @Override
    public void step(StepContext ctx) {
        double in = Math.max(0, ctx.inOr("m_in_kgph", 0));
        double draw = Math.max(0, ctx.inOr("m_draw_kgph", 0));
        double wanted = Math.max(0, Math.min(maxOutKgph, draw + gainKgphPerBar * (lineSetBar - ctx.inOr("p_line_bar", lineSetBar))));
        // what the buffer can give in this step without falling below its lowest pressure
        double available = Math.max(0, (massKg - massAt(pMinBar)) / ctx.dtHours() + in);
        double out = Math.min(wanted, available);
        if (out < wanted - 1e-9) {
            if (low == 0) {
                low = 1;
                ctx.event("buffer_low", Map.of("p_bar", Electrolyzer.fmt(pBar), "wanted_kgph", Electrolyzer.fmt(wanted)));
            }
        } else {
            low = 0;
        }
        double net = in - out;
        double pPrev = pBar;
        massKg = Math.max(0, massKg + net * ctx.dtHours());
        pBar = massKg * barPerKg();
        pMaxSeenBar = Math.max(pMaxSeenBar, pBar);
        outKgph = out;

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
        ctx.out("m_out_kgph", out);
        ctx.out("p_bar", pBar);
        ctx.out("ttl_min", ttl);
        ctx.out("headroom_kg", Math.max(0, massAt(pMaxBar) - massKg));
        ctx.out("m_net_kgph", net);
    }
}
