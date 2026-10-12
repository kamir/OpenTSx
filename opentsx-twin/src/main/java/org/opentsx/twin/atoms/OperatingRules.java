package org.opentsx.twin.atoms;

import org.opentsx.model.Micros;
import org.opentsx.twin.Atom;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A16: operating constraints (grid restrictions, field work, bat curtailment and similar). Time windows with a power
 * limit; outputs the tightest limit in force (NaN = no restriction), to be wired to a generation atom's
 * {@code limit_kw}.
 * <p>
 * Parameter {@code windows}: list of {@code {from: ISO, to: ISO, limitKw: number, reason: text}}.
 */
public final class OperatingRules extends Atom {

    private record Window(long from, long to, double limitKw, String reason) {
    }

    private final double defaultKw;
    private final List<Window> windows = new ArrayList<>();
    private double active;
    private double restrictedHours;

    @SuppressWarnings("unchecked")
    public OperatingRules(String id, Params params) {
        super(id, params);
        defaultKw = params.d("defaultKw", Double.NaN);
        Object w = params.asMap().get("windows");
        if (w instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                windows.add(new Window(Micros.of(Instant.parse(m.get("from").toString())),
                        Micros.of(Instant.parse(m.get("to").toString())),
                        ((Number) m.get("limitKw")).doubleValue(),
                        String.valueOf(m.getOrDefault("reason", ""))));
            }
        }
    }

    @Override
    public String type() {
        return "operating-rules";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of();
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("limit_kw", "kW"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("active", active);
        s.put("restricted_h", restrictedHours);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        active = s.get("active");
        restrictedHours = s.get("restricted_h");
    }

    @Override
    public void step(StepContext ctx) {
        double limit = defaultKw;
        String reason = null;
        for (Window w : windows) {
            if (ctx.tMicros() >= w.from() && ctx.tMicros() < w.to() && (Double.isNaN(limit) || w.limitKw() < limit)) {
                limit = w.limitKw();
                reason = w.reason();
            }
        }
        boolean now = reason != null;
        if (now && active == 0) {
            ctx.event("restriction_start", Map.of("limit_kw", Electrolyzer.fmt(limit), "reason", reason));
        } else if (!now && active == 1) {
            ctx.event("restriction_end", Map.of());
        }
        active = now ? 1 : 0;
        if (now) {
            restrictedHours += ctx.dtHours();
        }
        ctx.out("limit_kw", limit, Origin.REPORTED);
    }
}
