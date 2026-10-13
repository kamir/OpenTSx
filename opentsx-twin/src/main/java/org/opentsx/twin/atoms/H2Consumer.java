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
 * A21: hydrogen customer behind a handover station. The demand comes from outside (a profile, a contract, a
 * forecast); the customer passes it on as a request, which is information, and counts what arrives, which is gas.
 * What arrives is read one step late: the handover station acts after the customer has asked.
 */
public final class H2Consumer extends Atom {

    private double requestedKg;
    private double receivedKg;

    public H2Consumer(String id, Params params) {
        super(id, params);
    }

    @Override
    public String type() {
        return "h2-consumer";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("demand_kgph", "kg/h"), PortSpec.flow("m_in_kgph", "kg/h"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("request_kgph", "kg/h"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("requested_kg", requestedKg);
        s.put("received_kg", receivedKg);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        requestedKg = s.get("requested_kg");
        receivedKg = s.get("received_kg");
    }

    @Override
    public void step(StepContext ctx) {
        double demand = Math.max(0, ctx.inOr("demand_kgph", params.d("demandKgph", 0)));
        requestedKg += demand * ctx.dtHours();
        receivedKg += Math.max(0, ctx.inOr("m_in_kgph", 0)) * ctx.dtHours();
        ctx.out("request_kgph", demand);
    }
}
