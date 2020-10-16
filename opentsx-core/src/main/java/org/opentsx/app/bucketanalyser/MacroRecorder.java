/**
 * This functional test uses the OSZI class to produce the images
 * and raw data for a publishabel PIXNODE.
 *
 * PIXNODEs are introduced in the context of writing Mirko's thesis.
 *
 * First, a set of time series (a mix of different frequncies, ampitudes and
 * phases) is created and superposed. In a second step, we use different 
 * noise levels and to disturb the signal which was a clear sine-wave.
 *
 * Univariate time series analysis is applied to illustrate properties
 * and measures obtained from the data.
 * 
 * We compare the fluctuation function F(s) obtained by DFA for several 
 * series with and without additonal shuffeling. 
 *  
 * For the combined time series we compare the influence of 
 * PHASE TRANSFORMATIINS and compare with simple shuffeling.
 * 
 * Finally we look into the entropy of individual time series.
 * 
 * Questions:
 * 
 * Is entropy changing as a function of length? => Would impact sliding windows.
 *
 * 
 * 
 * Next, we apply Multi-Variate analysis, such as calculation of TE and MI.
 * 
 * Is TE transfer entropy removed by shuffeling?
 *  TE(mr,mr) = 0
 *  TE(mr,shuffled(mr) > 0 but depends not on number of shufflings.
 *
 *  TE(a,b) = X
 *  TE(shuffled(a), shuffled(b)) should be higher than X.
 * 
 * APPLY this algorithms to snippets of TS of variable length.
 *
 * - How is Entropy changing over time during the BURST? 
 * - Is Entropy in link strength distribution also changing During this time?
 *
 */
package org.opentsx.app.bucketanalyser;


import org.apache.commons.math3.stat.regression.SimpleRegression;

import org.opentsx.generators.FFTPhaseRandomizer;
import org.opentsx.generators.TSGenerator;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Vector;

/**
 * Produce Images for chapter 7. 
 * 
 * @author kamir
 */
public class MacroRecorder {

    public static String label_of_TRACK = "EXP4";
    public static String loadOp = "CACHE";
    
    public static double samplingRate = 1000;
        
    /**
     * NO ARGUMENTS
     * 
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        
        // Paramters for test data generation ...
        double PI = Math.PI;
        
//        // EXP 1
//        label_of_TRACK = "EXP1";
//        double[] f = {2, 3, 4, 6, 12, 24};  // frequency
//        double[] a = {1, 1, 1, 1, 1, 1};                  // amplitude 
//        double[] phase = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0};  // phase
//        double[] noise = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0};  // noise level

        // EXP 2
        label_of_TRACK = "EXP2";
        double[] f = {2, 2, 2, 6, 12, 24};  // frequency
        double[] a = {5, 3, 8, 2, 1, 5};                  // amplitude 
        double[] phase = {0.0, 0.5, 0.25, 0.0, 0.0, 0.0};   // phase
        double[] noise = {1.0, 0.5, 20.0, 0.0, 0.0, 0.0};  // noise level

//        // EXP 3
//        label_of_TRACK = "EXP3";
        samplingRate = 1.0 / (24 * 3600);
        
        doDFA = true;
        SCATTERPLOT = false;
        SCATTERPLOT_AUTO_SAVE = false;
        
        double time = 5;
        samplingRate = 1000;

        //***********************************************************
        //
        // To build a time series with N components we need a container to hold 
        // components.
        //       int N = f.length;
        Vector<TimeSeriesObject> components = new Vector<TimeSeriesObject>();
        
        // final series - additive superposition of components
        TimeSeriesObject mix = new TimeSeriesObject();
        mix.setLabel("mix");
        
        if ( !label_of_TRACK.equals( "EXP3") ) {
            /**
             * Run the generator for Sine-Waves and label the wave with its 
             * ENTROPY value.
             */
            for (int i = 0; i < f.length; i++) {
                TimeSeriesObject m = TSGenerator.getSinusWave(f[i], time, samplingRate, a[i], phase[i] * PI, noise[i]);
                m.labelWithEntropy();
                mix = mix.add(m);

                components.add(m.copy());
            }
            mix.labelWithEntropy();
            components.add(mix);
        }
        
        if ( label_of_TRACK.equals( "EXP3") ) {
            
            //components = loadStockData();
            
        }

        if ( label_of_TRACK.equals( "EXP4") ) {
            
            components = loadStockDataFromBucket(); 
            
        }

        
        
        MacroTrackerFrame.init(label_of_TRACK);
        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Collection", "Components", loadOp));
        
        
        
        MultiChart.open(components, true, "Components");
        
        // Now we shuffle the values and calc the Entropy per row again.        
        // shuffeling should not change the Entropy
        Vector<TimeSeriesObject> shuffledComponents = new Vector<TimeSeriesObject>();
        
        int windowSize = 20;
        int shuffles = 1;
        
        // Here we calculate the Entropy for a sliding Window ...
        // in unshuffled data
        Vector<TimeSeriesObject> slidingWindowResults = new Vector<TimeSeriesObject>();
        for( TimeSeriesObject comp : components ) {
              
            TimeSeriesObject mm = comp.copy();
            
            mm.shuffleYValues(shuffles);
            mm.labelWithEntropy();
            
            shuffledComponents.add(mm);
            
            slidingWindowResults.add(comp.calcEntropyForWindow(windowSize));
            
        }
        
        MultiChart.open(shuffledComponents, true, "Shuffled");
        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Components", "Shuffled", "shuffleYValues(" + shuffles + ")"));

