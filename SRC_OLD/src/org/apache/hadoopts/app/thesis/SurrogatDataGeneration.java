/**
 * This functional test uses the "OSZI" class to produce the images and raw data
 * for a publishabel PIXNOD (used in chapter 5).
 *
 * PIXNODEs are introduced in the context of writing Mirko's thesis.
 *
 * First, a set of time series (a mix of different frequncies, ampitudes and
 * phases) is created and superposed. In a second step, we use different noise
 * levels and to disturb the signal which was a clear sine-wave.
 *
 * Univariate time series analysis is applied to illustrate properties and
 * measures obtained from the data.
 *
 * We compare the fluctuation function F(s) obtained by DFA for several series
 * with and without additonal shuffeling.
 *
 * For the combined time series we compare the influence of PHASE
 * TRANSFORMATIINS and compare with simple shuffeling.
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
 * Is TE transfer entropy removed by shuffeling? TE(mr,mr) = 0
 * TE(mr,shuffled(mr) > 0 but depends not on number of shufflings.
 *
 * TE(a,b) = X TE(shuffled(a), shuffled(b)) should be higher than X.
 *
 * APPLY this algorithms to snippets of TS of variable length.
 *
 * - How is Entropy changing over time during the BURST? - Is Entropy in link
 * strength distribution also changing During this time?
 *
 */
package org.apache.hadoopts.app.thesis;

// Requires the following imports before the class definition:
import org.apache.hadoopts.app.bucketanalyser.MacroTrackerFrame;
import org.apache.hadoopts.app.bucketanalyser.TSOperationControlerPanel;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.chart.simple.MyXYPlot;
import org.apache.hadoopts.data.series.Messreihe;

import java.text.DecimalFormat;
import java.util.Vector;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

import org.apache.hadoopts.statphys.detrending.DetrendingMethodFactory;
import org.apache.hadoopts.statphys.detrending.methods.IDetrendingMethod;
import org.apache.hadoopts.app.thesis.experimental.ContextRecorder;

/**
 * Produce Images for chapter 5.
 *
 * @author kamir
 */
public class SurrogatDataGeneration {

    public static String label_of_EXPERIMENT = "SurrogatData";
    public static String subLabel = "EXP10";

    public static double samplingRate = 1000;

