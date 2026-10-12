package org.opentsx.retrieval.pattern;

/**
 * Symbolic Aggregate approXimation: z-normalize, PAA to {@code w} segments, map each segment to one of {@code a}
 * letters by standard-normal breakpoints. {@link #mindist} lower-bounds the Euclidean distance of the (z-normalized)
 * inputs, which makes SAX words an exact pre-filter.
 *
 * <p>Stored form (e.g. in {@code EpisodeSummary.sax}): {@code "<w>x<a>:<letters>"}, e.g. {@code "16x8:aabcfhhgec..."}.
 */
public final class Sax {

    private Sax() {
    }

    /** Breakpoints beta_1..beta_{a-1} of the standard normal distribution. */
    public static double[] breakpoints(int a) {
        if (a < 2 || a > 26) {
            throw new IllegalArgumentException("alphabet size must be in [2, 26]");
        }
        double[] b = new double[a - 1];
        for (int i = 1; i < a; i++) {
            b[i - 1] = inverseNormal((double) i / a);
        }
        return b;
    }

    /** SAX letters of an already z-normalized vector. */
    public static String letters(double[] z, int w, int a) {
        double[] paa = Paa.of(z, w);
        double[] b = breakpoints(a);
        StringBuilder sb = new StringBuilder(w);
        for (double v : paa) {
            double x = Double.isNaN(v) ? 0 : v;
            int k = 0;
            while (k < b.length && x >= b[k]) {
                k++;
            }
            sb.append((char) ('a' + k));
        }
        return sb.toString();
    }

    /** Stored form {@code "<w>x<a>:<letters>"} of an already z-normalized vector. */
    public static String word(double[] z, int w, int a) {
        return w + "x" + a + ":" + letters(z, w, a);
    }

    /** Lower bound of the Euclidean distance between the two length-n vectors the words were made from. */
    public static double mindist(String word1, String word2, int n) {
        int c1 = word1.indexOf(':');
        int c2 = word2.indexOf(':');
        if (c1 < 0 || c2 < 0 || !word1.substring(0, c1).equals(word2.substring(0, c2))) {
            throw new IllegalArgumentException("incompatible SAX words: " + word1 + " / " + word2);
        }
        int a = Integer.parseInt(word1.substring(word1.indexOf('x') + 1, c1));
        String l1 = word1.substring(c1 + 1);
        String l2 = word2.substring(c2 + 1);
        double[] b = breakpoints(a);
        double sum = 0;
        for (int i = 0; i < l1.length(); i++) {
            int r = l1.charAt(i) - 'a';
            int c = l2.charAt(i) - 'a';
            if (Math.abs(r - c) > 1) {
                double d = b[Math.max(r, c) - 1] - b[Math.min(r, c)];
                sum += d * d;
            }
        }
        return Math.sqrt((double) n / l1.length()) * Math.sqrt(sum);
    }

    /** Inverse of the standard normal CDF (Acklam's rational approximation, relative error < 1.2e-9). */
    static double inverseNormal(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
                -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
                -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
                4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pl = 0.02425;
        if (p < pl) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - pl) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