//        MultiChart.open(slidingWindowResults, true, "H for sliding window ("+windowSize+")");
//        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Components", "Entropy", "H for sliding window ("+windowSize+")"));

        // DFA ...
//        applyDFA(components, "RAW components  ");
//        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Components", "DFA_Components", "DFA_2"));
//
//        applyDFA(shuffledComponents, "SHUFFLED components   "); 
//        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Shuffled", "DFA_Shuffled_Components", "DFA_2"));

        int dig = 10;
        
        // Analyse the Transfer Entropy and Mutual Information
//        System.out.println( "BINS;JIDT.TE (mr1,mr2,5);JavaMI.Entropy(mr1);JavaMI.Entropy(mr2);JIDT.MI(mr1,mr2);JavaMI.MI(mr1,mr2),ACM3.cc(mr1,mr2)");
//        calcTEandMI(components.elementAt(0) , mix , dig);
//        calcTEandMI(components.elementAt(1) , mix , dig);
//        calcTEandMI(components.elementAt(2) , mix , dig);
//        calcTEandMI(components.elementAt(3) , mix , dig);
//        calcTEandMI(components.elementAt(4) , mix , dig);
//        calcTEandMI(components.elementAt(5) , mix , dig);
//        calcTEandMI(mix , mix , dig);
        
        // How is TE between shuffled and raw time series 
        // dependent on nr of shuffelings?
        //
        //   => Shuffeling is not changing the values for
        //      one particular series, but the pairs are changed by
        //      shuffeling, so we see a fluctuation of TE for the pairs.
        // 
        Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();
        Vector<TimeSeriesObject> mrv3 = new Vector<TimeSeriesObject>();
        
        TimeSeriesObject mrTE_i_SIMPLE = new TimeSeriesObject();
        TimeSeriesObject mrTE_i_PHASE_RANDOMISATION = new TimeSeriesObject();
        
        mrv3.add( mrTE_i_SIMPLE );
        mrv3.add( mrTE_i_PHASE_RANDOMISATION );
        
        int i = 1;
//        for( int i = 0; i < 1000; i = i + 1 ) {
//          
        
            // simple randomisation
            TimeSeriesObject m2 = mix.copy();
            m2.shuffleYValues(i);
            
            // phase manipulation
            TimeSeriesObject m3 = FFTPhaseRandomizer.getPhaseRandomizedRow(mix.copy(), false, false, 0, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value);
            TimeSeriesObject m4 = FFTPhaseRandomizer.getPhaseRandomizedRow(mix.copy(), false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);

            m2.setLabel("shuffled_SIMPLE(" + i + ")");
            m3.setLabel("shuffled_PHASERANDOM_MULTIPLY(" + i + ")");
            m4.setLabel("shuffled_PHASERANDOM_SHUFFLE(" + i + ")");
            
            m2.labelWithEntropy("m2");
            m3.labelWithEntropy("m3");
            m4.labelWithEntropy("m4");
            
            mrv2.add(mix);
            mrv2.add(m2);
            mrv2.add(m3);
            mrv2.add(m4);
            
            
