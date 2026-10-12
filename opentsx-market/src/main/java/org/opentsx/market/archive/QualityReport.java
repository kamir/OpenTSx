package org.opentsx.market.archive;

import org.opentsx.market.MarketSeries;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;
import org.opentsx.stats.StatsAccumulator;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Data-quality report of one archive package (one series, one year):
 * <ul>
 *   <li><b>gaps</b>: intervals of the window not covered by any point (leading, inner, trailing; the first
 *       {@value #MAX_GAPS} are listed);</li>
 *   <li><b>DST</b>: every local day (Europe/Berlin) must be covered for its real length; the two switch days of the
 *       year (23 h and 25 h) are listed with the hours found, and days whose coverage differs are counted;</li>
 *   <li><b>outliers</b>: values outside the plausible range of the metric, and robust outliers
 *       ({@code |v − median| > 8 · 1.4826 · MAD}); the robust check is skipped for zero-inflated series (more than
 *       20 % zeros, e.g. irradiance and solar power at night), where the MAD says nothing ({@code robustOutliers = -1});</li>
 *   <li><b>statistics</b> from {@code opentsx-stats} ({@link StatsAccumulator}: min, max, mean, std, p01, p50, p99)
 *       and the resolutions found (the day-ahead price changes from PT1H to PT15M on 2025-10-01).</li>
 * </ul>
 */
public record QualityReport(long rows, double coveragePct, long missingMicros, int gapCount, List<Gap> gaps,
                            Map<String, Long> resolutions, List<DstDay> dstDays, int incompleteDays,
                            int implausible, int robustOutliers, List<String> outlierExamples,
                            Map<String, Double> stats) {

    public static final int MAX_GAPS = 50;

    /** A gap {@code [from, to)} in the coverage. */
    public record Gap(String from, String to, double hours) {
    }

    /** A DST switch day: local date, its length and the hours covered. */
    public record DstDay(String date, double expectedHours, double coveredHours) {
    }

    /** Plausible ranges by metric prefix (harmonised SDAC price limits -500..4000 EUR/MWh; powers not negative). */
    static final Map<String, double[]> PLAUSIBLE = new LinkedHashMap<>();

    static {
        PLAUSIBLE.put("market.price.day_ahead", new double[]{-500, 4000});
        PLAUSIBLE.put("market.price.imbalance", new double[]{-20_000, 20_000});
        PLAUSIBLE.put("market.value", new double[]{-500, 4000});
        PLAUSIBLE.put("grid.", new double[]{-1, 200_000});
        PLAUSIBLE.put("weather.air_temperature", new double[]{-45, 45});
        PLAUSIBLE.put("weather.wind_speed", new double[]{0, 70});
        PLAUSIBLE.put("weather.global_radiation", new double[]{0, 1400});
    }

    public boolean dstOk() {
        return dstDays.stream().allMatch(d -> d.expectedHours == d.coveredHours);
    }

    public static QualityReport of(MarketSeries s, long from, long to) {
        List<Point> pts = s.slice(from, to).points();
        List<Gap> gaps = new ArrayList<>();
        long missing = 0;
        long cursor = from;
        Map<String, Long> res = new TreeMap<>();
        StatsAccumulator acc = new StatsAccumulator(0.005);
        for (Point p : pts) {
            if (p.tMicros() > cursor) {
                gaps.add(gap(cursor, p.tMicros()));
                missing += p.tMicros() - cursor;
            }
            cursor = Math.max(cursor, Math.min(p.endMicros(), to));
            res.merge(Duration.ofNanos(p.dtMicros() * 1000).toString(), 1L, Long::sum);
            acc.add(p.tMicros(), p.value());
        }
        if (cursor < to) {
            gaps.add(gap(cursor, to));
            missing += to - cursor;
        }

        List<DstDay> dst = new ArrayList<>();
        int incomplete = 0;
        boolean daily = pts.stream().allMatch(p -> p.dtMicros() < Micro.DAY);
        if (daily && !pts.isEmpty()) {
            Map<LocalDate, Long> covered = new TreeMap<>();
            for (Point p : pts) {
                LocalDate d = Micro.instant(p.tMicros()).atZone(Micro.BERLIN).toLocalDate();
                covered.merge(d, p.dtMicros(), Long::sum);
            }
            LocalDate d = Micro.instant(from).atZone(Micro.BERLIN).toLocalDate();
            LocalDate last = Micro.instant(to - 1).atZone(Micro.BERLIN).toLocalDate();
            for (; !d.isAfter(last); d = d.plusDays(1)) {
                long a = Micro.berlinDay(d);
                long b = Micro.berlinDay(d.plusDays(1));
                if (a < from || b > to) {
                    continue;   // local day only partly inside the UTC window
                }
                long c = covered.getOrDefault(d, 0L);
                if (b - a != Micro.DAY) {
                    dst.add(new DstDay(d.toString(), (b - a) / (double) Micro.HOUR, c / (double) Micro.HOUR));
                }
                if (c != b - a) {
                    incomplete++;
                }
            }
        }

        double[] range = PLAUSIBLE.entrySet().stream().filter(e -> s.def().metric().startsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY});
        double[] v = pts.stream().mapToDouble(Point::value).toArray();
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        double median = sorted.length == 0 ? Double.NaN : sorted[sorted.length / 2];
        double[] dev = Arrays.stream(v).map(x -> Math.abs(x - median)).sorted().toArray();
        double mad = dev.length == 0 ? 0 : dev[dev.length / 2];
        long zeros = Arrays.stream(v).filter(x -> x == 0).count();
        boolean robustCheck = mad > 0 && zeros <= 0.2 * v.length;
        int implausible = 0;
        int robust = robustCheck ? 0 : -1;
        List<String> examples = new ArrayList<>();
        for (Point p : pts) {
            boolean bad = p.value() < range[0] || p.value() > range[1];
            boolean far = robustCheck && Math.abs(p.value() - median) > 8 * 1.4826 * mad;
            implausible += bad ? 1 : 0;
            robust += far ? 1 : 0;
            if ((bad || far) && examples.size() < 5) {
                examples.add(Micro.instant(p.tMicros()) + "=" + p.value() + (bad ? " (implausible)" : ""));
            }
        }

        Map<String, Double> stats = new LinkedHashMap<>();
        if (!acc.isEmpty()) {
            stats.put("min", acc.min());
            stats.put("max", acc.max());
            stats.put("mean", round(acc.mean()));
            stats.put("std", round(acc.stddev()));
            stats.put("p01", round(acc.sketch().quantile(0.01)));
            stats.put("p50", round(acc.sketch().quantile(0.5)));
            stats.put("p99", round(acc.sketch().quantile(0.99)));
        }
        double coverage = to > from ? 100.0 * (to - from - missing) / (to - from) : 100;
        return new QualityReport(pts.size(), round(coverage), missing, gaps.size(),
                List.copyOf(gaps.subList(0, Math.min(gaps.size(), MAX_GAPS))), res, dst, incomplete, implausible, robust,
                examples, stats);
    }

    private static Gap gap(long a, long b) {
        return new Gap(Micro.instant(a).toString(), Micro.instant(b).toString(), round((b - a) / (double) Micro.HOUR));
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    /** One line for the console. */
    public String summary() {
        return String.format(java.util.Locale.ROOT, "%d rows, coverage %.2f %%, %d gaps (%.1f h), resolutions %s, "
                        + "DST %s, incomplete days %d, implausible %d, robust outliers %d", rows, coveragePct, gapCount,
                missingMicros / (double) Micro.HOUR, resolutions, dstDays.isEmpty() ? "n/a" : (dstOk() ? "ok" : "NOT ok "
                        + dstDays), incompleteDays, implausible, robustOutliers);
    }
}
