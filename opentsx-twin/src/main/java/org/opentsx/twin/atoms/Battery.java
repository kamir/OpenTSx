package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A8: battery storage. Control {@code power_kw}: positive charges, negative discharges; limited by power and SoC. */
public final class Battery extends Atom {

    private final double capacityKwh;
    private final double powerKw;
    private final double etaCharge;
    private final double etaDischarge;
    private double socKwh;
    private double throughputKwh;

    public Battery(String id, Params params) {
        super(id, params);
        capacityKwh = params.d("capacityKwh", 2_000);
        powerKw = params.d("powerKw", 1_000);
        etaCharge = params.d("etaCharge", 0.95);
        etaDischarge = params.d("etaDischarge", 0.95);
        socKwh = params.d("soc0", 0.5) * capacityKwh;
    }

    @Override
    public String type() {
        return "battery";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("power_kw", "kW"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_bat_kw", "kW"), PortSpec.signal("soc", "1"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("power_kw", 0.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("soc_kwh", socKwh);
        s.put("throughput_kwh", throughputKwh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        socKwh = s.get("soc_kwh");
        throughputKwh = s.get("throughput_kwh");
    }

    @Override
    public void step(StepContext ctx) {
        double req = ctx.control("power_kw");
        double p = Double.isNaN(req) ? 0 : Math.max(-powerKw, Math.min(powerKw, req));
        double h = ctx.dtHours();
        if (p > 0) {
            p = Math.min(p, (capacityKwh - socKwh) / etaCharge / h);
            socKwh += p * etaCharge * h;
        } else if (p < 0) {
            p = Math.max(p, -socKwh * etaDischarge / h);
            socKwh += p / etaDischarge * h;
        }
        socKwh = Math.max(0, Math.min(capacityKwh, socKwh));
        throughputKwh += Math.abs(p) * h;
        ctx.out("p_bat_kw", p);
        ctx.out("soc", socKwh / capacityKwh);
    }
}
