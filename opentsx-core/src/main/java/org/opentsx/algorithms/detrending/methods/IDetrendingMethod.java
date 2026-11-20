package org.opentsx.algorithms.detrending.methods;

import org.opentsx.data.series.TimeSeriesObject;

import java.util.Vector;

/**
 *
 * @author kamir
 */
public interface IDetrendingMethod {

        public double fx(double x, double[] terms);

    /**
     * Replaces the internal parameter object.
     * 
     * @param parameter
     */
    public void setParameter( Object parameter );

    /**
     * Creates the internal parameter object and returns it, so that the
     * caller can initialize it.
     * 
     * @return
     */
    public Object initParameter();

    /**
     * Sets the number of values of the series; call it before the
     * calculation.
     *
     * @param length
     */
    public void setNrOfValues(int length);

    /**
     * Sets the window sizes.
     */
    public void initIntervalS();

    /**
     * Sets logarithmically spaced window sizes.
     */
    public void initIntervalSlog();
    public void initIntervalS_FULL();
    
    public void initIntervalS_version4();

    /**
     * Sets the array with the data.
     */
    public void setZR(double[] zr);

    /**
     * Turns the data array into the profile array.
     */
    public void calcProfile();
    public TimeSeriesObject getProfilMR();

    public TimeSeriesObject getZeitreiheMR();
    /**
     * correlation coefficient
     */
    public double getCorr_coef();

    /**
     * run dfa calculation ...
     */
    public void calc();

    /**
     * calc alpha and return value ...fitted from beginning to end of F(s)
     */
    public double getAlpha();
    /**
     * calc alpha and return value ... for given fit border
     */
    public double getAlpha(double min, double max);

    /**
     * Returns the values s and F(s) as an array.
     */
    public double[][] getResults();

    /**
     * Returns the values s and F(s) as a TimeSeriesObject.
     */
    public TimeSeriesObject getResultsMR();
    public TimeSeriesObject getResultsMRLogLog();

    public Vector<TimeSeriesObject> getMRFit();




    /**
     * Returns the status text of the last calculation.
     *
     * @return status
     */
    public String getStatus();

    /**
     * returns the dfa parameter
     */
    public DFAParameter getPara();

    /**
     * returns the actually used fitminborder
     */
    public double getAlphaFitMax();

    /**
     * returns the actually used fitaxborder
     */
    public double getAlphaFitMin();

        /**
     * returns the dfa parameter
     */
    public int[] getS();
    public void showS();

}
