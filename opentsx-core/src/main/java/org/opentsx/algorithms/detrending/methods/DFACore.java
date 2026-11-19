package org.opentsx.algorithms.detrending.methods;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.stat.StatUtils;
import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Vector;

/**
 *
 * @author kamir
 */
abstract public class DFACore implements IDetrendingMethod {

    private static final Logger LOG = LoggerFactory.getLogger(DFACore.class);

    public static double S_SCALE_FACTOR = 1.15;
    
    int[] s = null; // window sizes
    
    double[] zr = null; // raw values of the series
    double[] pr = null; // profile
    double[][] F = null; // fluctuation function
    double alpha = 0; // slope of log F(s) over log s
    DFAParameter para = null;
    StringBuffer status = new StringBuffer();
    private double corr_coef = -9;

    public static boolean debug = false;

    /**
     * Chooses n=steps window sizes on a linear scale. The largest window is
     * N/4, so a single series gives enough segments per window. Many series
     * would not need this limit.
     *
     * with:  s_max = N/4
     * and:   s_min = p+2
     */
    public void initIntervalS() {

        int s_max = para.getN() / 4;

        int s_min = Math.abs( para.getGradeOfPolynom()) + 2;

        int rangeS = s_max-s_min;

        s = new int[para.getzSValues()];

        s[0] = s_min;
        s[para.getzSValues()-1] = s_max;

        int step = rangeS / para.getzSValues();

        for ( int i = 1; i < (para.getzSValues()-1); i++ ) {
            s[i] = s_min + step * i;
        }
        LOG.debug("linear window sizes min={} max={} s={}", s_min, s_max, Arrays.toString(s));
        initF();
    }

    Vector<TimeSeriesObject> fitMR = new Vector<TimeSeriesObject>();

    public Vector<TimeSeriesObject> getMRFit() {
        return fitMR;
    };


    public void showS() {
        LOG.debug("window sizes count={} s={}", s.length, Arrays.toString(s));
    }

