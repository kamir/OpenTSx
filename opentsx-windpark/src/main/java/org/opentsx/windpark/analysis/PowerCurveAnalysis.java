package org.opentsx.windpark.analysis;

import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.sim.PowerCurve;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

/**
 * Power-curve analysis of one turbine, following the method of bins of IEC 61400-12-1 (simplified: nacelle
 * anemometer, no air-density or site calibration):
 * <ol>
 *   <li>wind speed and active power are averaged over aligned intervals (default 10 minutes); intervals with missing
 *       data on either side are dropped;</li>
 *   <li>the means are binned by wind speed (default 0.5 m/s); per bin: count, mean wind, mean power, standard
 *       deviation and the reference power;</li>
 *   <li>the <b>performance index</b> is produced energy / expected energy over all intervals in which the reference
 *       expects at least {@code minExpectedShare} of rated power;</li>
 *   <li><b>underperformance periods</b> are consecutive intervals with actual/expected below {@code ratioThreshold},
 *       with the energy lost against the reference.</li>
 * </ol>
 */
public final class PowerCurveAnalysis {

    /** One wind-speed bin. */
    public record Bin(double windFromMs, double windToMs, long count, double meanWindMs, double meanPowerKw,
                      double stdPowerKw, double referenceKw) {
    }

    /** Consecutive intervals below the ratio threshold. */
    public record Underperformance(long fromMicros, long toMicros, double meanRatio, double lostEnergyKwh) {
    }

    public record Result(List<Bin> bins, int intervals, double performanceIndex, double producedKwh, double expectedKwh,
                         List<Underperformance> underperformance) {
    }

    public record Settings(Duration interval, double binWidthMs, double ratioThreshold, double minExpectedShare) {

        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(10), 0.5, 0.8, 0.1);
        }
    }

    private PowerCurveAnalysis() {
    }

    /** With the idealised reference curve of the turbine type. */
    public static Result analyze(TurbineType type, Collection<Episode> wind, Collection<Episode> power, Settings s) {
        return analyze(v -> PowerCurve.powerKw(type, v), type.ratedPowerKw(), wind, power, s);
    }

    /** With any reference curve (e.g. the warranted curve of the manufacturer). */
    public static Result analyze(DoubleUnaryOperator referenceKw, double ratedKw, Collection<Episode> wind,
                                 Collection<Episode> power, Settings s) {
        long d = s.interval().toNanos() / 1000;
        TreeMap<Long, double[]> w = means(wind, d);
        TreeMap<Long, double[]> p = means(power, d);
        TreeMap<Integer, double[]> bins = new TreeMap<>();     // count, sumWind, sumPower, sumPower2
        double produced = 0;
        double expected = 0;
        double hours = s.interval().toNanos() / 3.6e12;
        List<Underperformance> periods = new ArrayList<>();
        long runFrom = -1;
        long runTo = -1;
        double runRatio = 0;
        int runN = 0;
        double runLost = 0;
        int intervals = 0;
        for (var e : w.entrySet()) {
            double[] pw = p.get(e.getKey());
            if (pw == null) {
                continue;
            }
            double ws = e.getValue()[0];
            double kw = pw[0];
            intervals++;
            int b = (int) Math.floor(ws / s.binWidthMs());
            double[] acc = bins.computeIfAbsent(b, k -> new double[4]);
            acc[0]++;
            acc[1] += ws;
            acc[2] += kw;
            acc[3] += kw * kw;
            double ref = referenceKw.applyAsDouble(ws);
            boolean relevant = ref >= s.minExpectedShare() * ratedKw;
            if (relevant) {
                produced += kw * hours;
                expected += ref * hours;
            }
            double ratio = relevant ? kw / ref : Double.NaN;
            boolean under = relevant && ratio < s.ratioThreshold();
            boolean contiguous = runTo == e.getKey();
            if (under && (runN == 0 || contiguous)) {
                if (runN == 0) {
                    runFrom = e.getKey();
                }
                runTo = e.getKey() + d;
                runRatio += ratio;
                runN++;
                runLost += (ref - kw) * hours;
            } else {
                if (runN > 0) {
                    periods.add(new Underperformance(runFrom, runTo, runRatio / runN, runLost));
                    runN = 0;
                    runRatio = 0;
                    runLost = 0;
                }
                if (under) {
                    runFrom = e.getKey();
                    runTo = e.getKey() + d;
                    runRatio = ratio;
                    runN = 1;
                    runLost = (ref - kw) * hours;
                }
            }
        }
        if (runN > 0) {
            periods.add(new Underperformance(runFrom, runTo, runRatio / runN, runLost));
        }
        List<Bin> out = new ArrayList<>();
        for (var b : bins.entrySet()) {
            double[] a = b.getValue();
            double n = a[0];
            double meanWind = a[1] / n;
            double meanPower = a[2] / n;
            double var = n > 1 ? Math.max(0, (a[3] - n * meanPower * meanPower) / (n - 1)) : Double.NaN;
            out.add(new Bin(b.getKey() * s.binWidthMs(), (b.getKey() + 1) * s.binWidthMs(), (long) n, meanWind,
                    meanPower, Math.sqrt(var), referenceKw.applyAsDouble(meanWind)));
        }
        return new Result(out, intervals, expected == 0 ? Double.NaN : produced / expected, produced, expected, periods);
    }

    /** Mean of finite values per aligned interval: interval start -> {mean, count}. */
    private static TreeMap<Long, double[]> means(Collection<Episode> episodes, long d) {
        TreeMap<Long, double[]> acc = new TreeMap<>();
        for (Episode e : episodes) {
            long[] ts = Episodes.timestampsMicros(e);
            double[] vs = Episodes.values(e);
            for (int i = 0; i < ts.length; i++) {
                if (Double.isNaN(vs[i])) {
                    continue;
                }
                double[] a = acc.computeIfAbsent(Math.floorDiv(ts[i], d) * d, k -> new double[2]);
                a[1]++;
                a[0] += (vs[i] - a[0]) / a[1];
            }
        }
        return acc;
    }
}
