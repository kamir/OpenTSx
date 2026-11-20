package org.opentsx.algorithms.statistics;

import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

/**
 * A simple Granger-Causality test in pure Java.
 */
public class GrangerCausality {

    /**
     * Returns the p-value for the Granger-Causality test.
     *
     * @param y - predictable variable
     * @param x - predictor
     * @param l - lag, should be 1 or greater.
     * @return p-value of Granger-Causality
     */
    public static double grangerCausalityTest(double[] y, double[] x, int l){
        if (l < 1) {
            throw new IllegalArgumentException("l: " + l + " is out of range; expected a lag of 1 or more");
        }
        if (x.length != y.length) {
            throw new IllegalArgumentException("x: " + x.length + " values do not match the " + y.length
                    + " values of y; expected two series of the same length");
        }
        if (y.length < 3L * l + 2) {
            throw new IllegalArgumentException("y: " + y.length + " values are too few for lag " + l
                    + "; expected at least " + (3L * l + 2) + " values");
        }
        OLSMultipleLinearRegression h0 = new OLSMultipleLinearRegression();
        OLSMultipleLinearRegression h1 = new OLSMultipleLinearRegression();

        double[][] laggedY = createLaggedSide(l, y);

        double[][] laggedXY = createLaggedSide(l, x, y);

        int n = laggedY.length;

        try {
            h0.newSampleData(strip(l, y), laggedY);
            h1.newSampleData(strip(l, y), laggedXY);

            double rs0[] = h0.estimateResiduals();
            double rs1[] = h1.estimateResiduals();

            double RSS0 = sqrSum(rs0);
            double RSS1 = sqrSum(rs1);

            double ftest = ((RSS0 - RSS1)/l) / (RSS1 / ( n - 2*l - 1));

            FDistribution fDist = new FDistribution(l, n-2*l-1);
            return 1.0 - fDist.cumulativeProbability(ftest);
        } catch (MathIllegalArgumentException e) {
            throw new IllegalArgumentException("y, x: the regression of lag " + l + " over " + y.length
                    + " values cannot be solved (" + e.getMessage() + "); expected series that are not constant", e);
        }

    }


    private static double[][] createLaggedSide(int L, double[]... a) {
        int n = a[0].length - L;
        double[][] res = new double[n][L*a.length];
        for(int i=0; i<a.length; i++){
            double[] ai = a[i];
            for(int l=0; l<L; l++){
                for(int j=0; j<n; j++){
                    res[j][i*L+l] = ai[l+j];
                }
            }
        }
        return res;
    }

    public static double sqrSum(double[] a){
        double res = 0;
        for(double v : a){
            res+=v*v;
        }
        return res;
    }


     public static double[] strip(int l, double[] a){

        double[] res = new double[a.length-l];
        System.arraycopy(a, l, res, 0, res.length);
        return res;
    }




}
