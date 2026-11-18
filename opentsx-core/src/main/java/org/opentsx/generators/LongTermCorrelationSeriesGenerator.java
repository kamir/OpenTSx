package org.opentsx.generators;

/**
 * Generator for long-term correlated time series. 
 * 
 * The idea is simple. A Fourier-Transformation is
 * applied to a series of random-numbers. Than, the 
 * resulting Fourier Coefficients are modified.
 * 
 * The Fourier-Transformation is applied again and the
 * result is a random number series with long term 
 * correlations.
 * 
 * The main method is a Tester which shows DFA2 for 
 * multiple test series and a chart with the dependency
 * between alpha and beta, beside the theoretical 
 * curve.
 * 
 * SOURCE : 
 * 
 */

import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Random;
import java.util.Vector;

public class LongTermCorrelationSeriesGenerator {
    
    private static boolean debug = false;

    static double fitMIN = 0.8;
    static double fitMAX = 2.25;
    
    static TimeSeriesObject alphasCALC = new TimeSeriesObject();
    static TimeSeriesObject alphasTHEO = new TimeSeriesObject();
    static Vector<TimeSeriesObject> check = new Vector<TimeSeriesObject>();
    
    static Vector<TimeSeriesObject> tests = null;
    static StringBuffer log = null;
    
    static double[][] alphas = null; 
    static double[] betas = null; 
    
  
    
    // show a DFA curve only in this case
    static boolean firstInLoop = false;
    static int numberOfLoop = 0;
    static int numberOfBeta = 0;
 
    static int order = -1; // is defined in method: getRandomRow() ...
    
    
    /**
     *   Parameters with influence on RUNTIME and PERFORMANCE
     */
    static public int nrOfSValues = 250;
    
    static int EXP = 10; // 2 ^ EXP = length of the row                     //  <--------

    public static final String DEFAULT_DIRECTORY = "./data/";
    public static final long DEFAULT_SEED = 1;

    private static final Random DEFAULT_RANDOM = new Random( DEFAULT_SEED );

    /**
     * Arguments: [output directory, default ./data/] [seed, default 1]. Writes random_numbers_CHECK.csv and
     * random_numbers_FS.csv into the output directory and shows both charts.
     */
    public static void main(String args[]) throws Exception {
        
        System.out.println( "*** LongTermCorrelationSeriesGenerator *** " );

        File directory = new File( args.length > 0 ? args[0] : DEFAULT_DIRECTORY );
        long seed = DEFAULT_SEED;
        if ( args.length > 1 ) {
            try {
                seed = Long.parseLong( args[1] );
            }
            catch ( NumberFormatException ex ) {
                throw new IllegalArgumentException( "seed: \"" + args[1] + "\" is not a whole number; expected a seed such as 1", ex );
            }
        }

        writeTables( directory, new Random( seed ) );

        MultiChart.open(tests, "Fluctuation Function F(s): DFA " + order, 
                        "log(s)", "log(F(s))", false, log.toString(), null );
        MultiChart.open(check, "alpha vs. beta", 
                        "beta", "alpha", true, log.toString() , null);
    }

