package org.opentsx.algorithms.dataqualitytest;

import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.Vector;
import org.opentsx.algorithms.statistics.DistributionTester;


 
/**
 * THIS IS A STUB!!!! 
 * 
 * Has to be repaired in the future!
 * 
 * @author Mirko Kämpf
 */
public class InfluenceOfSinglePeakTester {
     public static boolean debug = false;
     
     // By default we use the cc value at tau=0
     public static int _mode = 0;
     
     public static double ts = 3.0;

     public static int NR_OF_ROWS = 25;
    
    public static final int mode_NORMALIZED = 0;     // A
    public static final int mode_CC_TAU_0 = 1;       // B
    public static final int mode_ADVANCED = 2;       // C
    public static final int mode_NORMALIZED_abs = 3; // D

//    /**
//     * PARAMETER FÃœR DIE AUSWAHL der Stelle in der CC-FUnktion
//     *
//     * Important for mode 1 !!!
//     *
//     * Depends on tau ...
//     *
//     */
//    public static int ID_TO_SELECT_CC_FROM = 21;
//
//    /**
//     * TimeSeriesObject mr : the cross-correlation function for a pair of time series
//     *
//     * @param mr
//     * @return
//     */
//    public static double _calcStrength(TimeSeriesObject mr) {
//        switch (_mode) {
//            case 0: {
//                return calcStrength_VERSION_A(mr);
//            }
//            case 1: {
//                return calcStrength_VERSION_B(mr);
//            }
//            case 2: {
//                return calcStrength_VERSION_C(mr);
//            }
//            case 3: {
//                return calcStrength_VERSION_D(mr);
//            }
//        }
//        return 0.0;
//    }
//
//    ;
//    
//    /**
//     * Calculates the linkstrength for Mode A.
//     * 
//     * @param mr
//     * @return
//     */
//    private static double calcStrength_VERSION_A(TimeSeriesObject mr) {
//
//        if (debug) {
//            System.out.println("Link Strength Version_A");
//        }
//
//        double stdDev = mr.getStddev();
//
//        double maxY = calcRealMaxY(mr);
//
//        mr.calcAverage();
//
//        double avY = mr.getAvarage();
//        double v = (maxY - avY) / stdDev;
//        if (debug) {
//            // System.out.println( mr.toString() );
//            System.out.println(mr.getLabel() + "\n\tstDev=" + stdDev
//                    + "\tmaxY=" + maxY + "\t<y>=" + avY + "\tstr=" + v);
//        }
//        return v;
//    }
//
//    ;
//
//    /**
//     * 
//     * Select the link strengt from a KreuzKorrelation object.
//     * 
//     * Uses:   ID_TO_SELECT_CC_FROM
//     * 
//     * @param mr
//     * @return 
//     */
//    private static double calcStrength_VERSION_B(TimeSeriesObject mr) {
//        if (debug) {
//            System.out.println("Version B");
//        }
//        double v = -10.0;
//        if (mr != null) {
//            if (debug) {
//                System.out.println("~~~ (" + mr + ")");
//            }
//            // tau = 0 => value aus der Mitte
//            v = (Double) mr.yValues.elementAt(ID_TO_SELECT_CC_FROM);
//            if (debug) {
//                //System.out.println( mr.toString() );
//                //System.out.println( mr.getLabel() + "\n\tstr=" + v );
//                // System.out.println( mr.yValues.size() + " \t " +   
//                //                     mr.yValues.elementAt(0) );
//                if (v != -1) {
//                    System.out.println("\tstr=" + v);
//                }
//            }
//        } else {
//            System.err.println("mr war mal wieder NULL ");
//        }
//        return v;
//    }
//
//    ;
//    
//        /**
//     * Compare the link strengt between the original row and the row form
//     * which the maximum peak is removed.
//     * 
//     * Uses the method: 
//     * 
//     *    calcStrength_VERSION_C(
//     *            TimeSeriesObject mr, double stdevA, double stdevB, double mwA, double mwB )
//     *  
//     * 
//     * and check results....
//     */ 
//    private static double calcStrength_VERSION_C(TimeSeriesObject kr) {
//
//        double maxYOriginal = kr.getMaxY();
//
//        TimeSeriesObject mr2 = kr.copy();
//        System.out.println(mr2.getStatisticData("<<<"));
//
//        mr2 = removeMaximumValueFromRow(mr2, 1);
//        System.out.println(mr2.getStatisticData(">>>"));
//
//        double maxYCleaned = mr2.getMaxY();
//
//        // mittelwert ermitteln mit dem Maximum
//        double mwA = kr.getAvarage2();
//        double stdevA = kr.getStddev();
//
//        // mittelwert ermitteln ohne dem Maximum
//        double mwB = mr2.getAvarage2();
//        double stdevB = mr2.getStddev();
//
//        // Effekt in den Daten
//        double v1 = (maxYOriginal - mwA) / stdevA;
//
//        double v2 = (maxYCleaned - mwB) / stdevB;
//
//        return v2 / v1;
//    }
//
//    /**
//     * Calculates the linkstrength for Mode D.
//     *
//     * @param mr
//     * @return
//     */
//    private static double calcStrength_VERSION_D(TimeSeriesObject mr) {
//
//        if (debug) {
//            System.out.println("Link Strength Version_D");
//        }
//
//        double stdDev = mr.getStddev();
//
//        double maxY = mr.getMaxY();
//
//        mr.calcAverage();
//
//        double avY = mr.getAvarage();
//        double v = (maxY - avY) / stdDev;
//        if (debug) {
//            // System.out.println( mr.toString() );
//            System.out.println(mr.getLabel() + "\n\tstDev=" + stdDev
//                    + "\tmaxY=" + maxY + "\t<y>=" + avY + "\tstr=" + v);
//        }
//        return v;
//    }
//
//    ;
//
//
//    /**
//     * prÃ¼ft ob "strength" > als Schwelle ist.
//     *  
//     * @param mr
//     * @param threshold
//     * @return
//     */
//    public static boolean sIsGreaterThanTS(TimeSeriesObject mr, double threshold) {
//        boolean b = false;
//        double v = _calcStrength(mr);
//        if (v > threshold) {
//            b = true;
//        }
//        return b;
//    }
//
//    ;
//
//    /**
//     * FÃ¼r eine Zeitreihe mit Werten aus einer Gauss-Verteilung
//     * wird der Einfluss einzelner Peaks auf die Link-StÃ¤rke untersucht.
//     *
//     * @param args
//     */
//    public static void main(String[] args) throws Exception {
//
//        run = _mode + "_" + run;
//        
//        boolean testA = true;
//        
//        boolean testB = false; // den verstehe ich nicht so richtig
//        
//        boolean testC = true;
//        
//        stdlib.StdRandom.initRandomGen(1);
//
//        InfluenceOfSinglePeakTester.debug = true;
//
//        if ( testA ) calcTestForSimulatedFluctuationFunction();
//
//        
//        if ( testB ) calcTestForSimulatedTS();
//        
//        
//        if ( testC ) calcShapiroValuesForPeaks();
//        
//
//    }
//
//    private static void calcTestForSimulatedFluctuationFunction() throws Exception {
//
//        // Wie groÃŸ ist die LINK-StÃ¤rke bei solch einem STÃ–R-Peak in 
//        // der CC-Function?
//
//        Vector<TimeSeriesObject> reihen_strength = new Vector<TimeSeriesObject>();
//
//        Vector<TimeSeriesObject> reihen = new Vector<TimeSeriesObject>();
//
//
//        
//        
//        for (int j = 0; j < 5; j++) {
//
//            Vector<TimeSeriesObject> t1reihen = new Vector<TimeSeriesObject>();
////            Vector<TimeSeriesObject> t2reihen = new Vector<TimeSeriesObject>();
//            Vector<TimeSeriesObject> t3reihen = new Vector<TimeSeriesObject>();
//            Vector<TimeSeriesObject> t4reihen = new Vector<TimeSeriesObject>();
//
//            double mw = j * 1.5;
//
//            for (int t = 0; t < NR_OF_ROWS; t++) {
//
//                TimeSeriesObject mr1 = TimeSeriesObject.getGaussianDistribution(41, mw, 1);
//                TimeSeriesObject mr2 = null;
//
//                // MR1 ist eine Kreuzkorrelationsfunktion mit ausreisser ...
//
//                // store a result value
//                TimeSeriesObject test = new TimeSeriesObject();
//                test.setLabel("F_CC(tau)_1_mw=" + mw);
//
//                TimeSeriesObject test_2 = new TimeSeriesObject();
//                test_2.setLabel("F_CC(tau)_2_mw=" + mw);
//
//                TimeSeriesObject test_3 = new TimeSeriesObject();
//                test_3.setLabel("F_CC(tau)_3_mw=" + mw);
//
//                TimeSeriesObject test_4 = new TimeSeriesObject();
//                test_4.setLabel("F_CC(tau)_4_mw=" + mw);
//
//                // store more result values
//                TimeSeriesObject test2 = new TimeSeriesObject();
//                test2.setLabel("mw=" + mw);
//
//                int iMAX = 41;
//
//                // do the procedur 10 times ...
//                // using the original row mr1
//                for (int i = 0; i < iMAX; i = i + 1) {
//
//                    TimeSeriesObject p = mr1.copy();
//
//                    double stdevA = p.getStddev();
//                    double mwA = p.getAvarage2();
//
//                    double sign = 1.0;
//                    if ( Math.random() < 0.5 ) sign = -1.0;
//                    
//                    // von der verrauschten F_CC Reihe ausgehend einen Peak 
//                    // fÃ¼r eine starke Correlation einsetzen ...
//                    p.yValues.setElementAt(i * sign * (1 + (4.0 / iMAX)), i);
//
//                    double stdevB = p.getStddev();
//                    double mwB = p.getAvarage2();
//
//                    // wir wollen ja mehrere Peak StÃ¤rken ansehen ...
//                    //reihen.add( p );
//
//                    double v1 = InfluenceOfSinglePeakTester.calcStrength_VERSION_A(p);
////                    double v2 = InfluenceOfSinglePeakTester.calcStrength_VERSION_B(p);
//                    double v3 = InfluenceOfSinglePeakTester.calcStrength_VERSION_C(p);
//                    double v4 = InfluenceOfSinglePeakTester.calcStrength_VERSION_D(p);
//                    
//                    test.addValuePair(i, v1 );
////                    test_2.addValuePair(i, v2 );
//                    test_3.addValuePair(i, v3 );
//                    test_4.addValuePair(i, v4);
//
//                }
//                t1reihen.add(test);
////                t2reihen.add(test_2);
//                t3reihen.add(test_3);
//                t4reihen.add(test_4);
//            }
//
//            MultiChart.xRangDEFAULT_MIN = 0;
//            MultiChart.xRangDEFAULT_MAX = 41;
//            MultiChart.setDefaultRange = true;
//
//            TimeSeriesObject avR1 = new TimeSeriesObject();
//            avR1 = TimeSeriesObject.calcAveragOfRows(t1reihen);
//            avR1.setLabel("<F_CC(tau)_1_mw=" + mw + ">");
//
//            TimeSeriesObject avR3 = new TimeSeriesObject();
//            avR3 = TimeSeriesObject.calcAveragOfRows(t3reihen);
//            avR3.setLabel("<F_CC(tau)_3_mw=" + mw + ">");
//
////            TimeSeriesObject avR2 = new TimeSeriesObject();
////            avR3 = TimeSeriesObject.calcAveragOfRows(t2reihen);
////            avR3.setLabel("<F_CC(tau)_2_mw=" + mw + ">");
//
//            
//            TimeSeriesObject avR4 = new TimeSeriesObject();
//            avR4 = TimeSeriesObject.calcAveragOfRows( t4reihen );
//            avR4.setLabel("<F_CC(tau)_4_mw=" + mw + ">");
//
//            reihen.add(avR1);
//            reihen.add(avR3);
//            reihen.add(avR4);
//        }
//        MultiChart.open(reihen, "Test of the influence of strong correlation peaks in the "
//                + "CC-function", "tau", "CC(tau,peak_height)", true);
//
//        File fMWT = new File( "./MWT_Experiment_1_" + run + "_" + NR_OF_ROWS + ".dat" );
//        MesswertTabelle mwt = new MesswertTabelle();
//        mwt.singleX = false;
//        mwt.setMessReihen(reihen);
//        mwt.setHeader("Test of the influence of strong correlation peaks in the CC-function \n# tau \n# CC(tau,peak_height)");
//        mwt.writeToFile( fMWT );
//
////        MultiChart.open(reihen_strength , "Influence of systematic peaks in " +
////                                 "CC-function", "peak_heigth", "strength", true);
////        
//
//    }
//
    public static void calcShapiroValuesForPeaks() throws Exception {

        DistributionTester dst = new DistributionTester();
        dst.init();

        Vector<TimeSeriesObject> reihen_strength = new Vector<TimeSeriesObject>();

        Vector<TimeSeriesObject> reihen = new Vector<TimeSeriesObject>();
        
        double sigma = 0;
                 
        for (int j = 1; j < 6; j++) {

            Vector<TimeSeriesObject> t1reihen = new Vector<TimeSeriesObject>();
            Vector<TimeSeriesObject> t2reihen = new Vector<TimeSeriesObject>();
            Vector<TimeSeriesObject> t3reihen = new Vector<TimeSeriesObject>();
            Vector<TimeSeriesObject> t4reihen = new Vector<TimeSeriesObject>();

            sigma = 1.0*j;

            for (int t = 0; t < NR_OF_ROWS; t++) {

                // simulate a time series with Gaussian distribution
                // of length 41 variable average and sigma 1.
                TimeSeriesObject mr1 = TimeSeriesObject.getGaussianDistribution(41, 0, sigma);

                // store test result values
                TimeSeriesObject test = new TimeSeriesObject();
                test.setLabel("Shapiro Wilk: (tau)_1_mw=" + 0);

                TimeSeriesObject test_2 = new TimeSeriesObject();
                test_2.setLabel("Shapiro-Wilk: log(tau)_2_mw=" + 0);

//                TimeSeriesObject test_3 = new TimeSeriesObject();
//                test_3.setLabel("SWT (tau)_3_mw=" + mw);
//
//                TimeSeriesObject test_4 = new TimeSeriesObject();
//                test_4.setLabel("SWT log(tau)_4_mw=" + mw);

                int iMAX = 41;

                // do the procedur 10 times ...
                // using the original row mr1
                for (int i = 0; i < iMAX; i = i + 1) {

                    TimeSeriesObject p = mr1.copy();

                    double stdevA = p.getStddev();
                    double mwA = p.getAvarage2();

                    // flip a coin to idenitfy the direction of the peak.
                    double sign = 1.0;
                    if ( Math.random() < 0.5 ) sign = -1.0;
                    
                    // von der verrauschten Reihe ausgehend nun einen "Stör-Peak" 
                    // einsetzen ...
                    
                    // Peakhöhe ist dabei i
                    p.addValuePair( i * sign * (1 + (4.0 / iMAX)), i );    

                    double stdevB = p.getStddev();
                    double mwB = p.getAvarage2();

                    // wir wollen ja mehrere Peak StÃ¤rken ansehen ...
                    //reihen.add( p );

                    double[] v1 = dst.testDataset(p.getYData());
                    double[] v2 = dst.testLogDataset(p.getYData());
                    
                    test.addValuePair(i, v1[0] );
//                    test_3.addValuePair(i, v1[1] );
                    test_2.addValuePair(i, v2[0] );
//                    test_4.addValuePair(i, v2[1] );

                }

                t1reihen.add(test);
//                t3reihen.add(test_3);
                t2reihen.add(test_2);
//                t4reihen.add(test_4);
            }

            MultiChart.yRangDEFAULT_MIN = 0;
            MultiChart.xRangDEFAULT_MIN = 0;
            MultiChart.yRangDEFAULT_MAX = 1.0;
            MultiChart.xRangDEFAULT_MAX = 41;
            
            MultiChart.setDefaultRange = true;

            TimeSeriesObject avR1 = new TimeSeriesObject();
            avR1 = TimeSeriesObject.calcAveragOfRows(t1reihen);
            avR1.setLabel("< SW pValue mw=" + 0 + ">");

//            TimeSeriesObject avR3 = new TimeSeriesObject();
//            avR3 = TimeSeriesObject.calcAveragOfRows(t3reihen);
//            avR3.setLabel("< SW WSTAT mw=" + mw + ">");

            reihen.add(avR1);
//            reihen.add(avR3);
            
            TimeSeriesObject avR2 = new TimeSeriesObject();
            avR2 = TimeSeriesObject.calcAveragOfRows(t2reihen);
            avR2.setLabel("< SW pValue log mw=" + 0 + ">");

////            TimeSeriesObject avR4 = new TimeSeriesObject();
////            avR4 = TimeSeriesObject.calcAveragOfRows(t4reihen);
////            avR4.setLabel("< SW WSTAT log mw=" + mw + ">");

            reihen.add(avR2);
//            reihen.add(avR4);

        }
        
        MultiChart.open(reihen, "Test of the influence of strong peaks on SW Test", "i", "p_SW(i," + sigma +")", true);

        int run = 0;
        
//        File fMWT = new File(   "./CIOSP/MWT_Experiment_1_SWTest_" + run + "_" + NR_OF_ROWS + ".dat" );
//
//        MesswertTabelle mwt = new MesswertTabelle();
//        mwt.singleX = false;
//        mwt.setMessReihen(reihen);
//        mwt.setHeader("Test of the influence of strong peaks on SW Test p-value");
//        mwt.writeToFile( fMWT );

//        MultiChart.open(reihen_strength , "Influence of systematic peaks in " +
//                                 "CC-function", "peak_heigth", "strength", true);
//        

    }
//
//    /**
//     *   Now we test with real time series.
//     */
//    private static void calcTestForSimulatedTS() {
//        
//        TimeSeriesObject mr1;
//        TimeSeriesObject mr2;
//        
//        double f = 0.0;
//        
//        TimeSeriesObject f_str = new TimeSeriesObject();
//        TimeSeriesObject f_strH = new TimeSeriesObject();
//        f_str.setLabel("<strength>( mittlerer PeakhÃ¶he )");
//        f_strH.setLabel("<strength>( mittlerer PeakhÃ¶he ) && strength > " + ts);
//        
//
//        Vector<TimeSeriesObject> fv = new Vector<TimeSeriesObject>();
//        fv.add(f_str);
//        fv.add(f_strH);
//        for (int j = 1; j <= 10; j++) {
//            f = 0.5 * j;
//
//            double mw = 25;
//            double tau = 21;
//            double devH = 5;
//            double devW = 0.2;
//
//            mr1 = TimeSeriesObject.getGaussianDistribution(300, mw, 2);
//            mr2 = TimeSeriesObject.getGaussianDistribution(300, mw, 2);
//
//            Vector<TimeSeriesObject> rr = new Vector<TimeSeriesObject>();
//            Vector<TimeSeriesObject> kkrr = new Vector<TimeSeriesObject>();
//
//            KreuzKorrelation.defaultK = (int) tau;
//
//            TimeSeriesObject test2 = new TimeSeriesObject();
//            test2.setLabel("<strength> zur mittleren PeakhÃ¶he f=" + f);
//            TimeSeriesObject test3 = new TimeSeriesObject();
//            test3.setLabel("<strength> zur mittleren PeakhÃ¶he f=" + f + " ( strength > " + ts + ")");
//
//            double avSTR = 0.0;
//            double avSTR_H = 0.0;
//            int c = 0;
//
//            int z = NR_OF_ROWS;
//            for (int i = 0; i < z; i++) {
//
//                // Variation der Lage der Peaks
//                // Wenn breiter verteilt, als tau, dann geht die Significanz
//                // verloren
//                double r1 = stdlib.StdRandom.gaussian(0, devW * tau);
//                double r2 = stdlib.StdRandom.gaussian(0, devW * tau);
//
//                // HÃ–HE des PEAKS
//                double y1 = stdlib.StdRandom.gaussian(mw * f, devH);
//                double y2 = stdlib.StdRandom.gaussian(mw * f, devH);
//
//                TimeSeriesObject m1 = mr1.copy();
//                TimeSeriesObject m2 = mr2.copy();
//
//                addPeak(m1, (int) (100 + r1), y1, 300);
//                addPeak(m2, (int) (100 + r2), y2, 300);
//
//                boolean b = true;
//                if (i % 500 == 0) {
//                    b = true;
//                } else {
//                    b = false;
//                }
//
////                m1.normalize();
////                m2.normalize();
//
//                KreuzKorrelation kr = null;
//
//                kr = KreuzKorrelation.calcKR(m1, m2, false, false);
//
//                kkrr.add(kr);
//                double str = 0.0;
//
//                str = InfluenceOfSinglePeakTester._calcStrength(kr);
//
//                avSTR = avSTR + str;
//
//                
//                // zÃ¤hle die, wo die CC_max > als ts ist ...
//                if (str > ts) {
//                    test3.addValuePair(i, str);
//                    avSTR_H = avSTR_H + str;
//                    c++;
//                } 
//                else {
//                    test2.addValuePair(i, str);
//                }
//
//                rr.add(m1);
//                rr.add(m2);
//            }
//            f_str.addValuePair(f, avSTR / (z * 1.0));
//            f_strH.addValuePair(f, avSTR_H / (c * 1.0));
//
//
//            //MultiChart.open(kkrr,"CC -> " +"f="+f+", mw="+mw,"","",false);
//
//            //MultiChart.open(rr,"test TS -> "+"f="+f+", mw="+mw,"","",false);
//            createHistogramm(test2, test3, 120, 0, 6);
//        }
//        MultiChart.open(fv, "f(str)", "f", "str", true);
//    }
//
//    public static void addPeak(TimeSeriesObject mr, int x, double y, int l) {
//        if (x > l - 1) {
//            x = l - x;
//        }
//        if (x < 0) {
//            x = l + x;
//        }
//        System.out.println(x + " " + y);
//        mr.yValues.setElementAt(y, x);
//    }
//    ;
//
//    static String OUTPUT = "E:";
//    static String run = "A";
//
//    public static Container createHistogramm(TimeSeriesObject mr,
//            TimeSeriesObject r2,
//            int bins, int min, int max) {
//
//        File f = new File(OUTPUT + "/CIOSP");
//        if (!f.exists()) {
//            f.mkdirs();
//        }
//
//        String l = mr.getLabel() + r2.getLabel();
//
//        HistogramChart demo = new HistogramChart(l);
//
//        System.out.println("mr: " + mr.yValues.size());
//        System.out.println("r2: " + r2.yValues.size());
//
//        demo.addSerieWithBinning(r2, bins, min, max);
//
//        demo.addSerieWithBinning(mr, bins, min, max);
//
//        demo.setContentPane(demo.createChartPanel());
//        demo.pack();
//        RefineryUtilities.centerFrameOnScreen(demo);
//        demo.setVisible(true);
//
//        l = l.replace(" ", "_");
//        l = l.replace(">", "_");
//        l = l.replace("<", "_");
//
//        demo.store(OUTPUT + "/CIOSP/", run + "_" + l + ".dat");
//        return demo.getContentPane();
//    }
//
//    
//
//    
//    public static TimeSeriesObject createRandomSeries_A_INTERNAL(String sl, int length, double mw, double mw_peakHoeh, double tau, double devH, double devW) {
//
//
//        // double f = stdlib.StdRandom.gaussian( 1, 10 ) * 0.5;
//
//        // Variation der Lage der Peaks
//        // Wenn breiter verteilt, als tau, dann geht die Significanz
//        // verloren
//        double r1 = stdlib.StdRandom.gaussian(0, devW * tau);
//
//        // HÃ–HE des PEAKS
//        double y1 = stdlib.StdRandom.gaussian(mw_peakHoeh, devH);
//
//        TimeSeriesObject m1 = TimeSeriesObject.getGaussianDistribution(length, mw, 1);
//        m1.setLabel(sl);
//
//        int pos_peak = (int) stdlib.StdRandom.gaussian(0, length);
//
//        if (pos_peak < 0) {
//            pos_peak = pos_peak + length;
//        } else if (pos_peak > length) {
//            pos_peak = pos_peak - length;
//        }
//
//        if (pos_peak < 0) {
//            pos_peak = pos_peak + length;
//        } else if (pos_peak > length) {
//            pos_peak = pos_peak - length;
//        }
//
//        if (pos_peak < 0) {
//            pos_peak = pos_peak + length;
//        } else if (pos_peak > length) {
//            pos_peak = pos_peak - length;
//        }
//
//        if (pos_peak < 0) {
//            pos_peak = pos_peak + length;
//        } else if (pos_peak > length) {
//            pos_peak = pos_peak - length;
//        }
//
//        int nr = (int) stdlib.StdRandom.gaussian(1, 10);
//
//        addPeak(m1, (int) (pos_peak + r1), y1, length);
//
//        return m1;
//    }
//
//    public static TimeSeriesObject createRandomSeries_A_Peaks(String label) {
//
//        double mw = 0;
//        double mw_peakHoeh = 0;// 20;  // 10
//        double tau = 14;
//        double devH = 5;
//        double devW = 0.2;
//        int length = RandomNodesGroup.length;
//
//        return createRandomSeries_A_INTERNAL(label, length, mw, mw_peakHoeh, tau, devH, devW);
//
//    }
//
// 
//    /**
//     * The first occurence of the maximum value in the row is removed.
//     *
//     * @param athis
//     * @return
//     */
//    static public TimeSeriesObject removeMaximumValueFromRow(TimeSeriesObject athis, int z) {
//
//        /**
//         * z is ignored at the moment. We only remove one value.
//         * in this case we replace it with the last value...
//         * 
//         */
//        boolean removed = false;
//
//        TimeSeriesObject mr = new TimeSeriesObject();
//        mr.setLabel(athis.getLabel());
//
//        int max = athis.yValues.size();
//
//        double maxY = athis.getMaxY();
//
//        double last = 0;
//        for (int i = 0; i < max; i++) {
//            
//            double v = (Double) athis.yValues.elementAt(i);
//            
//            if (!removed) {
//                if (v != maxY) {
//                    mr.addValuePair((double) i, v);
//                    // System.out.println( i + "not rem: " + v + "\t" + maxY);
//                } else {
//                    removed = true;
//                    mr.addValuePair((double) i, last);
//                    // System.out.println( "rem: " + v);
//                };
//            } 
//            else {
//                mr.addValuePair((double) i, (Double) athis.yValues.elementAt(i));
//            }
//            
//            last = v;
//        }
//        return mr;
//
//
//    }
//
//    /**
//     * Returns the larger value of the both:
//     *
//     * abs( max(mr)) and abs( min(mr))
//     *
//     * @param mr
//     * @return
//     */
//    private static double calcRealMaxY(TimeSeriesObject mr) {
//        double extr = 0.0;
//
//        double maxY = mr.getMaxY();
//        double minY = mr.getMinY();
//
//        double aMaxY = Math.abs(maxY);
//        double aMinY = Math.abs(minY);
//
//        if (aMaxY < aMinY) {
//            extr = minY;
//        } else {
//            extr = maxY;
//        }
//        return Math.abs(extr);
//    }
}