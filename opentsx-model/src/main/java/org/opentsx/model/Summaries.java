package org.opentsx.model;

import org.opentsx.model.v2.EpisodeSummary;

/** Welford summaries of finite values; NaN is counted separately. Summaries merge exactly (Chan et al.). */
public final class Summaries {

    private Summaries() {
    }

    public static EpisodeSummary of(double[] values) {
        long n = 0;
        long nan = 0;
        double mean = 0;
        double m2 = 0;
        double min = Double.NaN;
        double max = Double.NaN;
        for (double v : values) {
            if (Double.isNaN(v)) {
                nan++;
                continue;
            }
            n++;
            double delta = v - mean;
            mean += delta / n;
            m2 += delta * (v - mean);
            min = n == 1 ? v : Math.min(min, v);
            max = n == 1 ? v : Math.max(max, v);
        }
        return build(n, nan, min, max, n == 0 ? Double.NaN : mean, n == 0 ? Double.NaN : m2);
    }

    public static EpisodeSummary merge(EpisodeSummary a, EpisodeSummary b) {
        long na = a.getValidCount();
        long nb = b.getValidCount();
        long nan = a.getNanCount() + b.getNanCount();
        if (na == 0) {
            return build(nb, nan, b.getMin(), b.getMax(), b.getMean(), b.getM2());
        }
        if (nb == 0) {
            return build(na, nan, a.getMin(), a.getMax(), a.getMean(), a.getM2());
        }
        long n = na + nb;
        double delta = b.getMean() - a.getMean();
        double mean = a.getMean() + delta * nb / n;
        double m2 = a.getM2() + b.getM2() + delta * delta * ((double) na * nb / n);
        return build(n, nan, Math.min(a.getMin(), b.getMin()), Math.max(a.getMax(), b.getMax()), mean, m2);
    }

    /** Sample variance, NaN for fewer than two finite values. */
    public static double variance(EpisodeSummary s) {
        return s.getValidCount() < 2 ? Double.NaN : s.getM2() / (s.getValidCount() - 1);
    }

    private static EpisodeSummary build(long n, long nan, double min, double max, double mean, double m2) {
        return EpisodeSummary.newBuilder()
                .setValidCount(n).setNanCount(nan)
                .setMin(min).setMax(max).setMean(mean).setM2(m2)
                .setSax(null)
                .build();
    }
}
