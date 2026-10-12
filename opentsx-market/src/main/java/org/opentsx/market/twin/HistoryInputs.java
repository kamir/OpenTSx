package org.opentsx.market.twin;

import org.opentsx.market.MarketCatalog;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.Point;
import org.opentsx.market.SeriesDef;
import org.opentsx.market.archive.Archive;
import org.opentsx.twin.Origin;

import java.util.Arrays;
import java.util.List;

/**
 * Turns archive series into twin inputs: resampling to the plan's base step and the origin of the archive
 * ({@code MEASURED} stations, {@code REPORTED} market results, {@code FORECAST} forecasts).
 * <p>
 * Resampling is a time-weighted mean over each step: finer data (quarter-hours into hours) is averaged, coarser data
 * (hourly prices into quarter-hours, monthly values) is held. A step covered less than half by data becomes NaN, so
 * the twin falls back to its own default for that step (and marks it {@code SYNTHETIC}) instead of using a guess.
 */
public final class HistoryInputs {

    private final Archive archive;

    public HistoryInputs(Archive archive) {
        this.archive = archive;
    }

    public Archive archive() {
        return archive;
    }

    /** Series of the archive resampled to {@code n} steps of {@code step} from {@code start} (all UTC micros). */
    public double[] resampled(SeriesDef def, long start, long step, int n) {
        MarketSeries s = archive.series(def, start - step, start + n * step + step);
        return resample(s.points(), start, step, n);
    }

    /** Resampled series by archive path ({@code smard/day_ahead_price}). */
    public double[] resampled(String path, long start, long step, int n) {
        return resampled(MarketCatalog.require(path), start, step, n);
    }

    public static double[] resample(List<Point> points, long start, long step, int n) {
        double[] sum = new double[n];
        long[] covered = new long[n];
        for (Point p : points) {
            long a = Math.max(p.tMicros(), start);
            long b = Math.min(p.endMicros(), start + n * step);
            if (b <= a || Double.isNaN(p.value())) {
                continue;
            }
            for (long k = (a - start) / step; k < n && start + k * step < b; k++) {
                long lo = Math.max(a, start + k * step);
                long hi = Math.min(b, start + (k + 1) * step);
                if (hi > lo) {
                    sum[(int) k] += p.value() * (hi - lo);
                    covered[(int) k] += hi - lo;
                }
            }
        }
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            out[k] = covered[k] * 2 >= step ? sum[k] / covered[k] : Double.NaN;
        }
        return out;
    }

    /** Share of finite values. */
    public static double coverage(double[] v) {
        return v.length == 0 ? 0 : Arrays.stream(v).filter(Double::isFinite).count() / (double) v.length;
    }

    /** Twin origin of an archive series. */
    public static Origin origin(SeriesDef def) {
        return Origin.valueOf(def.origin());
    }
}
