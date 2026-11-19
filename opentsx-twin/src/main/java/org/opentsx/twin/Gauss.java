package org.opentsx.twin;

import java.util.Arrays;

/** The linear solver of the calibrations. */
public final class Gauss {

    private Gauss() {
    }

    /** Gaussian elimination with partial pivoting for small systems; NaN for a singular system. Changes no argument. */
    public static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = a0[i].clone();
        }
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int i = c + 1; i < n; i++) {
                if (Math.abs(a[i][c]) > Math.abs(a[piv][c])) {
                    piv = i;
                }
            }
            if (Math.abs(a[piv][c]) < 1e-300) {
                double[] nan = new double[n];
                Arrays.fill(nan, Double.NaN);
                return nan;
            }
            double[] tr = a[c];
            a[c] = a[piv];
            a[piv] = tr;
            double tb = b[c];
            b[c] = b[piv];
            b[piv] = tb;
            for (int i = c + 1; i < n; i++) {
                double f = a[i][c] / a[c][c];
                for (int k = c; k < n; k++) {
                    a[i][k] -= f * a[c][k];
                }
                b[i] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int k = i + 1; k < n; k++) {
                s -= a[i][k] * x[k];
            }
            x[i] = s / a[i][i];
        }
        return x;
    }
}
