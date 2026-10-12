package org.opentsx.bridge;

/** How the x-values of a legacy {@code TimeSeriesObject} map to time. */
public enum TimeUnitOfX {
    EPOCH_SECONDS(1_000_000L),
    EPOCH_MILLIS(1_000L),
    EPOCH_MICROS(1L);

    private final long micros;

    TimeUnitOfX(long micros) {
        this.micros = micros;
    }

    long toMicros(double x) {
        return Math.round(x * micros);
    }

    double fromMicros(long t) {
        return (double) t / micros;
    }
}