    /**
     * Runs DFA on series for 15 values of beta, 10 series each, and writes random_numbers_CHECK.csv and
     * random_numbers_FS.csv into directory.
     */
    public static void writeTables( File directory, Random random ) throws Exception {

        boolean showTest = true;

        // prepare some data structures
        tests = new Vector<TimeSeriesObject>();
        log = new StringBuffer();
                
        log.append("fit range: [" + fitMIN + ", ..., " + fitMAX + " ]\n" );
        
//        int Z = 15;
//        double STRETCH = 4;   // the product must be 60

        int Z = 15;                                                         //  <--------
        double STRETCH = 6;                                                 //  <--------
        
        int LOOPS = 10;                                                     //  <--------
        
        alphas = new double[Z][LOOPS];
        betas = new double[Z]; 

        // double[] alphas = { 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3 };  

        for( int i = 0; i < Z; i++ ) {
            
            numberOfBeta = i;
            
            double beta = -1.0 + ( i * STRETCH * 0.05 ); 
            
            if ( showTest ) { 
                System.out.println(">>> i=" +  i + "\t beta=" + beta + " Z=" + Z );
            }
            
            for( int j = 0; j < LOOPS; j++ ) {  
                
                if ( j == 0 ) firstInLoop = true;
                else firstInLoop = false;
                numberOfLoop = j;
                
                TimeSeriesObject mr = getRandomRow( (int)Math.pow(2, EXP), beta , random, showTest, true );
            }    
        }
        
        firstInLoop = true;
        TimeSeriesObject mr = getRandomRow( (int)Math.pow(2, EXP), 0 , random, showTest, true );
        
        alphasCALC.setLabel("CALC");
        alphasTHEO.setLabel("theory");
        // check.add( alphasCALC );
       // check.add( alphasTHEO );
        
        _calcErrorChart( alphas, numberOfBeta, numberOfLoop, 100.0 );

        File f = directory;
        if ( !f.isDirectory() && !f.mkdirs() ) {
            throw new IOException( f + ": cannot create the directory; expected a writable path" );
        }
        
        File f1 = new File( f, "random_numbers_CHECK.csv" );
        File f2 = new File( f, "random_numbers_FS.csv" );
        
        MeasurementTable mwt = new MeasurementTable( "random_numbers_CHECK" );
       
        mwt.singleX = false;
        mwt.setMessReihen( check );
        mwt.setHeader("# random_numbers_CHECK");
        mwt.writeToFile( f1 );
        
        
        
        
        mwt = new MeasurementTable( "random_numbers_FS" );
        
        mwt.createParrentFile( f );
        mwt.singleX = false;
        mwt.setMessReihen( tests );
        mwt.setHeader("# random_numbers_FS");
        mwt.writeToFile( f2 );
        
        
        
        
    }
    
     
    
  
    
    /**
     * A phase randomized time series is generated.
     * 
     * @param d4
     * @param showTest
     * @param runDFA
     * @return
     * @throws Exception 
     */    
    
    public static TimeSeriesObject getPhaseRandomizedRow(TimeSeriesObject d4, boolean showTest, boolean runDFA ) throws Exception {
        
        DecimalFormat df = new DecimalFormat("0.000");
        
        int N = d4.yValues.size();
        
        double[] zr = new double[N];

        // pass the array with the data of the series
        TimeSeriesObjectFFT mr4_NEW = TimeSeriesObjectFFT.convertToMessreiheFFT(d4);
        
        TimeSeriesObjectFFT temp = mr4_NEW.getModifiedTimeSeries_PhaseRandomized();

        if ( runDFA ) {

            Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();
            Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
            vr.add( d4 );

            zr = d4.getData()[1];

            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
            order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues( nrOfSValues );

            System.out.println( "nrOfSValues="+nrOfSValues );
            // number of values in the series
            dfa.setNrOfValues(N);

            // choose the values of the window sizes
//            dfa.initIntervalS();
            dfa.initIntervalSlog();
            
            
            if ( debug ) dfa.showS();


            // http://stackoverflow.com/questions/12049407/build-sample-data-for-apache-commons-fast-fourier-transform-algorithm

            dfa.setZR(temp.getData()[1]);

            dfa.calc();

            TimeSeriesObject mr4 = dfa.getResultsMRLogLog();
            mr4.setLabel( d4.getLabel() + " ( PR )" );
            v.add(mr4);

            String status = dfa.getStatus();

            SimpleRegression alphaSR = mr4.linFit(fitMIN, fitMAX);

            double alpha = alphaSR.getSlope();


            if ( log != null ) log.append( "PR:" + "\t" + df.format( alpha ) + "\n" );

            if ( showTest ) {
//                MultiChart.open(v, "fluctuation function F(s) [order:" + order + "] ", "log(s)", "log(F(s))", true, "alpha=" + alpha );
                if ( firstInLoop ) tests.add(mr4);
            }

            System.out.println( " alpha = " + df.format( alpha ) );
     
        }
        
        System.out.println( temp.yValues.size() + " " + temp.getLabel() );
        return temp;
    }
        
