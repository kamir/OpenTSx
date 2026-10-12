package org.opentsx.model;

import java.time.Instant;

/** Conversions between {@link Instant} and epoch microseconds (the model's time unit). */
public final class Micros {

    private Micros() {
    }

    public static long of(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }

    public static Instant toInstant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
    }

    public static long now() {
        return of(Instant.now());
    }
}
