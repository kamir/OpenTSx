package org.opentsx.stats;

import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Data-quality checks evaluated on {@link SeriesStats} - no access to the raw values is needed, so the checks run
 * wherever the statistics are (stream, Iceberg, OpenMetadata publisher).
 *
 * <table>
 *   <caption>Checks</caption>
 *   <tr><th>Check</th><th>Value</th><th>Fails when</th></tr>
 *   <tr><td>COMPLETENESS</td><td>validCount / expected samples in the window</td><td>below {@code minCompleteness}</td></tr>
 *   <tr><td>PLAUSIBLE_RANGE</td><td>share of values outside [min, max] (from the quantile sketch)</td>
 *       <td>above {@code maxOutOfRange}</td></tr>
 *   <tr><td>FLATLINE</td><td>standard deviation</td><td>at most {@code flatlineEpsilon} over at least
 *       {@code flatlineMinCount} values (stuck sensor)</td></tr>
 *   <tr><td>GAPS</td><td>gapCount</td><td>above {@code maxGaps}</td></tr>
 *   <tr><td>FRESHNESS</td><td>age of lastSeen in seconds</td><td>older than {@code maxAge} (only with a clock)</td></tr>
 * </table>
 */
public final class DataQuality {

    public enum Check { COMPLETENESS, PLAUSIBLE_RANGE, FLATLINE, GAPS, FRESHNESS }

    public enum Status { SUCCESS, FAILED, ABORTED }

    /** What is expected of a series. Null / zero fields disable the corresponding check. */
    public record Expectation(Double minPlausible, Double maxPlausible, long expectedIntervalMicros,
                              double minCompleteness, double maxOutOfRange, boolean flatlineCheck,
                              long flatlineMinCount, double flatlineEpsilon, long maxGaps, Duration maxAge) {

        public static Expectation of(Double min, Double max, Duration expectedInterval) {
            return new Expectation(min, max, expectedInterval == null ? 0 : expectedInterval.toNanos() / 1000,
                    0.95, 0.001, true, 60, 1e-9, 0, null);
        }

        public Expectation withoutFlatline() {
            return new Expectation(minPlausible, maxPlausible, expectedIntervalMicros, minCompleteness, maxOutOfRange,
                    false, flatlineMinCount, flatlineEpsilon, maxGaps, maxAge);
        }

        public Expectation withMaxAge(Duration age) {
            return new Expectation(minPlausible, maxPlausible, expectedIntervalMicros, minCompleteness, maxOutOfRange,
                    flatlineCheck, flatlineMinCount, flatlineEpsilon, maxGaps, age);
        }
    }

    /** Result of one check on one statistics record. */
    public record Result(String seriesId, String windowType, long windowStartMicros, long windowEndMicros,
                         Check check, Status status, double value, String threshold, String message) {

        public boolean failed() {
            return status == Status.FAILED;
        }
    }

    private DataQuality() {
    }

    public static List<Result> evaluate(SeriesStats s, Expectation e) {
        return evaluate(s, e, OptionalLong.empty());
    }

    public static List<Result> evaluate(SeriesStats s, Expectation e, OptionalLong nowMicros) {
        List<Result> out = new ArrayList<>();
        long start = Micros.of(s.getWindowStart());
        long end = Micros.of(s.getWindowEnd());

        if (e.expectedIntervalMicros() > 0 && end > start) {
            double expected = Math.floor((double) (end - start) / e.expectedIntervalMicros());
            double ratio = expected <= 0 ? 1.0 : Math.min(1.0, s.getValidCount() / expected);
            out.add(result(s, Check.COMPLETENESS, ratio >= e.minCompleteness(), ratio, ">= " + e.minCompleteness(),
                    String.format("%d of %.0f expected values valid (%d NaN)", s.getValidCount(), expected, s.getNanCount())));
        }

        if ((e.minPlausible() != null || e.maxPlausible() != null) && s.getValidCount() > 0) {
            double outside;
            String how;
            if (s.getQuantileSketch() != null) {
                QuantileSketch q = QuantileSketch.fromBytes(s.getQuantileSketch());
                outside = (e.minPlausible() == null ? 0 : q.fractionBelow(e.minPlausible()))
                        + (e.maxPlausible() == null ? 0 : q.fractionAbove(e.maxPlausible()));
                how = "sketch";
            } else {
                boolean any = (e.minPlausible() != null && s.getMin() < e.minPlausible())
                        || (e.maxPlausible() != null && s.getMax() > e.maxPlausible());
                outside = any ? 1.0 : 0.0;
                how = "min/max only";
            }
            out.add(result(s, Check.PLAUSIBLE_RANGE, outside <= e.maxOutOfRange(), outside, "<= " + e.maxOutOfRange(),
                    String.format("range [%s, %s], observed [%.4g, %.4g], share outside %.4f (%s)",
                            e.minPlausible(), e.maxPlausible(), s.getMin(), s.getMax(), outside, how)));
        }

        if (e.flatlineCheck() && s.getValidCount() >= e.flatlineMinCount()) {
            double sd = s.getValidCount() < 2 ? Double.NaN : Math.sqrt(s.getM2() / (s.getValidCount() - 1));
            boolean flat = sd <= e.flatlineEpsilon();
            out.add(result(s, Check.FLATLINE, !flat, sd, "> " + e.flatlineEpsilon(),
                    flat ? String.format("constant value %.6g over %d values", s.getMean(), s.getValidCount())
                         : "values vary"));
        }

        if (e.expectedIntervalMicros() > 0 || s.getGapCount() > 0) {
            out.add(result(s, Check.GAPS, s.getGapCount() <= e.maxGaps(), s.getGapCount(), "<= " + e.maxGaps(),
                    s.getGapCount() + " gaps"));
        }

        if (e.maxAge() != null && nowMicros.isPresent()) {
            if (s.getLastSeen() == null) {
                out.add(new Result(s.getSeriesId(), s.getWindowType(), start, end, Check.FRESHNESS, Status.ABORTED,
                        Double.NaN, "<= " + e.maxAge(), "never seen"));
            } else {
                double ageSeconds = (nowMicros.getAsLong() - Micros.of(s.getLastSeen())) / 1e6;
                out.add(result(s, Check.FRESHNESS, ageSeconds <= e.maxAge().toNanos() / 1e9, ageSeconds,
                        "<= " + e.maxAge(), String.format("last value %.0f s ago", ageSeconds)));
            }
        }
        return out;
    }

    private static Result result(SeriesStats s, Check check, boolean ok, double value, String threshold, String message) {
        return new Result(s.getSeriesId(), s.getWindowType(), Micros.of(s.getWindowStart()), Micros.of(s.getWindowEnd()),
                check, ok ? Status.SUCCESS : Status.FAILED, value, threshold, message);
    }
}