    /**
     * A time series with long term correlation (according to parameter BETA)
     * is generated with Fourier Filtering. For every beta, beta 0 included,
     * the filtered values are divided by their standard deviation, so the
     * result has variance 1.
     * 
     * @param d4
     * @param beta
     * @param showTest
     * @param runDFA
     * @return
     * @throws Exception 
     */    
    public static TimeSeriesObject getRandomRow(TimeSeriesObject d4, double beta, boolean showTest, boolean runDFA ) throws Exception {
        
        DecimalFormat df = new DecimalFormat("0.000");
        
        int N = d4.yValues.size();
        
        double[] zr = new double[N];

        // pass the array with the data of the series
        TimeSeriesObjectFFT mr4_NEW = TimeSeriesObjectFFT.convertToMessreiheFFT(d4);
        
        TimeSeriesObjectFFT temp = mr4_NEW.getModifiedTimeSeries_FourierFiltered( beta );

        double std = temp.getStddev();
        if ( !(std > 0) || Double.isInfinite( std ) ) {
            throw new IllegalArgumentException( "getRandomRow: the filtered series of " + N + " values has standard deviation "
                    + std + "; expected at least 2 values that are not all equal" );
        }
        for ( int i = 0; i < temp.yValues.size(); i++ ) {
            temp.yValues.set( i, (Double) temp.yValues.elementAt( i ) / std );
        }

        if ( runDFA ) {

            Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();
            Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
            vr.add( d4 );

            zr = d4.getData()[1];

            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
            order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues( nrOfSValues );

            System.out.println( "nrOfSValues="+nrOfSValues );
            // number of values in the series
            dfa.setNrOfValues(N);

            // choose the values of the window sizes
//            dfa.initIntervalS();
            dfa.initIntervalSlog();
            
            
            if ( debug ) dfa.showS();


            // http://stackoverflow.com/questions/12049407/build-sample-data-for-apache-commons-fast-fourier-transform-algorithm

            dfa.setZR(temp.getData()[1]);

            dfa.calc();

            TimeSeriesObject mr4 = dfa.getResultsMRLogLog();
            mr4.setLabel( d4.getLabel() + " (" + beta + ")" );
            v.add(mr4);

            String status = dfa.getStatus();

            SimpleRegression alphaSR = mr4.linFit(fitMIN, fitMAX);

            double alpha = alphaSR.getSlope();

            alphasCALC.addValuePair(beta, alpha );
            alphasTHEO.addValuePair(beta, ( 1.0 + beta ) / 2.0 );

            if ( log != null ) log.append( df.format( beta ) + "\t" + df.format( alpha ) + "\n" );

            if ( showTest ) {
//                MultiChart.open(v, "fluctuation function F(s) [order:" + order + "] ", "log(s)", "log(F(s))", true, "alpha=" + alpha );
                if ( firstInLoop ) tests.add(mr4);

                try{
                    alphas[numberOfBeta][numberOfLoop] = alpha;  
                    betas[numberOfBeta] = beta;
                }
                catch(Exception ex) { 
                    
                }
            }

            System.out.println( " alpha = " + df.format( alpha ) );
            System.out.println( "  beta = " + df.format( beta ) );
            System.out.println( "       = " + ( (2 * alpha) - 1.0 ) );

        }
        
        System.out.println( temp.yValues.size() + " " + temp.getLabel() );
        return temp;
    }

    /**
     * Filters Gaussian white noise drawn from one Random with the seed DEFAULT_SEED that all calls without a Random
     * share, so a program gets the same series in the same order on every run.
     *
     * @param length
     * @param beta
     * @param showTest
     * @return
     * @throws Exception 
     */
    public static TimeSeriesObject getRandomRow(int length, double beta, boolean showTest, boolean runDFA ) throws Exception {
        return getRandomRow(length, beta, DEFAULT_RANDOM, showTest, runDFA);
    }

