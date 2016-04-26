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
package org.apache.hadoopts.app.bucketanalyser;

        // Requires the following imports before the class definition:
import org.apache.hadoopts.app.thesis.FFTPhaseRandomizer;
import org.apache.hadoopts.app.thesis.TSGenerator;
import infodynamics.measures.discrete.TransferEntropyCalculatorDiscrete;
import infodynamics.utils.MatrixUtils;
import infodynamics.utils.RandomGenerator;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.chart.simple.MyXYPlot;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.data.series.MessreiheFFT;
import org.apache.hadoopts.hadoopts.loader.StockDataLoader2;
import infodynamics.measures.continuous.MutualInfoCalculatorMultiVariate;
import infodynamics.measures.mixed.gaussian.MutualInfoCalculatorMultiVariateWithDiscreteGaussian;
import infodynamics.utils.ArrayFileReader;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Vector;
import org.apache.commons.math3.stat.regression.SimpleRegression; 

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation; 
import org.apache.hadoopts.hadoopts.buckets.BucketLoader;
import org.apache.hadoopts.hadoopts.core.TSBucket;

import org.apache.hadoopts.statphys.detrending.DetrendingMethodFactory;
import org.apache.hadoopts.statphys.detrending.methods.IDetrendingMethod; 

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
        Vector<Messreihe> components = new Vector<Messreihe>();
            
        // DAX2, IPC, MDAX, SDAX, TECDAX  
        String market = "DAX2";
        components = loadStockDataFromBucket( market ); 
        
        MacroTrackerFrame.init(label_of_TRACK);
        MacroTrackerFrame.addTransformation( 
                TSBucketTransformation.getTransformation("Collection", "Components", loadOp));
        
        MultiChart.open(components, true, "Components");
        
        // Now we shuffle the values and calc the Entropy per row again.        
        // shuffeling should not change the Entropy
        Vector<Messreihe> shuffledComponents = new Vector<Messreihe>();
        
        int windowSize = 20;
        int shuffles = 1;
        
        // Here we calculate the Entropy for a sliding Window ...
        // in unshuffled data
        Vector<Messreihe> slidingWindowResults = new Vector<Messreihe>();
        for( Messreihe comp : components ) {
              
            Messreihe mm = comp.copy();
            
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

    private static void applyDFA(Vector<Messreihe> mrv, String label) throws Exception {

        if ( !doDFA ) return;
        
        int nrOfSValues = 250;
        int order = 0;

        Vector<Messreihe> v = new Vector<Messreihe>();

        for (Messreihe d4 : mrv) {

            int N = d4.yValues.size();
            double[] zr = new double[N];

            Vector<Messreihe> vr = new Vector<Messreihe>();

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

            Messreihe mr4 = dfa.getResultsMRLogLog();
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

    public static double calcTransferEntropy(Messreihe mra, Messreihe mrb, int le) {

// Prepare to generate some random normalised data.
        int numObservations = mra.getYData().length;

        int numDiscreteLevels = le;

        double[] sourceArray = mra.getYData();
        double[] destArray = mrb.getYData();

// Discretize or bin the data -- one could also call:
//  MatrixUtils.discretiseMaxEntropy for a maximum entropy binning
        int[] binnedSource = MatrixUtils.discretise(sourceArray, numDiscreteLevels);
        int[] binnedDest = MatrixUtils.discretise(destArray, numDiscreteLevels);

// Create a TE calculator and run it:
        TransferEntropyCalculatorDiscrete teCalc
                = new TransferEntropyCalculatorDiscrete(numDiscreteLevels, 1);
        teCalc.initialise();
        teCalc.addObservations(binnedSource, binnedDest);
        double result = teCalc.computeAverageLocalOfObservations();
// Calculation will be heavily biased because of the binning,
//  and the small number of samples
//        System.out.printf("TE result %.4f bits;\n",
//                result);
//        
        return result;
    }


    
    
    
    
    public static double calcMI_JIDT(double[] univariateSeries1,double[] univariateSeries2) throws Exception {
		

		
//                //  c. Pull out the columns from the data set which 
//		//     correspond to the univariate and joint variables we will work with:
//		//     First the univariate series to compute standard MI between: 
//		int univariateSeries1Column = props.getIntProperty("univariateSeries1Column");
//		int univariateSeries2Column = props.getIntProperty("univariateSeries2Column");
//		double[] univariateSeries1 = MatrixUtils.selectColumn(data, univariateSeries1Column);
//		double[] univariateSeries2 = MatrixUtils.selectColumn(data, univariateSeries2Column);
//		//     Next the multivariate series to compute joint or multivariate MI between: 
//		int[] jointVariable1Columns = props.getIntArrayProperty("jointVariable1Columns");
//		int[] jointVariable2Columns = props.getIntArrayProperty("jointVariable2Columns");
//		double[][] jointVariable1 = MatrixUtils.selectColumns(data, jointVariable1Columns);
//		double[][] jointVariable2 = MatrixUtils.selectColumns(data, jointVariable2Columns);
//		
		// 1. Create a reference for our calculator as
		//  an object implementing the interface type:
		MutualInfoCalculatorMultiVariate miCalc;
		
		// 2. Define the name of the class to be instantiated here:
		String implementingClass = "infodynamics.measures.continuous.kraskov.MutualInfoCalculatorMultiVariateKraskov1";

//# Note that one could use any of the following calculators (try them all!):
//#  implementingClass = infodynamics.measures.continuous.kraskov.MutualInfoCalculatorMultiVariateKraskov1
//#  implementingClass = infodynamics.measures.continuous.kernel.MutualInfoCalculatorMultiVariateKernel
//#  implementingClass = infodynamics.measures.continuous.gaussian.MutualInfoCalculatorMultiVariateGaussian                        
                        
		// 3. Dynamically instantiate an object of the given class:
		//  Part 1: Class.forName(implementingClass) grabs a reference to
		//   the class named by implementingClass.
		//  Part 2: .newInstance() creates an object instance of that class.
		//  Part 3: (MutualInfoCalculatorMultiVariate) casts the return
		//   object into an instance of our generic interface type.
		miCalc = (MutualInfoCalculatorMultiVariate)
				Class.forName(implementingClass).newInstance();
		
		// 4. Start using our MI calculator, paying attention to only
		//  call common methods defined in the interface type, not methods
		//  only defined in a given implementation class.
		// a. Initialise the calculator for a univariate calculation:
		miCalc.initialise(1, 1);
		// b. Supply the observations to compute the PDFs from:
		miCalc.setObservations(univariateSeries1, univariateSeries2);
		// c. Make the MI calculation:
		double miUnivariateValue = miCalc.computeAverageLocalOfObservations();

//		System.out.printf("MI calculator %s computed the univariate MI() as %.5f \n",
//				implementingClass,
//				miUnivariateValue);
                
                return miUnivariateValue;
	}

    private static void calcTEandMI(Messreihe mr1, Messreihe mr2, int dig) throws Exception {
        
        /**
         * CALCULATE INFORMATION THEORETICAL MEASURES
         * 
         */
        String line = dig + "\t" + calcTransferEntropy( mr1 , mr2, dig ) + "\t" +
                             JavaMI.Entropy.calculateEntropy(mr1.getYData()) + "\t" +
                             JavaMI.Entropy.calculateEntropy(mr2.getYData()) + "\t" +
                             calcMI_JIDT( mr1.getYData() , mr2.getYData() ) + "\t" + 
                             JavaMI.MutualInformation.calculateMutualInformation( mr1.getYData() , mr2.getYData() );

        /**
         * CALCULATE CROSSCORRELATION ...
         */
        PearsonsCorrelation pc = new PearsonsCorrelation();
        double cc = pc.correlation( mr1.getYData(), mr2.getYData() );   // no timelag
        line = line.concat("\t" + cc);

        
        if ( SCATTERPLOT ) {
        
            /**
             * SHOW A SCATERPLOT
             */
            Vector<Messreihe> v = new Vector<Messreihe>();
            v.add( mr1.zip( mr2 ) );

            // SET SCALES to MIN and MAX from Messreihe ...
            MyXYPlot.setXRange( mr1 );
            MyXYPlot.setYRange( mr2 );

            MyXYPlot plot = MyXYPlot.openAndGet( v, mr1.getLabel() + "_vs_" + mr2.getLabel() ,mr1.getLabel(), mr2.getLabel(), true);
        
            if ( SCATTERPLOT_AUTO_SAVE ) {
//                plot.fileControlerPanel.save();
            }
        }
        
        // CLEAN RESULTLINE
        System.out.println( line.replace('.', ',') );

    }

 
    private static Vector<Messreihe> loadStockDataFromBucket(String market) throws IOException {

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
