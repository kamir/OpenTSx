package org.opentsx.flink.stats;

import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsEngine;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The settings of the {@link StatsEngine} of a job. Unlike {@link StatsEngine.Config} it is serializable, so every
 * task and the state serializer can build the same engine.
 */
public record EngineSpec(List<Duration> windows, Duration lateness, double alpha) implements Serializable {

    public EngineSpec {
        windows = List.copyOf(windows);
        Objects.requireNonNull(lateness);
        new StatsEngine(config(windows, lateness, alpha));
    }

    /** PT10M and PT1H windows, no lateness, sketches with 1 % relative accuracy, as in the KafScale processor. */
    public static EngineSpec defaults() {
        return new EngineSpec(List.of(Duration.ofMinutes(10), Duration.ofHours(1)), Duration.ZERO,
                QuantileSketch.DEFAULT_ALPHA);
    }

    public StatsEngine.Config config() {
        return config(windows, lateness, alpha);
    }

    public StatsEngine newEngine() {
        return new StatsEngine(config());
    }

    private static StatsEngine.Config config(List<Duration> windows, Duration lateness, double alpha) {
        return StatsEngine.Config.defaults()
                .withWindows(windows.toArray(Duration[]::new))
                .withAllowedLateness(lateness)
                .withSketchAlpha(alpha);
    }
}