    /**
     * Filters Gaussian white noise drawn from random. The same seed gives the same series.
     *
     * @param length
     * @param beta
     * @param random
     * @param showTest
     * @return
     * @throws Exception 
     */
    public static TimeSeriesObject getRandomRow(int length, double beta, Random random, boolean showTest, boolean runDFA ) throws Exception {
        
        // has to be of type int
        // N = length; by a multiple of 4 !!!
        int N = length;
        
        
        System.out.println( "N=" + N );
        /**
         * Precondition:
         *
         * A series with clean values and no gaps, unless gaps have a
         * meaning in the domain.
         */
        
        TimeSeriesObject d4 = new TimeSeriesObject();
        d4.setLabel( N + " => gaussian" );
        for ( int j = 0; j < N; j++ ) {
            d4.addValuePair( 1.0 * j, random.nextGaussian() );
        }
        
        return getRandomRow(d4, beta, showTest, runDFA);
    }  
    
    static TimeSeriesObject mrAV = new TimeSeriesObject();  // means
    static TimeSeriesObject mrSTD = new TimeSeriesObject();  // standard deviations
    static TimeSeriesObject mrMAX = new TimeSeriesObject();  // ERROR
    static TimeSeriesObject mrMIN = new TimeSeriesObject();  // ERROR
        
    /**
     * Helper function to produce a debugging chart.
     */ 
    private static void _calcErrorChart(double[][] d, int nrB, int nrLOOPS , double scaleSTDEV ) {
        
        mrAV = new TimeSeriesObject();  // means
        mrSTD = new TimeSeriesObject();  // standard deviations
        mrMAX = new TimeSeriesObject();  // ERROR
        mrMIN = new TimeSeriesObject();  // ERROR
    
        mrAV.setLabel("mean");
        mrSTD.setLabel("stddev * " + scaleSTDEV );
        mrMAX.setLabel("upper");
        mrMIN.setLabel("lower");
        
        for( int iB = 0; iB < nrB; iB++ ) { 
            
            double[] L = new double[nrLOOPS];
            
            // copy the values
            for( int iL = 0; iL < nrLOOPS; iL++ ) { 
                L[iL] = d[iB][iL];
            }
            try {

                StandardDeviation stdev = new StandardDeviation();

                double mean = StatUtils.mean( L );
                double std = stdev.evaluate( L );

                mrAV.addValuePair(betas[iB] , mean );
                mrSTD.addValuePair(betas[iB] , std * scaleSTDEV );

                mrMAX.addValuePair( betas[iB] , mean+std );
                mrMIN.addValuePair( betas[iB] , mean-std );
            }
            catch(Exception ex) { 
                System.err.println( ex.getMessage() );
                System.err.println( ex.getCause() );
                
            }
        }
        
        check.add( mrAV );        
        check.add( mrSTD );
        check.add( mrMAX );
        check.add( mrMIN );
        
    }

    /**
     *
     * @return 
     */
    static TimeSeriesObject getRandomRowSchreiberSchmitz(TimeSeriesObject row, double beta, boolean f1, boolean f2, int rounds) throws Exception {

                    System.out.println("\n\n\n\n" + rounds + "rounds" );
                    System.out.println(      "-------------------------" );

        TimeSeriesObject rowOriginal = row.copy();
        
        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
        
        for( int i = 0; i < rounds; i++ ){
            
            TimeSeriesObject mr = LongTermCorrelationSeriesGenerator.getRandomRow(row, beta, false, false);
            
            row = mr.exchangeRankWise( rowOriginal );
            
            v.add( mr.copy() );
            v.add( row.copy() );
            
            System.out.println("\nRound NR:" + i + "\n" );
            
        }
        
        MultiChart.open(v, "Schreiber Schmitz DEBUGGING", 
                        "y(t)", "t", true, "", null);
        
        
        
        
        return row;
        
    }
    

}
