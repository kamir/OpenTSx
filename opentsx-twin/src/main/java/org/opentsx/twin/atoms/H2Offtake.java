package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A11: hydrogen offtake. The valve (0..1) is a control; closing it is the typical disturbance. */
public final class H2Offtake extends Atom {

    private double deliveredKg;

    public H2Offtake(String id, Params params) {
        super(id, params);
    }

    @Override
    public String type() {
        return "h2-offtake";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("demand_kgph", "kg/h"), PortSpec.signal("valve", "1"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("m_out_kgph", "kg/h"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("valve", 1.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("delivered_kg", deliveredKg);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        deliveredKg = s.get("delivered_kg");
    }

    @Override
    public void step(StepContext ctx) {
        double demand = Math.max(0, ctx.inOr("demand_kgph", params.d("demandKgph", 0)));
        double valve = Math.max(0, Math.min(1, ctx.control("valve")));
        double m = demand * valve;
        deliveredKg += m * ctx.dtHours();
        ctx.out("m_out_kgph", m);
    }
}