    public static void main(String[] args) throws Exception {

        // Paramters for test data generation ...
        double PI = Math.PI;

        double[] f     = {2  , 30  , 40  , 150  };  // frequency
        double[] a     = {1  , 1  , 1  , 1  };                  // amplitude 
        double[] phase = {0.0, 0.0, 0.0, 0.0};  // phase
        double[] noise = {0.0, 0.0, 0.2, 0.2};  // noise level
        
        boolean doShuffle = false;

        /**
         *  Track parameters and prepare results ...
         */
        MacroTrackerFrame.init( label_of_EXPERIMENT );

        ContextRecorder.setSubLabel(subLabel);
        
        TSOperationControlerPanel.label_of_EXPERIMENT = label_of_EXPERIMENT + "_" + subLabel;
        
        ContextRecorder.recordParameters("f", f);
        ContextRecorder.recordParameters("a", a);
        ContextRecorder.recordParameters("phase", phase);
        ContextRecorder.recordParameters("noise", noise);
        ContextRecorder.resetSubLabel();

//        // EXP 2
//        label_of_EXPERIMENT = "EXP2";
//        double[] f = {2, 2, 2, 6, 12, 24};  // frequency
//        double[] a = {5, 3, 8, 2, 1, 5};                  // amplitude 
//        double[] phase = {0.0, 0.5, 0.25, 0.0, 0.0, 0.0};   // phase
//        double[] noise = {1.0, 0.5, 20.0, 0.0, 0.0, 0.0};  // noise level
//        // EXP 3
//        label_of_EXPERIMENT = "EXP3";
//        samplingRate = 1.0 / (24 * 3600);
        
        doDFA = true;
        
        SCATTERPLOT = false;
        SCATTERPLOT_AUTO_SAVE = false;

        double time = 10;
        samplingRate = 1000;

        //***********************************************************
        //
        // To build a time series with N components we need a container to hold 
        // components.
        //       int N = f.length;
        Vector<Messreihe> components = new Vector<Messreihe>();

        // final series - additive superposition of components
        Messreihe mix = new Messreihe();
        mix.setLabel("mix");
 
            /**
             * Run the generator for Sine-Waves and label the wave with its
             * ENTROPY value.
             */
            for (int i = 0; i < f.length; i++) {
                Messreihe m = TSGenerator.getSinusWave(f[i], time, samplingRate, a[i], phase[i] * PI, noise[i]);
                m.labelWithEntropy();
                mix = mix.add(m);

                components.add(m.copy());
            }
            mix.labelWithEntropy();
            components.add(mix); 
   
        int N = mix.yValues.size();

        MultiChart.open(components, true, "Components");

        // Now we shuffle the values and calc the Entropy per row again.        
        // shuffeling should not change the Entropy
        Vector<Messreihe> shuffledComponents = new Vector<Messreihe>();

        int windowSize = 20;
        int shuffles = 1;

        // Here we calculate the Entropy for a sliding Window ...
        // in unshuffled data
        Vector<Messreihe> slidingWindowResults = new Vector<Messreihe>();
        for (Messreihe comp : components) {

            Messreihe mm = comp.copy();

            mm.shuffleYValues(shuffles);
            mm.labelWithEntropy();

            shuffledComponents.add(mm);

//            slidingWindowResults.add(comp.calcEntropyForWindow(windowSize));
        }
//        MultiChart.open(shuffledComponents, true, "Shuffeled (N=" + shuffles + ")");
//        MultiChart.open(slidingWindowResults, true, "H for sliding window ("+windowSize+")");
        


        // DFA ...
//        applyDFA(components, "RAW components  ");
//        applyDFA(shuffledComponents, "SHUFFLED components   ");

        int dig = 10;

        // Analyse the Transfer Entropy and Mutual Information
//        System.out.println("BINS;JIDT.TE (mr1,mr2,5);JavaMI.Entropy(mr1);JavaMI.Entropy(mr2);JIDT.MI(mr1,mr2);JavaMI.MI(mr1,mr2),ACM3.cc(mr1,mr2)");
//        calcTEandMI(components.elementAt(0) , mix , dig);
//        calcTEandMI(components.elementAt(1) , mix , dig);
//        calcTEandMI(components.elementAt(2) , mix , dig);

        Messreihe mrPROBE1 = new Messreihe();
        Messreihe mrPROBE2 = new Messreihe();
        mrPROBE1.setLabel("MI as a function ot length");
        mrPROBE2.setLabel("CC as a function ot length");
        Vector<Messreihe> prob = new Vector<Messreihe>();
        prob.add(mrPROBE1);
        prob.add(mrPROBE2);

        for (int i = 5; i < 6000; i = i + 1) {

            System.out.print(i + "\t");

            Vector<Messreihe> comp = new Vector<Messreihe>();

//            for (Messreihe m : components) {
            Messreihe mm = components.elementAt(0).cutOut(0, i);
            Messreihe mmm = components.elementAt(1).cutOut(0, i);

            if (doShuffle) {
                mm.shuffleYValues(1);
                mmm.shuffleYValues(1);
            }
            comp.add(mm);
            comp.add(mmm);

//                System.out.println( ">>> chop ... " + i );
//            }
            if (i == samplingRate) {
                SCATTERPLOT = true;
            } else {
                SCATTERPLOT = false;
            }

//            calcTEandMI(comp.elementAt(0), comp.elementAt(1), dig, "r0", "r1", mrPROBE1, mrPROBE2, i);
//            calcTEandMI(comp.elementAt(1), comp.elementAt(0), dig, "r1", "r0");
//            calcTEandMI(comp.elementAt(1), comp.elementAt(1), dig, "r1", "r1");
//            calcTEandMI(comp.elementAt(0), comp.elementAt(0), dig, "r0", "r0");

        }
//        
//        for (int i = 1000; i < 6000; i = i + 100) {
//
//            System.out.print( i + "\t" );
//
//            Vector<Messreihe> comp = new Vector<Messreihe>();
//
//            
////            for (Messreihe m : components) {
//
//                Messreihe mm = components.elementAt(0).cutFromStart(i);
//                Messreihe mmm = components.elementAt(1).cutFromStart(i);
//
//                comp.add(mm);
//                comp.add(mmm);
//                
////                System.out.println( ">>> chop ... " + i );
//
////            }
//            calcTEandMI(comp.elementAt(0), comp.elementAt(1), dig, "r0", "r1", mrPROBE1, mrPROBE2, i );
////            calcTEandMI(comp.elementAt(1), comp.elementAt(0), dig, "r1", "r0");
////            calcTEandMI(comp.elementAt(1), comp.elementAt(1), dig, "r1", "r1");
////            calcTEandMI(comp.elementAt(0), comp.elementAt(0), dig, "r0", "r0");
//            
//            if ( SCATTERPLOT == true ) SCATTERPLOT = false;
//
//
//        }

 //       MultiChart.open(prob, subLabel, "length", "MI and CC", true, ContextRecorder.sb.toString());

//        calcTEandMI(components.elementAt(3) , mix , dig);
//        calcTEandMI(components.elementAt(4) , mix , dig);
//        calcTEandMI(components.elementAt(5) , mix , dig);
//        calcTEandMI(mix , mix , dig);
        /**
         * SECTION 2
         */
        // How is TE between shuffled and raw time series 
        // dependent on nr of shuffelings?
        //
        //   => Shuffeling is not changing the values for
        //      one particular series, but the pairs are changed by
        //      shuffeling, so we see a fluctuation of TE for the pairs.
        // 
        Vector<Messreihe> mrv2 = new Vector<Messreihe>();
        Vector<Messreihe> mrv3 = new Vector<Messreihe>();

        Messreihe mrTE_i_SIMPLE = new Messreihe();
        Messreihe mrTE_i_PHASE_RANDOMISATION = new Messreihe();

        mrv3.add(mrTE_i_SIMPLE);
        mrv3.add(mrTE_i_PHASE_RANDOMISATION);

        int i = 10;
//        for( int i = 0; i < 1000; i = i + 1 ) {

        // simple randomisation
        Messreihe m2 = mix.copy();
        m2.shuffleYValues(i);

        // phase manipulation
//        Messreihe m3 = FFTPhaseRandomizer.getPhaseRandomizedRow(mix.copy(), false, false, 0, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value );
//        Messreihe m4 = FFTPhaseRandomizer.getPhaseRandomizedRow(mix.copy(), false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
        
        Messreihe m5 = LongTermCorrelationSeriesGenerator.getRandomRow(mix.copy(), -2.0, doDFA, doDFA);
        Messreihe m6 = LongTermCorrelationSeriesGenerator.getRandomRow(mix.copy(), -1.0, doDFA, doDFA);
        Messreihe m7 = LongTermCorrelationSeriesGenerator.getRandomRow(mix.copy(), 1.0, doDFA, doDFA);
        Messreihe m8 = LongTermCorrelationSeriesGenerator.getRandomRow(mix.copy(), 2.0, doDFA, doDFA);

//        Messreihe m5 = LongTermCorrelationSeriesGenerator.getRandomRow(m2.copy(), -2.0, doDFA, doDFA);
//        Messreihe m6 = LongTermCorrelationSeriesGenerator.getRandomRow(m2.copy(), -1.0, doDFA, doDFA);
//        Messreihe m7 = LongTermCorrelationSeriesGenerator.getRandomRow(m2.copy(), 1.0, doDFA, doDFA);
//        Messreihe m8 = LongTermCorrelationSeriesGenerator.getRandomRow(m2.copy(), 2.0, doDFA, doDFA);

        Messreihe m3 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(mix.copy(), doDFA, doDFA);
        Messreihe m4 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(mix.copy(), doDFA, doDFA);
        
        m2.setLabel("shuffled_SIMPLE(" + i + ")");
        m3.setLabel("shuffled_PHASERANDOM_MULTIPLY(" + i + ")");
        m4.setLabel("shuffled_PHASERANDOM_SHUFFLE(" + i + ")");

        m2.labelWithEntropy(" SHUFFLED( " + i + ")" );
        m3.labelWithEntropy(" PHASERANDOM_MULTIPLY ");
        m4.labelWithEntropy(" PHASERANDOM_SHUFFLE");



//        mrv2.add(mix);
        mrv2.add(m2);
        
//        mrv2.add(m3);
//        mrv2.add(m4);
        
        mrv2.add(m5);
        mrv2.add(m6);
        mrv2.add(m7);
        mrv2.add(m8);
        
//        mrv2.add(m5.normalizeToStdevIsOne());
//        mrv2.add(m6.normalizeToStdevIsOne());
//        mrv2.add(m7.normalizeToStdevIsOne());
//        mrv2.add(m8.normalizeToStdevIsOne());
        
       
        applyDFA(mrv2, "DFA for all generated TS");
 
        MultiChart.open(mrv2, "GENERATED DATA", "t", "f(t)", true, "", null);
        
        System.out.println();
        System.out.println("mr and m2");

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
//                Messreihe mA = mrv.elementAt(0).cutFromStart( i * 1000 );
//                Messreihe mB = mrv.elementAt(1).cutFromStart( i * 1000 );
//                Messreihe mC = mrv.elementAt(2).cutFromStart( i * 1000 );
//                Messreihe mD = mrv.elementAt(3).cutFromStart( i * 1000 );
//                Messreihe mE = mrv.elementAt(4).cutFromStart( i * 1000 );
//                Messreihe mF = mrv.elementAt(5).cutFromStart( i * 1000 );
//                Messreihe mG = mr.cutFromStart( i * 1000 );
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

    static boolean doDFA = true;
    static boolean SCATTERPLOT = false;
    static boolean SCATTERPLOT_AUTO_SAVE = false;

    public static double fitMIN = 1.2;
    public static double fitMAX = 3.5;

    private static void applyDFA(Vector<Messreihe> mrv, String label) throws Exception {

        if (!doDFA) {
            return;
        }

        int nrOfSValues = 250;
        int order = 0;

        Vector<Messreihe> v = new Vector<Messreihe>();
        
        int N = 0;
        double xMax = 15;
        double xMin = -15;
                
        for (Messreihe d4 : mrv) {

            N = d4.yValues.size();
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
            
            xMax = mr4.getMaxX();
            xMin = mr4.getMinX();

        }
                
        double m1 = 0.5;
        double n1 = 0.0;
        
        double m2 = 1.0;
        double n2 = 0.0;
        
        double dx = ( xMax - xMin ) / N;
        
        
        Messreihe ref1 = Messreihe.getLinearFunction( m1, n1, dx, xMin, N);
        Messreihe ref2 = Messreihe.getLinearFunction( m2, n2, dx, xMin, N);
        
        v.add( ref1 );
        v.add( ref2 );
        

//        if (true) {
            DecimalFormat df = new DecimalFormat("0.000");
            MultiChart.open(v, label + " fluctuation function F(s) [order:" + order + "] ", "log(s)", "log(F(s))", true, "???", null);

//                System.out.println(" alpha = " + df.format(alpha));
//                System.out.println("       = " + ((2 * alpha) - 1.0));
//        }
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

    public static double calcMI_JIDT(double[] univariateSeries1, double[] univariateSeries2) throws Exception {

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
        miCalc = (MutualInfoCalculatorMultiVariate) Class.forName(implementingClass).newInstance();

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

    static boolean singleDebug = true;

    private static void calcTEandMI(Messreihe mr1, Messreihe mr2, int dig, String l1, String l2, Messreihe row1, Messreihe row2, int x) throws Exception {

        mr1.normalize();
        mr2.normalize();

        Vector<Messreihe> vv = new Vector<Messreihe>();
        vv.add(mr1);
        vv.add(mr2);
        if (singleDebug) {
            MultiChart.open(vv);
            singleDebug = false;
        }

        double v1 = JavaMI.MutualInformation.calculateMutualInformation(mr1.getYData(), mr2.getYData());

        /**
         * CALCULATE INFORMATION THEORETICAL MEASURES
         *
         */
        String line = dig + "\t"
                //                + calcTransferEntropy(mr1, mr2, dig) + "\t"
                //                + JavaMI.Entropy.calculateEntropy(mr1.getYData()) + "\t"
                //                + JavaMI.Entropy.calculateEntropy(mr2.getYData()) + "\t"
                //                + calcMI_JIDT(mr1.getYData(), mr2.getYData()) + "\t"
                + v1;

        /**
         * CALCULATE CROSSCORRELATION ...
         */
        PearsonsCorrelation pc = new PearsonsCorrelation();
        double cc = pc.correlation(mr1.getYData(), mr2.getYData());   // no timelag
        line = line.concat("\t" + cc);

        row1.addValuePair(x, v1);
        row2.addValuePair(x, cc);

        if (SCATTERPLOT) {

            /**
             * SHOW A SCATERPLOT
             */
            Vector<Messreihe> v = new Vector<Messreihe>();
            v.add(mr1.zip(mr2));

            // SET SCALES to MIN and MAX from Messreihe ...
            MyXYPlot.setXRange(mr1);
            MyXYPlot.setYRange(mr2);

            MyXYPlot plot = MyXYPlot.openAndGet(v, l1 + "_vs_" + l2, mr1.getLabel(), mr2.getLabel(), true);

            if (SCATTERPLOT_AUTO_SAVE) {
//                plot.fileControlerPanel.save();
            }
        }

        // CLEAN RESULTLINE
        System.out.println(line.replace('.', ','));

    }
 

}
