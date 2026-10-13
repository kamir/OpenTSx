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
 * A20: handover station with its regulating valve, the point where hydrogen passes to the customer. It delivers what
 * the customer requests, as far as the valve is open and the pressure at the end of the pipeline is at least
 * {@code pMinBar}. What it cannot deliver is a shortfall, reported as a flow and summed up.
 * <p>
 * The valve (0..1) is a control; closing it is the typical disturbance. The request is information from the
 * customer, not a flow of gas.
 * <p>
 * <b>Simplification, on purpose:</b> below the lowest delivery pressure the station delivers nothing; a real
 * regulator would throttle.
 * <p>
 * Events: {@code delivery_interrupted} (pressure below {@code pMinBar} while something is requested),
 * {@code delivery_resumed}.
 */
public final class H2Handover extends Atom {

    private final double pMinBar;
    private final double maxKgph;

    private double deliveredKg;
    private double shortfallKg;
    private double interrupted;

    public H2Handover(String id, Params params) {
        super(id, params);
        pMinBar = params.d("pMinBar", 20);
        maxKgph = params.d("maxKgph", 150);
    }

    @Override
    public String type() {
        return "h2-handover";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.signal("request_kgph", "kg/h"),
                PortSpec.signal("p_in_bar", "bar"),
                PortSpec.signal("valve", "1"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.flow("m_out_kgph", "kg/h"),
                PortSpec.signal("p_delivery_bar", "bar"),
                PortSpec.flow("shortfall_kgph", "kg/h"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("valve", 1.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("delivered_kg", deliveredKg);
        s.put("shortfall_kg", shortfallKg);
        s.put("interrupted", interrupted);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        deliveredKg = s.get("delivered_kg");
        shortfallKg = s.get("shortfall_kg");
        interrupted = s.get("interrupted");
    }

    @Override
    public void step(StepContext ctx) {
        double request = Math.max(0, ctx.inOr("request_kgph", 0));
        double valve = Math.max(0, Math.min(1, ctx.control("valve")));
        double p = ctx.inOr("p_in_bar", pMinBar);
        boolean enough = p >= pMinBar;
        if (!enough && request > 0) {
            if (interrupted == 0) {
                interrupted = 1;
                ctx.event("delivery_interrupted", Map.of("p_bar", Electrolyzer.fmt(p), "p_min_bar", Electrolyzer.fmt(pMinBar)));
            }
        } else if (interrupted != 0) {
            interrupted = 0;
            ctx.event("delivery_resumed", Map.of("p_bar", Electrolyzer.fmt(p)));
        }
        double m = enough ? Math.min(maxKgph, request * valve) : 0;
        deliveredKg += m * ctx.dtHours();
        shortfallKg += (request - m) * ctx.dtHours();
        ctx.out("m_out_kgph", m);
        ctx.out("p_delivery_bar", p);
        ctx.out("shortfall_kgph", request - m);
    }
}
