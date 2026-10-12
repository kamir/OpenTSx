package org.opentsx.retrieval.pattern;

/**
 * Dynamic time warping with a Sakoe-Chiba band and the lower bounds of the UCR suite (Rakthanmanon et al., KDD 2012):
 * LB_Kim (first/last point) and LB_Keogh (query envelope). All distances are Euclidean-style (square root of summed
 * squared differences), so they are comparable with z-normalized Euclidean distance (band 0 = Euclidean).
 */
public final class Dtw {

    private Dtw() {
    }

    /** DTW distance, or +inf as soon as it must exceed {@code cutoff} (early abandoning). */
    public static double distance(double[] q, double[] c, int band, double cutoff) {
        int m = q.length;
        if (c.length != m) {
            throw new IllegalArgumentException("DTW here compares equal lengths");
        }
        double limit = cutoff == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : cutoff * cutoff;
        double[] prev = new double[m];
        double[] cur = new double[m];
        java.util.Arrays.fill(prev, Double.POSITIVE_INFINITY);
        for (int i = 0; i < m; i++) {
            java.util.Arrays.fill(cur, Double.POSITIVE_INFINITY);
            double rowMin = Double.POSITIVE_INFINITY;
            int from = Math.max(0, i - band);
            int to = Math.min(m - 1, i + band);
            for (int j = from; j <= to; j++) {
                double d = q[i] - c[j];
                d *= d;
                double best;
                if (i == 0 && j == 0) {
                    best = 0;
                } else {
                    best = Double.POSITIVE_INFINITY;
                    if (i > 0) {
                        best = Math.min(best, prev[j]);
                        if (j > 0) {
                            best = Math.min(best, prev[j - 1]);
                        }
                    }
                    if (j > 0) {
                        best = Math.min(best, cur[j - 1]);
                    }
                }
                cur[j] = d + best;
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) {
                return Double.POSITIVE_INFINITY;
            }
            double[] t = prev;
            prev = cur;
            cur = t;
        }
        return Math.sqrt(prev[m - 1]);
    }

    /** LB_Kim (first and last point; valid for DTW with any band). */
    public static double lbKim(double[] q, double[] c) {
        int m = q.length;
        double a = q[0] - c[0];
        double b = q[m - 1] - c[m - 1];
        return Math.sqrt(a * a + (m > 1 ? b * b : 0));
    }

    /** Upper and lower envelope of {@code q} for the band: {upper, lower}. */
    public static double[][] envelope(double[] q, int band) {
        int m = q.length;
        double[] u = new double[m];
        double[] l = new double[m];
        for (int i = 0; i < m; i++) {
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int j = Math.max(0, i - band); j <= Math.min(m - 1, i + band); j++) {
                hi = Math.max(hi, q[j]);
                lo = Math.min(lo, q[j]);
            }
            u[i] = hi;
            l[i] = lo;
        }
        return new double[][]{u, l};
    }

    /** LB_Keogh of candidate {@code c} against the query envelope; +inf once above {@code cutoff}. */
    public static double lbKeogh(double[][] envelope, double[] c, double cutoff) {
        double limit = cutoff == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : cutoff * cutoff;
        double[] u = envelope[0];
        double[] l = envelope[1];
        double sum = 0;
        for (int i = 0; i < c.length; i++) {
            double d = c[i] > u[i] ? c[i] - u[i] : c[i] < l[i] ? l[i] - c[i] : 0;
            sum += d * d;
            if (sum > limit) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return Math.sqrt(sum);
    }
}
