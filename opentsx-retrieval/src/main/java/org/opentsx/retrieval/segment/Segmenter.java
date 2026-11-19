package org.opentsx.retrieval.segment;

import org.opentsx.model.v2.SegmentationStrategy;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * How a series is cut into episodes. The four strategies of the model ({@code Segmentation.strategy}):
 *
 * <pre>
 * Segmenter.fixedDuration(Duration.ofMinutes(10))                          // aligned 10-minute blocks
 * Segmenter.fixedCount(4096, 2048)                                         // FFT blocks, 50 % overlap
 * Segmenter.extremum(Feature.MAX, 3.0, PT10M, PT5M, PT2M, PT3M)            // gusts: peak +2/-3 min
 * Segmenter.threshold(Direction.ABOVE, 20, 18, PT1M, PT1M, PT30S, PT2H)    // storm phases with hysteresis
 * </pre>
 * The parameters are recorded in {@code Segmentation.params} (ISO-8601 durations, plain numbers).
 */
public final class Segmenter {

    public enum Feature { MAX, MIN }

    public enum Direction { ABOVE, BELOW }

    /** The longest duration the time axis of microseconds can hold. */
    private static final Duration MAX_DURATION = Duration.of(Long.MAX_VALUE, java.time.temporal.ChronoUnit.MICROS);

    private final SegmentationStrategy strategy;
    private final Map<String, String> params;
    private final Supplier<Detector> detector;

    private Segmenter(SegmentationStrategy strategy, Map<String, String> params, Supplier<Detector> detector) {
        this.strategy = strategy;
        this.params = Collections.unmodifiableMap(new TreeMap<>(params));
        this.detector = detector;
    }

    public static Segmenter fixedDuration(Duration duration) {
        return fixedDuration(duration, Duration.ZERO);
    }

    /** Windows aligned to the epoch shifted by {@code offset}. */
    public static Segmenter fixedDuration(Duration duration, Duration offset) {
        // the time axis counts microseconds, so a shorter duration would make every window zero long
        long d = duration.isNegative() ? 0 : micros("params.duration", duration);
        if (d <= 0) {
            throw new IllegalArgumentException("params.duration: " + duration + " is out of range; expected an ISO-8601 duration of at"
                    + " least PT0.000001S, such as PT10M");
        }
        long o = micros("params.offset", offset);
        return new Segmenter(SegmentationStrategy.FIXED_DURATION, Map.of("duration", duration.toString(), "offset",
                offset.toString()), () -> new FixedDurationDetector(d, o));
    }

    /** Blocks of {@code n} samples; consecutive blocks share {@code overlap} samples. */
    public static Segmenter fixedCount(int n, int overlap) {
        if (n < 1) {
            throw new IllegalArgumentException("params.n: " + n + " is out of range; expected a whole number from 1 to " + Integer.MAX_VALUE);
        }
        if (overlap < 0 || overlap >= n) {
            throw new IllegalArgumentException("params.overlap: " + overlap + " is out of range; expected a whole number from 0 to "
                    + (n - 1) + ", below params.n");
        }
        return new Segmenter(SegmentationStrategy.FIXED_COUNT, Map.of("n", Integer.toString(n), "overlap",
                Integer.toString(overlap)), () -> new FixedCountDetector(n, overlap));
    }

    /**
     * Windows around local extrema.
     *
     * @param prominence minimum prominence (height above the higher of the two surrounding minima within ±window/2)
     * @param window     neighbourhood for the prominence
     * @param distance   the peak must be the extreme value within ±distance
     */
    public static Segmenter extremum(Feature feature, double prominence, Duration window, Duration distance,
                                     Duration pre, Duration post) {
        if (prominence < 0) {
            throw new IllegalArgumentException("params.prominence: " + prominence + " is below zero; expected a number of 0 or more");
        }
        Map<String, String> p = Map.of("feature", feature.name(), "prominence", Double.toString(prominence),
                "window", window.toString(), "distance", distance.toString(), "pre", pre.toString(), "post", post.toString());
        long w = micros("params.window", window);
        long d = micros("params.distance", distance);
        long a = micros("params.pre", pre);
        long b = micros("params.post", post);
        return new Segmenter(SegmentationStrategy.FEATURE_EXTREMUM, p,
                () -> new ExtremumDetector(feature == Feature.MAX, prominence, w, d, a, b));
    }

    /**
     * Threshold-to-threshold segments with hysteresis.
     *
     * @param enter       value that starts a phase (reached or exceeded in the given direction)
     * @param exit        value that ends it (for ABOVE: falling below exit; exit <= enter)
     * @param minDuration shorter phases are ignored
     * @param maxDuration longer phases are cut (Duration.ZERO = unlimited)
     */
    public static Segmenter threshold(Direction direction, double enter, double exit, Duration pre, Duration post,
                                      Duration minDuration, Duration maxDuration) {
        if (direction == Direction.ABOVE ? exit > enter : exit < enter) {
            throw new IllegalArgumentException("params.exit: " + exit + " is " + (direction == Direction.ABOVE ? "above" : "below")
                    + " params.enter " + enter + "; expected a number of " + (direction == Direction.ABOVE ? "at most " : "at least ")
                    + enter + " for direction " + direction);
        }
        Map<String, String> p = Map.of("direction", direction.name(), "enter", Double.toString(enter), "exit",
                Double.toString(exit), "pre", pre.toString(), "post", post.toString(), "minDuration",
                minDuration.toString(), "maxDuration", maxDuration.toString());
        long a = micros("params.pre", pre);
        long b = micros("params.post", post);
        long mn = micros("params.minDuration", minDuration);
        long mx = micros("params.maxDuration", maxDuration);
        return new Segmenter(SegmentationStrategy.FEATURE_THRESHOLD, p,
                () -> new ThresholdDetector(direction == Direction.ABOVE, enter, exit, a, b, mn, mx));
    }

    public SegmentationStrategy strategy() {
        return strategy;
    }

    /** Parameters, sorted by name. */
    public Map<String, String> params() {
        return params;
    }

    Detector newDetector() {
        return detector.get();
    }

    private static long micros(String field, Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException(field + ": " + d + " is negative; expected an ISO-8601 duration of zero or more, such as PT1M");
        }
        try {
            return Math.addExact(Math.multiplyExact(d.getSeconds(), 1_000_000L), d.getNano() / 1000);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + ": " + d + " is out of range; expected an ISO-8601 duration of at most "
                    + MAX_DURATION + ", such as PT1M", e);
        }
    }

    @Override
    public String toString() {
        return strategy + params.toString();
    }
}
