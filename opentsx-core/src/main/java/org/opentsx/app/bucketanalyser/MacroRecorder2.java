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

import org.opentsx.generators.TSGeneratorFINAL;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.BucketLoader;
import org.opentsx.core.TSBucket;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Vector;

/**
 * Produce Images for chapter 7. 
 * 
 * @author kamir
 */
public class MacroRecorder2 {

    public static String label_of_TRACK = "EXP4";
    
    public static String loadOp = "CACHE";
  
    /**
     * NO ARGUMENTS
     * 
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        //***********************************************************
        //
        // To build a time series with N components we need a container to hold 
        // components.
        //       int N = f.length;
        Vector<TimeSeriesObject> components = new Vector<TimeSeriesObject>();
            
        // DAX2, IPC, MDAX, SDAX, TECDAX  
        String market = "DAX2";
        // components = loadStockDataFromBucket( market );

        components = TSGeneratorFINAL.getSampleA();

        MacroTrackerFrame.init(label_of_TRACK);
        MacroTrackerFrame.addTransformation( 
                TSBucketTransformation.getTransformation("Collection", "Components", loadOp));
        
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
//
//        DFA ...
//        applyDFA(components, "RAW components  ");
//        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Components", "DFA_Components", "DFA_2"));
//
//        applyDFA(shuffledComponents, "SHUFFLED components   "); 
//        MacroTrackerFrame.addTransformation( TSBucketTransformation.getTransformation("Shuffled", "DFA_Shuffled_Components", "DFA_2"));

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



    
    
    
    


//    private static void calcTEandMI(TimeSeriesObject mr1, TimeSeriesObject mr2, int dig) throws Exception {
//
//        /**
//         * CALCULATE INFORMATION THEORETICAL MEASURES
//         *
//         */
//        String line = dig + "\t" + calcTransferEntropy( mr1 , mr2, dig ) + "\t" +
//                             JavaMI.Entropy.calculateEntropy(mr1.getYData()) + "\t" +
//                             JavaMI.Entropy.calculateEntropy(mr2.getYData()) + "\t" +
//                             calcMI_JIDT( mr1.getYData() , mr2.getYData() ) + "\t" +
//                             JavaMI.MutualInformation.calculateMutualInformation( mr1.getYData() , mr2.getYData() );
//
//        /**
//         * CALCULATE CROSSCORRELATION ...
//         */
//        PearsonsCorrelation pc = new PearsonsCorrelation();
//        double cc = pc.correlation( mr1.getYData(), mr2.getYData() );   // no timelag
//        line = line.concat("\t" + cc);
//
//
//        if ( SCATTERPLOT ) {
//
//            /**
//             * SHOW A SCATERPLOT
//             */
//            Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
//            v.add( mr1.zip( mr2 ) );
//
//            // SET SCALES to MIN and MAX from TimeSeriesObject ...
//            MyXYPlot.setXRange( mr1 );
//            MyXYPlot.setYRange( mr2 );
//
//            MyXYPlot plot = MyXYPlot.openAndGet( v, mr1.getLabel() + "_vs_" + mr2.getLabel() ,mr1.getLabel(), mr2.getLabel(), true);
//
//            if ( SCATTERPLOT_AUTO_SAVE ) {
////                plot.fileControlerPanel.save();
//            }
//        }
//
//        // CLEAN RESULTLINE
//        System.out.println( line.replace('.', ',') );
//
//    }

 
    private static Vector<TimeSeriesObject> loadStockDataFromBucket(String market) throws IOException {

        String folder = "/TSBASE/EXP1/";
        String fn = "Components_" + market + "_Close__2003_2004_2005_2006_2007_2008_2009_2010_2011_2012_2013_2014.tsb.vec.seq";
        
        File f = new File( fn );
        if ( !f.exists() ) {
            
            TSBucket.useHDFS = false;
            BucketLoader loader = new BucketLoader();
            
            loader.loadBucketData(folder + fn);
             
            return loader.getBucketData();
             
        } 
        else {
       
            return null;
            
        }
        
    }

}
