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
 * A19: hydrogen pipeline with a pressure at each end. Its content is a second, small buffer (linepack): ideal gas,
 * isothermal, {@code p = m R T / V}. {@code p_in_bar} is the pressure of that content; the pressure at the far end
 * is lower by a loss that grows with the square of the flow taken out: {@code dropBar} at {@code dropRefKgph}.
 * <p>
 * <b>Simplifications, on purpose:</b> one pressure for the whole content, no friction model from length, diameter
 * and roughness, no height profile. The gas in the pipeline is counted here and nowhere else.
 * <p>
 * Events: {@code pressure_limit} (crossing of {@code pMaxBar} inside the step), {@code pressure_low} (below
 * {@code pMinBar}).
 */
public final class H2Pipeline extends Atom {

    private final double volumeM3;
    private final double tempK;
    private final double pMinBar;
    private final double pMaxBar;
    private final double dropBar;
    private final double dropRefKgph;
    private final double initialOutKgph;

    private double massKg;
    private double pBar;
    private double pMaxSeenBar;
    private double pMinSeenBar;
    private double outKgph;
    private double low;

    public H2Pipeline(String id, Params params) {
        super(id, params);
        volumeM3 = params.d("volumeM3", 157);
        tempK = params.d("tempK", 288);
        pMinBar = params.d("pMinBar", 20);
        pMaxBar = params.d("pMaxBar", 40);
        dropBar = params.d("dropBar", 1.5);
        dropRefKgph = params.d("dropRefKgph", 100);
        initialOutKgph = params.d("initialOutKgph", 0);
        pBar = params.d("p0Bar", 30);
        massKg = massAt(pBar);
        pMaxSeenBar = pBar;
        pMinSeenBar = pBar;
        outKgph = initialOutKgph;
    }

    private double massAt(double bar) {
        return bar * 1e5 * volumeM3 / (H2Line.R_H2 * tempK);
    }

    private double barPerKg() {
        return H2Line.R_H2 * tempK / volumeM3 / 1e5;
    }

    /** Pressure loss between the two ends at a flow, in bar. */
    public double drop(double kgph) {
        double x = kgph / dropRefKgph;
        return dropBar * x * x;
    }

    @Override
    public String type() {
        return "h2-pipeline";
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
                PortSpec.signal("p_in_bar", "bar"),
                PortSpec.signal("p_out_bar", "bar"),
                PortSpec.signal("linepack_kg", "kg"),
                PortSpec.flow("m_net_kgph", "kg/h"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("mass_kg", massKg);
        s.put("p_bar", pBar);
        s.put("p_max_bar", pMaxSeenBar);
        s.put("p_min_bar", pMinSeenBar);
        s.put("out_kgph", outKgph);
        s.put("low", low);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        massKg = s.get("mass_kg");
        pBar = s.get("p_bar");
        pMaxSeenBar = s.get("p_max_bar");
        pMinSeenBar = s.get("p_min_bar");
        outKgph = s.get("out_kgph");
        low = s.get("low");
    }

    @Override
    public Map<String, Double> restingOutputs() {
        return Map.of("p_in_bar", pBar, "p_out_bar", Math.max(0, pBar - drop(outKgph)));
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
        pMinSeenBar = Math.min(pMinSeenBar, pBar);
        outKgph = out;
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
        ctx.out("p_in_bar", pBar);
        ctx.out("p_out_bar", Math.max(0, pBar - drop(out)));
        ctx.out("linepack_kg", massKg);
        ctx.out("m_net_kgph", net);
    }
}
