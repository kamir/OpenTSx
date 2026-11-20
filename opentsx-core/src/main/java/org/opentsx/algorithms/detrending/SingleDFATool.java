package org.opentsx.algorithms.detrending;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.methods.DFAmulti;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Vector;

/**
 * Computes the DFA of every series on its own, with window sizes up to the
 * full length instead of N/4.
 *
 * @author kamir
 */
public class SingleDFATool {

    private static final Logger LOG = LoggerFactory.getLogger(SingleDFATool.class);

    public static boolean logLogResults = false;
    /**
     * @deprecated Has no effect; the level of the SLF4J logger of this class decides.
     */
    @Deprecated
    public static boolean debug;

    public SingleDFATool() {
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

    static void _initPuffer(int zahlS) {

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
        
        

    }

    int[] S = null;

    
    String value = "_";   // the measured quantity

    // one curve for every order or phase
    static public Vector<TimeSeriesObject> kurven = new Vector<TimeSeriesObject>();

    public static SingleDFATool tool = new SingleDFATool();
            
    public static void calcMultiDFA(Vector<TimeSeriesObject> vmr , int phase, String v) throws Exception {

        tool = new SingleDFATool();

        kurven = new Vector<TimeSeriesObject>(); // one per order

        doClear = true;
        
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

        RNGWrapper.init();

        SingleDFATool tool = new SingleDFATool();
        tool.logLogResults = true;
        tool.showCharts = true;

        
        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
        vmr.add( TimeSeriesObject.getGaussianDistribution( 4000 ) );
        vmr.add( TimeSeriesObject.getGaussianDistribution( 4000 ) );

        // the DFA for every order
        int[] orders = { 2 };
        for (int i : orders) {
            try {
                tool.runDFA(vmr, i);       
            }
            catch (Exception ex) {
               ex.printStackTrace();
            }
        }
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
    public Vector<TimeSeriesObject> runDFA(Vector<TimeSeriesObject> vmr, int order) throws Exception  {

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
        dfa.setNrOfValues((int)(laengsteReihe.getXValues().size() / 4));

        // choose the window sizes

        int s_max = (int)(zahlW);
        int s_min = Math.abs( order + 2 );

        dfa.initIntervalS_FULL2( s_min , s_max );
        
        S=dfa.getS();
        dfa.showS();
        
        zahlS = dfa.getS().length;
        
        LOG.debug("running DFA series={} order={} windows={}", vmr.size(), order, zahlS);

        _initPuffer(zahlS);

        for (int z = 0; z < vmr.size(); z++) {

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

                LOG.debug("computed DFA series={} maxWindowIndex={}", z, dfa.sMax);
            }

        }
        
        return Fs;
        
    }

    public String getAlphas() throws Exception {
        String s = "";
        double fit_min[] = { 0.8, 1.5, 2.2 };
        double fit_max[] = { 1.3, 2.1, 2.8 };
        double alpha1 = Fs.elementAt(0).linFit(fit_min[0], fit_max[0]).getSlope();
        double alpha2 = Fs.elementAt(0).linFit(fit_min[1], fit_max[1]).getSlope();
        double alpha3 = Fs.elementAt(0).linFit(fit_min[2], fit_max[2]).getSlope();
        s = alpha1 + "\t" + alpha2 + "\t" + alpha3;
        return s;
    }
}
