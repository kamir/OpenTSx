package org.opentsx.retrieval.pattern;

/** Piecewise Aggregate Approximation with fractional segment boundaries (any n, any w <= n); NaN values are skipped. */
public final class Paa {

    private Paa() {
    }

    public static double[] of(double[] x, int w) {
        int n = x.length;
        if (w <= 0 || n == 0) {
            throw new IllegalArgumentException("need w > 0 and a non-empty input");
        }
        double[] out = new double[w];
        for (int s = 0; s < w; s++) {
            double from = (double) s * n / w;
            double to = (double) (s + 1) * n / w;
            double sum = 0;
            double weight = 0;
            for (int i = (int) Math.floor(from); i < Math.ceil(to) && i < n; i++) {
                double overlap = Math.min(to, i + 1) - Math.max(from, i);
                if (overlap > 0 && !Double.isNaN(x[i])) {
                    sum += x[i] * overlap;
                    weight += overlap;
                }
            }
            out[s] = weight == 0 ? Double.NaN : sum / weight;
        }
        return out;
    }
}
