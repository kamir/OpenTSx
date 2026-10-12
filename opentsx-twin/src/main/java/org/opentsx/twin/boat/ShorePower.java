package org.opentsx.twin.boat;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shore power (230 V) through the AC charger of the power station. Offers {@code chargerW · efficiency} on the DC side
 * while connected; how much is drawn decides the power station.
 * <p>
 * Connected if the bound input {@code available} is 1 (logbook or measurement), or, if none is bound, while the boat
 * is in a mode listed in {@code modes} (default harbour and away). Controls: {@code enabled} (0 = never plug in) and
 * {@code every_n_days} (plug in only on local days with {@code dayIndex mod n = 0}, counted from {@code dayZero}).
 */
public final class ShorePower extends Atom {

    private final double chargerW;
    private final double efficiency;
    private final double utcOffsetH;
    private final long dayZero;
    private final boolean[] modes = new boolean[BoatClock.MODES.size()];
    private double connected = -1;
    private double connectedH;

    public ShorePower(String id, Params params) {
        super(id, params);
        chargerW = params.d("chargerW", 400);
        efficiency = params.d("efficiency", 0.9);
        utcOffsetH = params.d("utcOffsetH", 2);
        dayZero = BoatClock.epochDay(params);
        for (String m : params.list("modes", List.of("harbour", "away"))) {
            int i = BoatClock.mode(m);
            if (i < 0) {
                throw new IllegalArgumentException("unknown mode '" + m + "' in shore-power modes");
            }
            modes[i] = true;
        }
    }

    @Override
    public String type() {
        return "shore-power";
    }

    @Override
    public Phase phase() {
        return Phase.ASSETS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("mode", "1"), PortSpec.signal("available", "1"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_avail_w", "W"), PortSpec.signal("connected", "1"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("enabled", 1.0);
        c.put("every_n_days", 1.0);
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("connected", connected);
        s.put("connected_h", connectedH);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        connected = s.get("connected");
        connectedH = s.get("connected_h");
    }

    @Override
    public void step(StepContext ctx) {
        boolean avail;
        if (ctx.has("available")) {
            avail = ctx.in("available").getAsDouble() > 0.5;
        } else {
            avail = modes[BoatClock.modeOf(ctx.inOr("mode", BoatClock.HARBOUR))];
        }
        long n = Math.max(1, Math.round(ctx.control("every_n_days")));
        long day = BoatClock.dayIndex(ctx.tMicros(), utcOffsetH, dayZero);
        boolean on = avail && ctx.control("enabled") > 0.5 && Math.floorMod(day, n) == 0;
        double now = on ? 1 : 0;
        if (connected >= 0 && now != connected) {
            ctx.event(on ? "shore_connected" : "shore_disconnected", Map.of());
        }
        connected = now;
        if (on) {
            connectedH += ctx.dtHours();
        }
        ctx.out("p_avail_w", on ? chargerW * efficiency : 0);
        ctx.out("connected", now);
    }
}