    public TimeSeriesObject getResultsMRLogLog() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel_X( "s" );
        mr.setLabel_Y( "F(s)" );
        mr.setLabel( "F(s)" ); // para.toString() );
        for( int i = 0; i < F[0].length ; i++ ) {
            double x =  F[0][i];
            double y =  F[3][i];
            if ( x != 0.0 && y != 0.0 ) mr.addValuePair( Math.log10(x) , Math.log10( y ));
        }
        return mr;
    };

    public TimeSeriesObject getResultsMR() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel_X( "s" );
        mr.setLabel_Y( "F(s)" );
        mr.setLabel( "F(s)" ); // para.toString() );
        for( int i = 0; i < F[1].length ; i++ ) { 
            mr.addValuePair( F[0][i], F[3][i]);
        }
        return mr;
    };

    public TimeSeriesObject getZeitreiheMR() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel_X( "t" );
        mr.setLabel_Y( "y(t)" );
        mr.setLabel( "Time series" );
        for( int i = 0; i < zr.length ; i++ ) {
            mr.addValuePair( i, zr[i]);
        }
        return mr;
    };


    public TimeSeriesObject getProfilMR() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel_X( "t" );
        mr.setLabel_Y( "prof( y(t) )" );
        mr.setLabel( "Profile" );
        for( int i = 0; i < pr.length ; i++ ) {
            mr.addValuePair( i, pr[i]);
        }
        return mr;
    };

    public void initIntervalSlog() {

        int s_start = Math.abs( para.getGradeOfPolynom() ) + 2;

        int s_end = para.getN();

        int log_start=20;

        int lin_steps=log_start-s_start;//first steps linear

        int log_steps=0;

        double tmp=0;
        for (double i=log_start*1.03; Math.round(i) < s_end; i=i*1.03) {//      adding number of steps of log scale

            if(Math.round(i)!=tmp){
                tmp=Math.round(i);
                log_steps++;
            }
        }
        if ( log_steps == 0 ) {
            throw new IllegalArgumentException( "initIntervalSlog: " + s_end + " values are too few; expected at least "
                    + ( Math.round( log_start * 1.03 ) + 1 ) + " values" );
        }
        para.setzSValues(lin_steps+log_steps);
        s = new int[lin_steps+log_steps];

        for (int i =s_start; i<=log_start;i++) s[i-s_start] = i;
        s[lin_steps]=log_start;

        double newS=s[lin_steps];
        for ( int step = 1; step < log_steps;) {
             newS=Double.parseDouble(String.valueOf(newS))*1.03;
             s[lin_steps+step] = Integer.parseInt(String.valueOf(Math.round(newS)));
             if(s[lin_steps+step] != s[lin_steps+step-1]) step++;
        }

        if ( debug ) {
            LOG.debug("logarithmic window sizes s={}", Arrays.toString(s));
        }
        initF();
    }

    /**
     * Allocates the result array F.
     */
    void initF() {
        if (debug) LOG.debug("allocating results windows={}", para.getzSValues());
        F = new double[4][para.getzSValues()];
    }

    /*
     * Sets a new parameter object.
     */
    public void setParameter(Object parameter) {
        para = (DFAParameter) parameter;
    }

    public String getStatus() {
        return status.toString();
    }

    /**
     * Creates a new parameter object.
     *
     * @return
     */
    public Object initParameter() {
        para = new DFAParameter();
        return (Object) para;
    }

    public void setNrOfValues(int N) {
        para.setN(N);
    }

    public int[] getS() {
        return s;
    }

 

 

    public void setZR(double[] zr) {
        this.zr = zr;
    }

    /**
     *   Computes the profile, the cumulative sum of the values minus their mean.
     */int a,b =0;
    public void calcProfile() {
        
        pr = new double[zr.length];

        // mean value of data
        double mw = StatUtils.mean(zr);
        
        // calc profile from data
        
        double prsum = 0.0;
        for (int i = 0; i < zr.length; i++) {
            prsum = 0.0;
            for (int j = 0; j <= i; j++) {
                prsum = prsum + (zr[j] - mw);
                b = j;
            }
            pr[i] = prsum;
            a = i;            
        }
        
    }

    /**
     * Returns the window sizes s.
     */
    public int[] getIntervalS(){
        return s;
    }

    /**
     * Returns the results F.
     */
    public double[][] getResults() {
        return F;
    }

    public DFAParameter getPara() {
        return para;
    }

    public void setPara(DFAParameter para) {
        this.para = para;
    }

    public double getCorr_coef() {
        return corr_coef;
    }

    public void calc() {
        throw new UnsupportedOperationException("Not supported yet.");
    }



    public double[] fit1(int u, int o) {
        double[] x = new double[o + 1];
        for (int i = 0; i <= o; i++) {
            x[i] = i;
        }
        return fit1(x, u, o);
    }

    public double[] fit1(double[] x, int u, int o) {
        double[] fit = new double[2]; // 2 values: slope and constant term
        //calculation for linear term yi=a*xi+b with method of least squares ...
        double N = (o - u) + 1;  //# of fitable values
        if (N > 1) {
            double S, Sx, Sxx, Sy, Sxy, Syy, delta;
            Sx = 0;
            Sxx = 0;
            Sy = 0;
            Sxy = 0;
            Syy = 0;
            for (int i = u; i <= o; i++) {
                S = x[i] - x[u];         //subtract the lower border to keep the numbers small
                Sx += S;               //sum of all x values
                Sxx += S * S;            //sum of all x^2 values
                Sy += pr[i];           //sum of all y values
                Syy += pr[i] * pr[i];    //sum of all y^2 values
                Sxy += pr[i] * S;        //sum of all x*y values
            }
            delta = N * Sxx - Sx * Sx;
            fit[0] = (Sxx * Sy - Sxy * Sx) / delta;    //constant term b of the linear function
            fit[1] = (N * Sxy - Sx * Sy) / delta;    // slope a of the linear function

            //standard deviation
            double stdw, stdw_slope, stdw_const; //stdw sigma^2
            stdw = (fit[0] * fit[0] * Sxx + 2 * fit[0] * fit[1] * Sx - 2 * fit[0] * Sxy - 2 * fit[1] * Sy + Syy + fit[1] * fit[1] * N) / (N - 2);
            stdw_slope = stdw * N / delta;
            stdw_const = stdw * Sxx / delta;

            //correlation coefficient
            double cov_xy = 1 / (N - 1) * (Sxy - Sx * Sy / N); //covarianz from Lit.: Bartsch mathematics
            double cov_xx = 1 / (N - 1) * (Sxx - Sx * Sx / N);
            double cov_yy = 1 / (N - 1) * (Syy - Sy * Sy / N);
            corr_coef = cov_xy / Math.sqrt(cov_xx * cov_yy); // calculation of corcoef from matlab
        }
        return fit;
    }

    public double[] fit2(int u, int o) {
        int N;
        double S = 0, Sx = 0, Sxx = 0, Sxxx = 0, Sxxxx = 0, Sy = 0, Sxy = 0, Syy = 0, Sxxy = 0, delta = 0;
        N = (o - u) + 1;  //# of fitable values
        //calculation of quadratic term yi=a*xi^2+b*xi+c with method of least squares ...
        double[] fit = null;
        if (N > 2) {
            fit = new double[3]; // 3 values c, b, a of the quadratic term ...
            
            Sx = 0;
            Sxx = 0;
            Sxxx = 0;
            Sxxxx = 0;
            Sy = 0;
            Sxy = 0;
            Sxxy = 0;
            Syy = 0;
            for (int i = u; i <= o; i++) {
                S = i - u;
                Sx += S;              //sum of all x values
                Sxx += (S *= S);        //sum of all x^2 values
                Sxxx += S * (i - u);          //sum of all x^3 values
                Sxxxx += S * S;         //sum of all x^4 values
                Sy += pr[i];          //sum of all y values
                Syy += pr[i] * pr[i];   //sum of all y^2 values
                Sxy += (i - u) * pr[i];       //sum of all x*y values
                Sxxy += S * pr[i];      //sum of all x^2*y values
            }
            delta = Sxxxx * (N * Sxx - Sx * Sx) + Sxxx * (Sx * Sxx - N * Sxxx) + Sxx * (Sxxx * Sx - Sxx * Sxx);
            fit[0] = (Sxxxx * (Sxx * Sy - Sxy * Sx) + Sxxx * (Sxy * Sxx - Sxxx * Sy) + Sxxy * (Sxxx * Sx - Sxx * Sxx)) / delta;//calculation of c of quadratic term yi=a*xi^2+b*xi+c ...
            fit[1] = (Sxxxx * (N * Sxy - Sx * Sy) + Sxxy * (Sx * Sxx - N * Sxxx) + Sxx * (Sxxx * Sy - Sxy * Sxx)) / delta;//calculation of b of quadratic term yi=a*xi^2+b*xi+c ...
            fit[2] = (Sxxy * (N * Sxx - Sx * Sx) + Sxxx * (Sx * Sy - N * Sxy) + Sxx * (Sxy * Sx - Sxx * Sy)) / delta;//calculation of a of quadratic term yi=a*xi^2+b*xi+c ...
        } else {
            status.append("min 4 values for quadratic fit");
        }
        return fit;
    }

    /**
     * Fits a polynomial of order n to the profile from index u to index o by least squares and returns the
     * coefficients c_0 to c_n of c_0 + c_1 (i - u) + ... + c_n (i - u)^n, the form fx evaluates.
     */
    public double[] fitn(int u, int o, int n) {
        int points = o - u + 1;
        if ( points < n + 1 ) {
            throw new IllegalArgumentException( "fitn: " + points + " points cannot determine a polynomial of order " + n
                    + "; expected at least " + ( n + 1 ) + " points" );
        }
        // the powers of (i - u) / width keep the least squares problem well conditioned
        double width = Math.max( 1, points - 1 );
        double[][] powers = new double[points][n + 1];
        double[] values = new double[points];
        for ( int i = 0; i < points; i++ ) {
            double t = i / width;
            double p = 1.0;
            for ( int k = 0; k <= n; k++ ) {
                powers[i][k] = p;
                p = p * t;
            }
            values[i] = pr[u + i];
        }
        double[] fit = new QRDecomposition( new Array2DRowRealMatrix( powers, false ) ).getSolver()
                .solve( new ArrayRealVector( values, false ) ).toArray();
        double scale = 1.0;
        for ( int k = 0; k <= n; k++ ) {
            fit[k] = fit[k] / scale;
            scale = scale * width;
        }
        return fit;
    }

    double alphaFitMin, alphaFitMax;

    // linear fit of loglogplot of F(s) and s to get alpha
    public void calcAlpha(double log_fit_min, double log_fit_max) {
        DFAmulti instance = new DFAmulti();
        double[][] result = new double[2][F[0].length];
        for (int i = 0; i < F[0].length; i++) {
            result[0][i] = Math.log10(F[0][i]);
            result[1][i] = Math.log10(F[1][i]);
            status.append("\n. log F(s):" + (result[0][i]) + "\t log s: " + (result[1][i]));
        }
        instance.pr = result[1];
        int min = 0, max = 0;
        int i;
        for (i = 0; i < result[0].length; i++) {
            if (result[0][i] >= log_fit_min && min == 0) {
                min = i;
            }
            if (result[0][i] >= log_fit_max && max == 0) {
                max = i;
                break;
            }
        }
        if (max == 0) {
            max = result[0].length - 1;
        }

        double[] fit = instance.fit1(result[0], min, max);
        corr_coef = instance.getCorr_coef();
        alpha = fit[1];
        alphaFitMin = F[0][min];
        alphaFitMax = F[0][max];
    }

    public double fx( double posX , double[] terms ){
        double a = 0;
        for ( int i = 0; i < terms.length ; i++) {
            a += terms[ i ] * Math.pow(posX, i);
        }
        return a;
    }

    public double getAlphaFitMax() {
        return alphaFitMax;
    }

    public double getAlphaFitMin() {
        return alphaFitMin;
    }

    public double getAlpha() {
        this.calcAlpha(Math.log10(F[0][0]), Math.log10(F[0][F[0].length - 1]));
        return alpha;
    }

    public double getAlpha(double min, double max) {
        this.calcAlpha(min, max);
        return alpha;
    }












}