////            MultiChart.open(mrv2, true, "Randomized Example");
//            MacroTrackerFrame.addSource( TSBucketTransformation.getTransformation("SAMPLES", "Samples", "extract"));
//            MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("SAMPLES", "Randomized Series",  "PHASE TRANSFORMATION"));
//
//            applyDFA(mrv2, "<<< RAW, Shuffled, PHRand.MULTIPLY, PHRand.SHUFFLE >>> "); 
//            MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Randomized Series", "DFA_Samples", "DFA_2"));
//            
//            System.out.println();
//            System.out.println("mr and m2");
//            
//            calcTEandMI(mix , m2 , dig);
//            calcTEandMI(m2 , mix , dig);
//            calcTEandMI( m2 , m2 , dig);
//            calcTEandMI(mix , mix , dig);

//            calcTEandMI(mix , m3 , dig);
//            calcTEandMI(m3 , mix , dig);
//            calcTEandMI( m3 , m3 , dig);
//            
//            calcTEandMI(mix , m4 , dig);
//            calcTEandMI(m4 , mix , dig);
//            calcTEandMI( m4 , m4 , dig);
            
//            // VARIATION OF LENGTH ...
//            for ( i = 1; i < 5; i++ ) {
//
//                System.out.println("\n\n"+(i*1000)+"\n");
//    
//                TimeSeriesObject mA = mrv.elementAt(0).cutFromStart( i * 1000 );
//                TimeSeriesObject mB = mrv.elementAt(1).cutFromStart( i * 1000 );
//                TimeSeriesObject mC = mrv.elementAt(2).cutFromStart( i * 1000 );
//                TimeSeriesObject mD = mrv.elementAt(3).cutFromStart( i * 1000 );
//                TimeSeriesObject mE = mrv.elementAt(4).cutFromStart( i * 1000 );
//                TimeSeriesObject mF = mrv.elementAt(5).cutFromStart( i * 1000 );
//                TimeSeriesObject mG = mr.cutFromStart( i * 1000 );
//
//                calcTEandMI( mA , mB , dig);
//                calcTEandMI( mA , mC , dig);
//                calcTEandMI( mA , mD , dig);
//                calcTEandMI( mA , mE , dig);
//                calcTEandMI( mA , mF , dig);
//                calcTEandMI( mA , mG , dig);
//                
//            }

    }

    static boolean doDFA = false;
    static boolean SCATTERPLOT = false;
    static boolean SCATTERPLOT_AUTO_SAVE = false;
    
    public static double fitMIN = 1.2;
    public static double fitMAX = 3.5;

    private static void applyDFA(Vector<TimeSeriesObject> mrv, String label) throws Exception {

        if ( !doDFA ) return;
        
        int nrOfSValues = 250;
        int order = 0;


        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();

        for (TimeSeriesObject d4 : mrv) {

            int N = d4.yValues.size();
            double[] zr = new double[N];

            Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();

            vr.add(d4);

            zr = d4.getData()[1];

            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
            order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues(nrOfSValues);

            // Anzahl der Werte in der Zeitreihe
            dfa.setNrOfValues(N);

            // die Werte für die Fensterbreiten sind zu wählen ...
            //dfa.initIntervalS();
            dfa.initIntervalSlog();

            dfa.showS();

            // http://stackoverflow.com/questions/12049407/build-sample-data-for-apache-commons-fast-fourier-transform-algorithm
            dfa.setZR(d4.getData()[1]);

            dfa.calc();

            TimeSeriesObject mr4 = dfa.getResultsMRLogLog();
            mr4.setLabel(d4.getLabel());
            v.add(mr4);

            String status = dfa.getStatus();

            SimpleRegression alphaSR = mr4.linFit(fitMIN, fitMAX);

            double alpha = alphaSR.getSlope();

            System.out.println(status);
        }

        if (true) {
            DecimalFormat df = new DecimalFormat("0.000");
            MultiChart.open(v, label + " fluctuation function F(s) [order:" + order + "] ", "log(s)", "log(F(s))", true, "???", null);

//                System.out.println(" alpha = " + df.format(alpha));
//                System.out.println("       = " + ((2 * alpha) - 1.0));
        }
    }


    
    



    /**
     *

    private static Vector<TimeSeriesObject> loadStockData() {
        
        int [] YEARS = {2013,2014};
        
        return StockDataLoader2.concatRowsForYearsFromCache( YEARS );
        
    }

     *
     **/

    private static Vector<TimeSeriesObject> loadStockDataFromBucket() {

        String fn = "Components_IPC_Close__2003_2004_2005_2006_2007_2008_2009_2010_2011_2012_2013_2014.tsb.vec.seq";
        File f = new File( fn );
        if ( !f.exists() ) {
            
            
        } 
        else {
            
        }
        
        return null;
    }

}
