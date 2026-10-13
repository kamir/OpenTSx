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
 * A17: hydrogen compressor between the electrolyzer and a high-pressure buffer. It passes on what it takes in and
 * draws the power the compression needs.
 * <p>
 * <b>Simplifications, on purpose:</b> ideal gas, one adiabatic stage without intercooling, a constant isentropic
 * efficiency, a fixed suction pressure ({@code pInBar}, the outlet pressure of the electrolyzer). A real machine has
 * several stages, a characteristic map and limits of its own; that belongs to a checking model, not to this atom.
 * <p>
 * Event: {@code throughput_limit} when the flow first exceeds {@code ratedKgph}; the flow is not cut, so no mass is
 * lost in the model.
 */
public final class H2Compressor extends Atom {

    /** specific heat capacity of hydrogen at constant pressure, J/(kg K) */
    static final double CP_H2 = 14_300;
    /** (kappa - 1) / kappa for kappa = 1.41 */
    static final double EXPONENT = 0.41 / 1.41;

    private final double pInBar;
    private final double etaIs;
    private final double tempK;
    private final double ratedKgph;

    private double h2Kg;
    private double energyKwh;
    private double over;

    public H2Compressor(String id, Params params) {
        super(id, params);
        pInBar = params.d("pInBar", 30);
        etaIs = params.d("etaIs", 0.75);
        tempK = params.d("tempK", 288);
        ratedKgph = params.d("ratedKgph", 120);
    }

    /** Electrical energy per kg of hydrogen compressed to {@code pOutBar}, in kWh/kg; 0 when nothing is to compress. */
    public double kwhPerKg(double pOutBar) {
        if (!(pOutBar > pInBar)) {
            return 0;
        }
        return CP_H2 * tempK / etaIs * (Math.pow(pOutBar / pInBar, EXPONENT) - 1) / 3.6e6;
    }

    @Override
    public String type() {
        return "h2-compressor";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("m_in_kgph", "kg/h"), PortSpec.signal("p_out_bar", "bar"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("m_out_kgph", "kg/h"), PortSpec.flow("p_el_kw", "kW"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("h2_kg", h2Kg);
        s.put("energy_kwh", energyKwh);
        s.put("over", over);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        h2Kg = s.get("h2_kg");
        energyKwh = s.get("energy_kwh");
        over = s.get("over");
    }

    @Override
    public void step(StepContext ctx) {
        double m = Math.max(0, ctx.inOr("m_in_kgph", 0));
        double p = m * kwhPerKg(ctx.inOr("p_out_bar", pInBar));
        if (m > ratedKgph) {
            if (over == 0) {
                over = 1;
                ctx.event("throughput_limit", Map.of("m_kgph", Electrolyzer.fmt(m), "rated_kgph", Electrolyzer.fmt(ratedKgph)));
            }
        } else {
            over = 0;
        }
        h2Kg += m * ctx.dtHours();
        energyKwh += p * ctx.dtHours();
        ctx.out("m_out_kgph", m);
        ctx.out("p_el_kw", p);
    }
}
