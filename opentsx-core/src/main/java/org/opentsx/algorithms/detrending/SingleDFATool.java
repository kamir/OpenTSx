package org.opentsx.algorithms.detrending;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.methods.DFAmulti;

import java.util.Vector;

/**
 * Hier wird nun die Begrenzung der Segementlänge auf N/4 aufgehoben ...
 *
 * Das SingleDFA-Tool berechnet für eine Zeitreihe die DFA einzeln.
 *
 * @author kamir
 */
public class SingleDFATool {

    public static boolean logLogResults = false;
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

    
    String value = "_";   // Welche Messgröße wird betrachtet?

    // Für jede Ordnung / PHASE wird hier eine Kurve erstellt.
    static public Vector<TimeSeriesObject> kurven = new Vector<TimeSeriesObject>();

    public static SingleDFATool tool = new SingleDFATool();
            
    public static void calcMultiDFA(Vector<TimeSeriesObject> vmr , int phase, String v) throws Exception {

        tool = new SingleDFATool();

        kurven = new Vector<TimeSeriesObject>(); // für jede Ordnung eine ...

        doClear = true;
        
        tool.value = v;
        
        // nun werden die Berechnungen für verschiedene Ordnungen durchgeführt
        int[] orders = {2};
        
        for (int i : orders) {
            if (vmr.size() > 0) {

                tool.runDFA(vmr, i);
                kurven.add( tool.Fs.elementAt(0) );
                

            }
            else {
                System.out.println("Der Vector<Messreie> vmr ist leer");
            }
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

        // nun werden die Berechnungen für verschiedene Ordnungen durchgeführt
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

    // alle Fluktuationsfunktionen...
    public Vector<TimeSeriesObject> Fs = new Vector<TimeSeriesObject>();
    public Vector<TimeSeriesObject> fsFMW = new Vector<TimeSeriesObject>();

    // Für alle s wird eine Mittler Fluctuationsfunktion bestimmt
    Vector<TimeSeriesObject> Fs_MW = new Vector<TimeSeriesObject>();

    // Die Fensterbreiten
    Vector<Integer> s = new Vector<Integer>();

    public static boolean showCharts = false;
    public static boolean storeCharts = false;

    Vector<TimeSeriesObject> fsF2 = null;

    // Für eine Klasse von Messreiehn wird die DFA einer Ordnung berechnet.
    public Vector<TimeSeriesObject> runDFA(Vector<TimeSeriesObject> vmr, int order) throws Exception  {

        // s bestimmen ... anhand der längsten Reihe
        int zahlW = 0;
        int zahlT = 0;
        int zahlS = 0;

        TimeSeriesObject laengsteReihe = vmr.elementAt(0);
        
        int max = vmr.size();
        System.out.println( "zahlW=" + zahlW + " max=" + max + " " + laengsteReihe.getLabel() );
        
        for (int z = 0; z < max ; z++) {
            TimeSeriesObject mr = vmr.elementAt(z);
            System.out.println( z+ " size x=" + mr.xValues.size() );
            
            if (mr.xValues.size() > zahlW) {
                        zahlW = mr.xValues.size();
                        laengsteReihe = mr;
                        
            }
            
        }
        
        
        System.out.println(""
                + "> Ermittle s für längste Reihe mit l=" + zahlW
                + " Werten. \n> Label der längsten Reihe: " + laengsteReihe.getLabel() );

        // ohne Begrenzung auf N/4 !!!

      
        
        dfa = DetrendingMethodFactory.getDFAmulti(order);

        dfa.setZR( laengsteReihe.getData()[1] );

        // Anzahl der Werte in der Zeitreihe
        dfa.setNrOfValues((int)(laengsteReihe.getXValues().size() / 4));

        // die Werte für die Fensterbreiten sind zu wählen ...

        int s_max = (int)(zahlW);
        int s_min = Math.abs( order + 2 );

        dfa.initIntervalS_FULL2( s_min , s_max );
        
        S=dfa.getS();
        dfa.showS();
        
        zahlS = dfa.getS().length;
        
        System.out.println( " <<< " + zahlS + "=s >>> werden betrachtet.");

        _initPuffer(zahlS);

        String savePath = "/Volumes/MyExternalDrive/CALCULATIONS/data/out/";
        

        double fit_min[] = { 0.8, 1.5, 2.2 };
        double fit_max[] = { 1.3, 2.1, 2.8 };
                

        System.out.println(
                "> Berechne DFA für " + vmr.size() +
                " Messreihen mit order=" + order + ".");

        StringBuffer info = new StringBuffer("> Infos zur DFA-Rechnung");
        info.append("\nnr.\t| file\t| save path");


        for (int z = 0; z < vmr.size(); z++) {

            System.out.println(  (z*1.0) / vmr.size() );
            
            double[][] dfa_results = null;

            TimeSeriesObject mr = vmr.elementAt(z);
            if (mr != null ) {

                // set number of values
                double[] zr = mr.getData()[1];
                dfa.setZR(zr);
                dfa.setNrOfValues(zr.length);

                dfa.debug = false;

                dfa.calc();

                if ( logLogResults ) {
                   Fs.add( dfa.getResultsMRLogLog() );
                }
                else {
                   Fs.add( dfa.getResultsMR() );
                }

                System.out.println( "Reihe=" + z + " d < " + dfa.sMax );
                System.out.println( (dfa.sMax) + " => maximaler Index für größtes s der längsten Reihe.");

                
            }
            else {
                System.out.println( "MR = null !!! ");
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
