package org.opentsx.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Time helpers: UTC micros, the market time zone and the 15-minute MTU switch. */
public final class Micro {

    public static final long SECOND = 1_000_000L;
    public static final long MINUTE = 60 * SECOND;
    public static final long QUARTER = 15 * MINUTE;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;
    /** Market time zone of DE-LU (delivery days, Marktwerte months). */
    public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /**
     * First delivery day with a 15-minute MTU in the SDAC day-ahead coupling: 2025-10-01 00:00 CEST =
     * 2025-09-30T22:00Z. Before, the day-ahead price DE-LU had hourly products.
     */
    public static final Instant MTU_15_MIN = Instant.parse("2025-09-30T22:00:00Z");

    private Micro() {
    }

    public static long of(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), SECOND), t.getNano() / 1000);
    }

    public static Instant instant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, SECOND), Math.floorMod(micros, SECOND) * 1000);
    }

    /** Start of a UTC year. */
    public static long utcYear(int year) {
        return of(LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** UTC year of an instant. */
    public static int yearOf(long micros) {
        return instant(micros).atZone(ZoneOffset.UTC).getYear();
    }

    /** Start of a local day in Europe/Berlin. */
    public static long berlinDay(LocalDate d) {
        return of(d.atStartOfDay(BERLIN).toInstant());
    }
}
