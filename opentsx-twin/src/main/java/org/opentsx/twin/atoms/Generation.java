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
 * A1/A3 (simplified): wind-farm generation at the grid connection point. Available power comes from a measurement or
 * a forecast; the park controller limit ({@code limit_kw}) curtails it.
 */
public final class Generation extends Atom {

    private final double ratedKw;
    private double energyKwh;
    private double curtailedKwh;

    public Generation(String id, Params params) {
        super(id, params);
        this.ratedKw = params.d("ratedKw", 10_000);
    }

    @Override
    public String type() {
        return "generation";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("p_avail_kw", "kW"), PortSpec.signal("limit_kw", "kW"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_gen_kw", "kW"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("limit_kw", ratedKw);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("energy_kwh", energyKwh);
        s.put("curtailed_kwh", curtailedKwh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        energyKwh = s.get("energy_kwh");
        curtailedKwh = s.get("curtailed_kwh");
    }

    @Override
    public void step(StepContext ctx) {
        double avail = Math.min(Math.max(ctx.inOr("p_avail_kw", 0), 0), ratedKw);
        double limit = ctx.control("limit_kw");
        double p = Math.max(0, Math.min(avail, Double.isNaN(limit) ? ratedKw : limit));
        energyKwh += p * ctx.dtHours();
        curtailedKwh += (avail - p) * ctx.dtHours();
        ctx.out("p_gen_kw", p);
    }
}
