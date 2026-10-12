package org.opentsx.twin.boat;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operating mode of a boat per superstep: harbour, sailing, anchor or away (see {@link BoatClock}). Devices and shore
 * power read it.
 * <p>
 * Sources, best first:
 * <ol>
 *   <li>control {@code force_mode} (an intervention, e.g. "stay in harbour"), when {@code >= 0};</li>
 *   <li>the bound input {@code mode_obs}, e.g. the logbook ({@link Origin#REPORTED});</li>
 *   <li>the planned itinerary (parameter, {@link Origin#SYNTHETIC}).</li>
 * </ol>
 * Parameter {@code itinerary}: one letter per local day from {@code dayZero}:
 * {@code H} harbour day, {@code S} sail and spend the night in a harbour, {@code A} sail and anchor for the night,
 * {@code X} stay at anchor, {@code U} away (nobody on board). Before the sailing window
 * ({@code sailFrom}–{@code sailTo}, local hours) the boat is where it spent the previous night.
 */
public final class BoatMode extends Atom {

    private final String itinerary;
    private final double sailFrom;
    private final double sailTo;
    private final double utcOffsetH;
    private final long dayZero;
    private int mode = -1;
    private final double[] hours = new double[BoatClock.MODES.size()];

    public BoatMode(String id, Params params) {
        super(id, params);
        itinerary = params.s("itinerary", "H").toUpperCase(java.util.Locale.ROOT);
        if (!itinerary.matches("[HSAXU]+")) {
            throw new IllegalArgumentException("itinerary may only contain H, S, A, X, U: " + itinerary);
        }
        sailFrom = params.d("sailFrom", 9);
        sailTo = params.d("sailTo", 17);
        utcOffsetH = params.d("utcOffsetH", 2);
        dayZero = BoatClock.epochDay(params);
    }

    @Override
    public String type() {
        return "boat-mode";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("mode_obs", "1"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("mode", "1"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("force_mode", -1.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("mode", (double) mode);
        for (int i = 0; i < hours.length; i++) {
            s.put(BoatClock.MODES.get(i) + "_h", hours[i]);
        }
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        mode = s.get("mode").intValue();
        for (int i = 0; i < hours.length; i++) {
            hours[i] = s.get(BoatClock.MODES.get(i) + "_h");
        }
    }

    @Override
    public void step(StepContext ctx) {
        double forced = ctx.control("force_mode");
        int m;
        Origin origin;
        if (!Double.isNaN(forced) && forced >= 0) {
            m = BoatClock.modeOf(forced);
            origin = Origin.REPORTED;
        } else if (ctx.has("mode_obs")) {
            m = BoatClock.modeOf(ctx.in("mode_obs").getAsDouble());
            origin = null;
        } else {
            long day = BoatClock.dayIndex(ctx.tMicros(), utcOffsetH, dayZero);
            m = modeAt(itinerary, day, BoatClock.localHour(ctx.tMicros(), utcOffsetH), sailFrom, sailTo);
            origin = Origin.SYNTHETIC;
        }
        if (m != mode && mode >= 0) {
            ctx.event("mode_change", Map.of("from", BoatClock.MODES.get(mode), "to", BoatClock.MODES.get(m)));
        }
        mode = m;
        hours[m] += ctx.dtHours();
        if (origin == null) {
            ctx.out("mode", m);
        } else {
            ctx.out("mode", m, origin);
        }
    }

    /** Mode of an itinerary on day {@code day} (0 = first letter) at local hour {@code hour}. */
    public static int modeAt(String itinerary, long day, double hour, double sailFrom, double sailTo) {
        if (day < 0) {
            return BoatClock.HARBOUR;
        }
        if (day >= itinerary.length()) {
            return evening(itinerary, itinerary.length() - 1);
        }
        char c = itinerary.charAt((int) day);
        switch (c) {
            case 'H':
                return BoatClock.HARBOUR;
            case 'X':
                return BoatClock.ANCHOR;
            case 'U':
                return BoatClock.AWAY;
            default:
                if (hour < sailFrom) {
                    return day == 0 ? BoatClock.HARBOUR : evening(itinerary, day - 1);
                }
                if (hour < sailTo) {
                    return BoatClock.SAILING;
                }
                return c == 'A' ? BoatClock.ANCHOR : BoatClock.HARBOUR;
        }
    }

    private static int evening(String itinerary, long day) {
        return switch (itinerary.charAt((int) day)) {
            case 'A', 'X' -> BoatClock.ANCHOR;
            case 'U' -> BoatClock.AWAY;
            default -> BoatClock.HARBOUR;
        };
    }
}
