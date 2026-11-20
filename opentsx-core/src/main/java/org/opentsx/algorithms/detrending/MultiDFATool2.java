package org.opentsx.algorithms.detrending;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.generator.TestDataFactory;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.methods.DFAmulti;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Vector;

/**
 * Runs the DFA of every series of a group, for example a class of series,
 * with window sizes up to the full length instead of N/4.
 *
 * For every window size s it also sums (x tilde)^2 and counts the segments.
 *
 * The end result is one <F(s)> for all contributions of the series of
 * different length.
 *
 * @author kamir
 */
public class MultiDFATool2 {

    private static final Logger LOG = LoggerFactory.getLogger(MultiDFATool2.class);

    public static boolean logLogResults = false;

    public MultiDFATool2() {
        resetPuffer();
    };

    static double[] sum_xs_xs_durch_s = null;
    static double[] anzSegmente = null;

    static double[] sum_xs_xs_durch_s_ALL = null;
    static double[] anzSegmente_ALL = null;

    static boolean doClear = true;

    TimeSeriesObject finalFS = null;

    static void resetPuffer() {

        sum_xs_xs_durch_s = null;
        anzSegmente = null;

        sum_xs_xs_durch_s_ALL = null;

        anzSegmente_ALL = null;

        sum_xs_xs_durch_s_ALL = null;
        anzSegmente_ALL = null;
        
    }

    static void initPuffer(int zahlS) {

        sum_xs_xs_durch_s = new double[zahlS];
        anzSegmente = new double[zahlS];

        for( int i = 0; i < zahlS; i++ ) {
            anzSegmente[i] = 0;
            sum_xs_xs_durch_s[i] = 0;
        }
        
        if ( sum_xs_xs_durch_s_ALL == null )sum_xs_xs_durch_s_ALL = new double[zahlS];

        if( anzSegmente_ALL == null ) anzSegmente_ALL = new double[zahlS];

        if ( doClear ) {
            sum_xs_xs_durch_s_ALL = new double[zahlS];
            anzSegmente_ALL = new double[zahlS];
            doClear = false;
        }
        
        mw = null;

    }

    int[] S = null;

    // for the title of the chart
    int phase = 0;
    String value = "_";   // the measured quantity

    // one curve for every order or phase
    static public Vector<TimeSeriesObject> kurven = new Vector<TimeSeriesObject>();

    public static MultiDFATool2 tool = new MultiDFATool2();
            
    public static void calcMultiDFA(Vector<TimeSeriesObject> vmr , int phase, String v) {

        tool = new MultiDFATool2();

        kurven = new Vector<TimeSeriesObject>(); // one per order

        doClear = true;
        
        tool.phase = phase;
        tool.value = v;

        if (vmr.isEmpty()) {
            throw new IllegalArgumentException("calcMultiDFA: vmr has no series; expected at least 1 series");
        }
        
        // the DFA for every order
        int[] orders = {2};
        
        for (int i : orders) {
            tool.runDFA(vmr, i);
            kurven.add( tool.Fs.elementAt(0) );
            tool.Fs.clear();
        }
    }

    public static void main(String[] main) {

        MultiDFATool2 tool = new MultiDFATool2();
        tool.logLogResults = true;
        tool.showCharts = true;
        tool.storeCharts = true;

        Vector<TimeSeriesObject> vmr = getTestReihen( 4 );

        // the DFA for every order
        int[] orders = { 2 };
        for (int i : orders) {
            try {
                tool.runDFA(vmr, i);                
            }
            catch (Exception ex) {
               ex.printStackTrace();
            }
            tool.Fs.clear();
        }

        MultiChart.open(vmr);

    }


    DFAmulti dfa;

    // all fluctuation functions
    public Vector<TimeSeriesObject> Fs = new Vector<TimeSeriesObject>();
    public Vector<TimeSeriesObject> fsFMW = new Vector<TimeSeriesObject>();

    // the mean fluctuation function for every s
    Vector<TimeSeriesObject> Fs_MW = new Vector<TimeSeriesObject>();

    // the window sizes
    Vector<Integer> s = new Vector<Integer>();

    public static boolean showCharts = false;
    public static boolean storeCharts = false;

    Vector<TimeSeriesObject> fsF2 = null;

