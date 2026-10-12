package org.opentsx.twin.boat;

import org.opentsx.twin.Params;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers of the boat atoms: local time of day, local day index, operating modes and hour windows.
 * <p>
 * Modes (signal {@code mode}, unit {@code 1}): {@code 0 = harbour}, {@code 1 = sailing}, {@code 2 = anchor},
 * {@code 3 = away} (boat in its berth, nobody on board).
 */
public final class BoatClock {

    public static final long HOUR = 3_600_000_000L;
    public static final long DAY = 24 * HOUR;
    public static final List<String> MODES = List.of("harbour", "sailing", "anchor", "away");
    public static final int HARBOUR = 0;
    public static final int SAILING = 1;
    public static final int ANCHOR = 2;
    public static final int AWAY = 3;

    private BoatClock() {
    }

    /** Local hour of day in {@code [0, 24)} for a fixed UTC offset (CEST = 2). */
    public static double localHour(long tMicros, double utcOffsetH) {
        long local = tMicros + Math.round(utcOffsetH * HOUR);
        return Math.floorMod(local, DAY) / (double) HOUR;
    }

    /** Local day number since 1970-01-01. */
    public static long localEpochDay(long tMicros, double utcOffsetH) {
        return Math.floorDiv(tMicros + Math.round(utcOffsetH * HOUR), DAY);
    }

    /** Day index relative to {@code dayZero} (ISO date, local), e.g. 0 on the first day of a trip. */
    public static long dayIndex(long tMicros, double utcOffsetH, long dayZeroEpochDay) {
        return localEpochDay(tMicros, utcOffsetH) - dayZeroEpochDay;
    }

    static long epochDay(Params p) {
        String z = p.s("dayZero", null);
        return z == null ? 0 : LocalDate.parse(z).toEpochDay();
    }

    /** Mode number from a name or number ("harbour", "hafen", "1", ...); -1 if unknown. */
    public static int mode(String s) {
        if (s == null) {
            return -1;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "harbour", "harbor", "hafen", "h", "0":
                return HARBOUR;
            case "sailing", "segeln", "unterwegs", "motor", "s", "1":
                return SAILING;
            case "anchor", "anker", "ankern", "a", "2":
                return ANCHOR;
            case "away", "unbemannt", "abwesend", "u", "3":
                return AWAY;
            default:
                return -1;
        }
    }

    /** A local hour window {@code [from, to)}; wraps over midnight if {@code to < from}. */
    public record Window(double from, double to) {

        /** Parses {@code "07-23"}, {@code "7.5-9"}, {@code "22-02"}. */
        public static Window parse(String s) {
            String[] p = s.trim().split("-");
            if (p.length != 2) {
                throw new IllegalArgumentException("window must look like 'HH-HH': " + s);
            }
            return new Window(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()));
        }

        public boolean contains(double hour) {
            return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
        }
    }

    /** Parameter {@code windows}: map mode name → list of windows. Missing modes have no window. */
    @SuppressWarnings("unchecked")
    static List<List<Window>> windows(Params p, String name) {
        List<List<Window>> out = new ArrayList<>();
        Object o = p.asMap().get(name);
        Map<String, Object> m = o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
        for (String k : m.keySet()) {
            if (mode(k) < 0) {
                throw new IllegalArgumentException("unknown mode '" + k + "' in " + name + ", known: " + MODES);
            }
        }
        for (String mode : MODES) {
            List<Window> ws = new ArrayList<>();
            Object l = m.get(mode);
            if (l instanceof List<?> list) {
                list.forEach(x -> ws.add(Window.parse(x.toString())));
            } else if (l != null) {
                ws.add(Window.parse(l.toString()));
            }
            out.add(List.copyOf(ws));
        }
        return out;
    }

    static boolean inAny(List<Window> ws, double hour) {
        for (Window w : ws) {
            if (w.contains(hour)) {
                return true;
            }
        }
        return false;
    }

    /** Clamps a mode value read from a port to a known mode (harbour for anything else). */
    static int modeOf(double v) {
        int m = (int) Math.round(v);
        return m >= 0 && m < MODES.size() ? m : HARBOUR;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
