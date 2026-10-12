package org.opentsx.retrieval.pattern;

/** z-normalization; a (near-)constant input becomes all zeros. NaN values become 0 after normalization. */
public final class ZNorm {

    static final double FLAT = 1e-8;

    private ZNorm() {
    }

    public static double[] of(double[] x) {
        double mean = 0;
        int n = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                n++;
                mean += (v - mean) / n;
            }
        }
        double m2 = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) {
                m2 += (v - mean) * (v - mean);
            }
        }
        double sd = n == 0 ? 0 : Math.sqrt(m2 / n);
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = Double.isNaN(x[i]) || sd < FLAT ? 0 : (x[i] - mean) / sd;
        }
        return out;
    }
}