    // the DFA of one order for a class of series
    public void runDFA(Vector<TimeSeriesObject> vmr, int order)  {

        // the longest series sets the window sizes s
        int zahlW = 0;
        int zahlT = 0;
        int zahlS = 0;

        TimeSeriesObject laengsteReihe = vmr.elementAt(0);
        
        int max = vmr.size();
        
        for (int z = 0; z < max ; z++) {
            TimeSeriesObject mr = vmr.elementAt(z);
            if (mr == null) {
                throw new IllegalArgumentException("runDFA: series " + z + " is null; expected a series at every index");
            }
            
            if (mr.xValues.size() > zahlW) {
                        zahlW = mr.xValues.size();
                        laengsteReihe = mr;
                        
            }
            
        }
        
        
        LOG.debug("longest series series={} label={} values={}", max, laengsteReihe.getLabel(), zahlW);

        // without the limit of N/4
        dfa = DetrendingMethodFactory.getDFAmulti(order);

        dfa.setZR( laengsteReihe.getData()[1] );

        // number of values in the series
        dfa.setNrOfValues(laengsteReihe.getXValues().size());

        // choose the window sizes

        int s_max = zahlW;
        int s_min = Math.abs( order  + 2 );

        dfa.initIntervalS_FULL( s_min , s_max );

        S=dfa.getS();
        dfa.showS();
        
        zahlS = dfa.getS().length;
        
        initPuffer(zahlS);

        LOG.debug("running DFA series={} order={} windows={}", vmr.size(), order, zahlS);

        // for every s the sum of xs^2 and the number of segments
        for (int z = 0; z < vmr.size(); z++) {

            double[][] dfa_results = null;

            TimeSeriesObject mr = vmr.elementAt(z);

            if (mr != null ) {

                // set number of values
                double[] zr = mr.getData()[1];
                dfa.setZR(zr);
                dfa.setNrOfValues(zr.length);


                dfa.calc();

                if ( logLogResults ) {
                   Fs.add( dfa.getResultsMRLogLog() );
                }
                else {
                   Fs.add( dfa.getResultsMR() );
                }

                


                
                // the contributions to the mean function for every s
                for( int d = 0; d < dfa.sMax ; d++ ) {
                    sum_xs_xs_durch_s[d] = dfa.FSMW[0][d];
                    anzSegmente[d] = dfa.FSMW[1][d];
                   
                    sum_xs_xs_durch_s_ALL[d] = sum_xs_xs_durch_s_ALL[d] + dfa.FSMW[0][d];
                    anzSegmente_ALL[d] = anzSegmente_ALL[d]+ dfa.FSMW[1][d];

                }

            }

        
        }


                 





        // collect the values into one F(s)
        finalFS = new TimeSeriesObject("<F(s)>");
        
        TimeSeriesObject finalZ = new TimeSeriesObject("#segments");
        TimeSeriesObject finalS = new TimeSeriesObject("s");

        for( int d = 0; d < dfa.sMax-1 ; d++ ) {
            if ( anzSegmente_ALL[d] > 4 ) {
                if ( logLogResults ) {
                    finalFS.addValuePair(Math.log10(S[d]), Math.log10(Math.sqrt( sum_xs_xs_durch_s_ALL[d] / anzSegmente_ALL[d])));
                }
                else {
                    finalFS.addValuePair( S[d], Math.sqrt( sum_xs_xs_durch_s_ALL[d] / anzSegmente_ALL[d]));
                }
                finalZ.addValuePair(S[d], anzSegmente_ALL[d] );
            }
        }

        fsFMW = new Vector<TimeSeriesObject>();
        fsFMW.add(finalFS);

        fsF2 = new Vector<TimeSeriesObject>();
        fsF2.add(finalZ);

        mw = finalFS;
        mw.setLabel("<F(s)> phase=" + phase );

        try{
            double TEST_alpha = finalFS.linFit( 1.25, 2.0 ).getSlope();
            LOG.debug("fitted alpha phase={} order={} alpha={}", phase, order, TEST_alpha);
        }
        catch( Exception ex ) {
            throw new IllegalStateException("runDFA: cannot fit alpha to <F(s)> for log s from 1.25 to 2 (" + ex + ")", ex);
        }




        String[] normal_Lable = {"s", "F(s)" };
        String[] logLog_Lable = {"log(s)" , "log( F(s) )" };

        String[] label = normal_Lable;

        if ( logLogResults ) label = logLog_Lable;
        
        if (showCharts) {
            MultiChart.open(fsFMW, "<F(s)>", label[0], label[1], true, "?", null);

        }
        else {
            LOG.debug("computed mean fluctuation phase={} order={} value={}", phase, order, value);
        }

        resetPuffer();
        
    }

    static TimeSeriesObject mw = null;
    
    /**
     * Returns the mean curve once; a second call returns null.
     * @return 
     */
    static public TimeSeriesObject getMWKurve() {
        TimeSeriesObject r = mw;
        mw = null;
        return r;
    }



    public static Vector<TimeSeriesObject> getTestReihen(int anz ) {

        RNGWrapper.init();


        int[] length = { 1000, 500, 250, 500, 2500,
                         3500, 1450, 2500, 700, 3000,
                         1000, 600, 200, 100, 2000,
                         1234, 5678, 2222, 100, 10  };

        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
        for (int i = 0; i < anz; i++) {

            TimeSeriesObject mr = TestDataFactory.getDataSeriesRandomValues_RW(length[i]);
            mr.setLabel("R" + i + " [" + length[i] + "]");
            vmr.add(mr);
        }

        return vmr;
    }


}
