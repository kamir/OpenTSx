package org.apache.hadoopts.app.thesis;

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
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.RNGWrapper;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.data.series.TimeSeriesObjectFFT;
import org.apache.hadoopts.statphys.detrending.DetrendingMethodFactory;
import org.apache.hadoopts.statphys.detrending.methods.IDetrendingMethod;

import java.io.File;
import java.text.DecimalFormat;
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
    
  
    
    // nur in dem Fall auch eine DFA Kurve zeigen
    static boolean firstInLoop = false;
    static int numberOfLoop = 0;
    static int numberOfBeta = 0;
 
    static int order = -1; // is defined in method: getRandomRow() ...
    
    
    /**
     *   Parameters with influence on RUNTIME and PERFORMANCE
     */
    static public int nrOfSValues = 250;
    
    static int EXP = 10; // 2 ^ EXP = length of the row                     //  <--------

    public static void main(String args[]) throws Exception {
        
        System.out.println( "*** LongTermCorrelationSeriesGenerator *** " );
        
        boolean showTest = true;

        RNGWrapper.init();

        // prepare some data structures
        tests = new Vector<TimeSeriesObject>();
        log = new StringBuffer();
                
        log.append("fit range: [" + fitMIN + ", ..., " + fitMAX + " ]\n" );
        
//        int Z = 15;
//        double STRETCH = 4;   // PRODUCT muss 60 sein ...

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
                
                TimeSeriesObject mr = getRandomRow( (int)Math.pow(2, EXP), beta , showTest, true );
            }    
        }
        
        firstInLoop = true;
        TimeSeriesObject mr = getRandomRow( (int)Math.pow(2, EXP), 0 , showTest, true );
        
        boolean showLegend = true;
        MultiChart.open(tests, "Fluctuation Function F(s): DFA " + order, 
                        "log(s)", "log(F(s))", false, log.toString(), null );
        
        
        alphasCALC.setLabel("CALC");
        alphasTHEO.setLabel("Theorie");
        // check.add( alphasCALC );
       // check.add( alphasTHEO );
        
        _calcErrorChart( alphas, numberOfBeta, numberOfLoop, 100.0 );
        
//        MultiChart.open(check, "alpha vs. beta", 
//                        "beta", "alpha", showLegend );

                MultiChart.open(check, "alpha vs. beta", 
                        "beta", "alpha", showLegend, log.toString() , null);


        
        String a = "/Users/kamir/Documents/THESIS/dissertationFINAL/main/FINAL/LATEX/semanpix/RandomNumbers/";
        
        File f = new File( a );
        if ( !f.exists() ) f.mkdirs();
        
        File f1 = new File( a + "random_numbers_CHECK.csv" );
        File f2 = new File( a + "random_numbers_FS.csv" );
        
        MesswertTabelle mwt = new MesswertTabelle( "random_numbers_CHECK" );
       
        mwt.singleX = false;
        mwt.setMessReihen( check );
        mwt.setHeader("# random_numbers_CHECK");
        mwt.writeToFile( f1 );
        
        
        
        
        mwt = new MesswertTabelle( "random_numbers_FS" );
        
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

        // nun wird das Array mit den Daten der ZR übergeben
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
            // Anzahl der Werte in der Zeitreihe
            dfa.setNrOfValues(N);

            // die Werte für die Fensterbreiten sind zu wählen ...
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
     * is generated with Fourier Filtering.
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

        // nun wird das Array mit den Daten der ZR übergeben
        TimeSeriesObjectFFT mr4_NEW = TimeSeriesObjectFFT.convertToMessreiheFFT(d4);
        
        TimeSeriesObjectFFT temp = mr4_NEW.getModifiedTimeSeries_FourierFiltered( beta );
        
        if ( beta == 0 ) temp = mr4_NEW;

        if ( runDFA ) {

            Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();
            Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
            vr.add( d4 );

            zr = d4.getData()[1];

            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
            order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues( nrOfSValues );

            System.out.println( "nrOfSValues="+nrOfSValues );
            // Anzahl der Werte in der Zeitreihe
            dfa.setNrOfValues(N);

            // die Werte für die Fensterbreiten sind zu wählen ...
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
     * 
     * @param length
     * @param beta
     * @param showTest
     * @return
     * @throws Exception 
     */
    public static TimeSeriesObject getRandomRow(int length, double beta, boolean showTest, boolean runDFA ) throws Exception {
        
        // has to be of type int
        // N = length; by a multiple of 4 !!!
        int N = length;
        
        
        System.out.println( "N=" + N );
        /**
         * Vorbedingungen:
         *
         * Eine Zeitreihe mit bereinigten Werten, ohne Lücken, ausser
         * es hat einen fahlichen Sinn, Lücken zu nutzen.
         */
        
        TimeSeriesObject d4 = TimeSeriesObject.getGaussianDistribution(N);
        
        return getRandomRow(d4, beta, showTest, runDFA);
    }  
    
    static TimeSeriesObject mrAV = new TimeSeriesObject();  // Mittelwerte
    static TimeSeriesObject mrSTD = new TimeSeriesObject();  // standardabweichung
    static TimeSeriesObject mrMAX = new TimeSeriesObject();  // ERROR
    static TimeSeriesObject mrMIN = new TimeSeriesObject();  // ERROR
        
    /**
     * Helper function to produce a debugging chart.
     */ 
    private static void _calcErrorChart(double[][] d, int nrB, int nrLOOPS , double scaleSTDEV ) {
        
        mrAV = new TimeSeriesObject();  // Mittelwerte
        mrSTD = new TimeSeriesObject();  // standardabweichung
        mrMAX = new TimeSeriesObject();  // ERROR
        mrMIN = new TimeSeriesObject();  // ERROR
    
        mrAV.setLabel("mean");
        mrSTD.setLabel("stddev * " + scaleSTDEV );
        mrMAX.setLabel("upper");
        mrMIN.setLabel("lower");
        
        for( int iB = 0; iB < nrB; iB++ ) { 
            
            double[] L = new double[nrLOOPS];
            
            // werte umlagern
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
