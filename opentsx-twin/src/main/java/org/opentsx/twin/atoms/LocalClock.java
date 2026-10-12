package org.opentsx.twin.atoms;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Local time for household schedules: hour of day, weekday/weekend and day of year in a time zone (default
 * {@code Europe/Berlin}), plus hour ranges written as {@code "18-23"} (from 18:00 to 23:00, end exclusive).
 */
public final class LocalClock {

    private final ZoneId zone;

    public LocalClock(String zone) {
        this.zone = ZoneId.of(zone == null ? "Europe/Berlin" : zone);
    }

    public LocalDateTime at(long tMicros) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(Math.floorDiv(tMicros, 1_000_000L)), zone);
    }

    /** Hour of day as a fraction, e.g. 18.5 for 18:30. */
    public double hour(long tMicros) {
        LocalDateTime t = at(tMicros);
        return t.getHour() + t.getMinute() / 60.0;
    }

    public boolean weekend(long tMicros) {
        DayOfWeek d = at(tMicros).getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public int dayOfYear(long tMicros) {
        return at(tMicros).getDayOfYear();
    }

    /** Parses hour ranges {@code "a-b"} into {@code [a0, b0, a1, b1, ...]}. */
    public static double[] parse(List<String> ranges) {
        if (ranges == null) {
            return new double[0];
        }
        double[] out = new double[2 * ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            String[] ab = ranges.get(i).split("-");
            if (ab.length != 2) {
                throw new IllegalArgumentException("hour range must look like '18-23': " + ranges.get(i));
            }
            out[2 * i] = Double.parseDouble(ab[0].trim());
            out[2 * i + 1] = Double.parseDouble(ab[1].trim());
            if (out[2 * i] >= out[2 * i + 1]) {
                throw new IllegalArgumentException("hour range must look like '18-23': " + ranges.get(i));
            }
        }
        return out;
    }

    /** True if {@code hour} lies in one of the parsed ranges (a inclusive, b exclusive; b may be 24). */
    public static boolean in(double[] ranges, double hour) {
        for (int i = 0; i < ranges.length; i += 2) {
            if (hour >= ranges[i] && hour < ranges[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** Length of parsed ranges in hours. */
    public static double hours(double[] ranges) {
        double h = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            h += ranges[i + 1] - ranges[i];
        }
        return h;
    }
}
