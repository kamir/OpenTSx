package org.opentsx.retrieval.pattern;

/**
 * MASS (Mueen's Algorithm for Similarity Search): the z-normalized Euclidean distance between a query and every
 * subsequence of a series in O(n log n) via FFT.
 *
 * <p>Conventions: a window containing NaN gets +inf; a constant window has distance 0 to a constant query and
 * sqrt(m) to any other query.
 */
public final class Mass {

    private Mass() {
    }

    public static double[] distanceProfile(double[] query, double[] series) {
        int m = query.length;
        int n = series.length;
        if (m < 2 || n < m) {
            return new double[Math.max(0, n - m + 1)];
        }
        // query statistics
        double qMean = 0;
        for (double v : query) {
            qMean += v;
        }
        qMean /= m;
        double qVar = 0;
        for (double v : query) {
            qVar += (v - qMean) * (v - qMean);
        }
        double qSd = Math.sqrt(qVar / m);
        boolean qFlat = qSd < ZNorm.FLAT;

        // series: shift by its mean for numerical stability, NaN -> 0 (windows with NaN are masked)
        double shift = 0;
        int finite = 0;
        for (double v : series) {
            if (!Double.isNaN(v)) {
                finite++;
                shift += (v - shift) / finite;
            }
        }
        double[] x = new double[n];
        int[] nanPrefix = new int[n + 1];
        double[] s1 = new double[n + 1];
        double[] s2 = new double[n + 1];
        for (int i = 0; i < n; i++) {
            boolean nan = Double.isNaN(series[i]);
            x[i] = nan ? 0 : series[i] - shift;
            nanPrefix[i + 1] = nanPrefix[i] + (nan ? 1 : 0);
            s1[i + 1] = s1[i] + x[i];
            s2[i + 1] = s2[i] + x[i] * x[i];
        }

        // sliding dot products QT via FFT convolution with the reversed (z-normalized) query
        int size = Fft.nextPow2(n + m);
        double[] ar = new double[size];
        double[] ai = new double[size];
        double[] br = new double[size];
        double[] bi = new double[size];
        System.arraycopy(x, 0, ar, 0, n);
        for (int i = 0; i < m; i++) {
            br[i] = qFlat ? 0 : (query[m - 1 - i] - qMean) / qSd;
        }
        Fft.transform(ar, ai, false);
        Fft.transform(br, bi, false);
        for (int i = 0; i < size; i++) {
            double r = ar[i] * br[i] - ai[i] * bi[i];
            double im = ar[i] * bi[i] + ai[i] * br[i];
            ar[i] = r;
            ai[i] = im;
        }
        Fft.transform(ar, ai, true);

        double[] out = new double[n - m + 1];
        for (int i = 0; i <= n - m; i++) {
            if (nanPrefix[i + m] - nanPrefix[i] > 0) {
                out[i] = Double.POSITIVE_INFINITY;
                continue;
            }
            double mu = (s1[i + m] - s1[i]) / m;
            double var = (s2[i + m] - s2[i]) / m - mu * mu;
            double sd = var > 0 ? Math.sqrt(var) : 0;
            boolean flat = sd < ZNorm.FLAT * Math.max(1, Math.abs(mu + shift));
            if (flat || qFlat) {
                out[i] = flat && qFlat ? 0 : Math.sqrt(m);
                continue;
            }
            // dot(zq, x_window) = dot(zq, zx) * sd  (zq has zero mean)
            double corr = ar[i + m - 1] / (m * sd);
            double d2 = 2.0 * m * (1 - corr);
            out[i] = Math.sqrt(Math.max(0, d2));
        }
        return out;
    }
}
