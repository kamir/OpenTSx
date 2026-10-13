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
 * A22: wind farm with the three powers that say why energy was not generated.
 * <ol>
 *   <li>{@code p_potential_kw} (input): what the weather would allow, before any restriction.</li>
 *   <li>{@code p_avail_kw} (output): what may be generated after technical and environmental restrictions
 *   ({@code limit_kw}, usually wired from an operating-rules atom).</li>
 *   <li>{@code p_gen_kw} (output): what is generated after the dispatch ({@code dispatch_kw}, a command).</li>
 * </ol>
 * The energy not generated is summed per cause: {@code curtailed_rules_kwh} and {@code curtailed_dispatch_kwh}. A
 * kilowatt lost to a restriction is never also counted as dispatched away.
 */
public final class WindPark extends Atom {

    private final double ratedKw;

    private double potentialKwh;
    private double energyKwh;
    private double curtailedRulesKwh;
    private double curtailedDispatchKwh;

    public WindPark(String id, Params params) {
        super(id, params);
        this.ratedKw = params.d("ratedKw", 10_000);
    }

    @Override
    public String type() {
        return "wind-park";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.flow("p_potential_kw", "kW"),
                PortSpec.signal("limit_kw", "kW"),
                PortSpec.signal("dispatch_kw", "kW"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_avail_kw", "kW"), PortSpec.flow("p_gen_kw", "kW"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        // in a fixed order: a Map.of with two entries iterates differently from one JVM to the next
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("dispatch_kw", ratedKw);
        c.put("limit_kw", ratedKw);
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("potential_kwh", potentialKwh);
        s.put("energy_kwh", energyKwh);
        s.put("curtailed_rules_kwh", curtailedRulesKwh);
        s.put("curtailed_dispatch_kwh", curtailedDispatchKwh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        potentialKwh = s.get("potential_kwh");
        energyKwh = s.get("energy_kwh");
        curtailedRulesKwh = s.get("curtailed_rules_kwh");
        curtailedDispatchKwh = s.get("curtailed_dispatch_kwh");
    }

    @Override
    public void step(StepContext ctx) {
        double potential = Math.min(Math.max(ctx.inOr("p_potential_kw", 0), 0), ratedKw);
        double limit = ctx.control("limit_kw");
        double dispatch = ctx.control("dispatch_kw");
        double avail = Math.max(0, Math.min(potential, Double.isNaN(limit) ? ratedKw : limit));
        double p = Math.max(0, Math.min(avail, Double.isNaN(dispatch) ? ratedKw : dispatch));
        potentialKwh += potential * ctx.dtHours();
        energyKwh += p * ctx.dtHours();
        curtailedRulesKwh += (potential - avail) * ctx.dtHours();
        curtailedDispatchKwh += (avail - p) * ctx.dtHours();
        ctx.out("p_avail_kw", avail);
        ctx.out("p_gen_kw", p);
    }
}
