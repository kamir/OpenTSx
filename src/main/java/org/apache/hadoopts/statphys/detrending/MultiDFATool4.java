package org.apache.hadoopts.statphys.detrending;

import org.apache.hadoopts.analysistools.HZDouble;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.RNGWrapper;
import org.apache.hadoopts.data.generator.TestDataFactory;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.statphys.detrending.methods.DFAmulti;

import java.util.Vector;

/**
 * Hier wird nun die Begrenzung der Segementlänge auf N/4 aufgehoben ...
 *
 * Das MultiDFA-Tool berechnet für eine Menge von Zeitreihen, die z.B. zu
 * einer bestimmten Klasse gehöen die DFA einzeln.
 *
 * Ausserdem wird für jede Fensterbreite s die Summe von (x-schlange)^2
 * und die Anzahl der betrachteten Segmente ermittelt. {FORMEL:II.A.20}
 *
 * Am Ende wird für alle s aus allen Beiträgen der unterschielich langen
 * Zeitreihen ein <F(s)> bestimmt.
 *
 * @author kamir
 */
public class MultiDFATool4 {

    public static boolean logLogResults = false;
    public static boolean debug;

    public MultiDFATool4() {
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
        
        mw = null;

    }

    int[] S = null;

    
    String value = "_";   // Welche Messgröße wird betrachtet?

    // Für jede Ordnung / PHASE wird hier eine Kurve erstellt.
    static public Vector<TimeSeriesObject> kurven = new Vector<TimeSeriesObject>();

    public static MultiDFATool4 tool = new MultiDFATool4();
            
    public static void calcMultiDFA(Vector<TimeSeriesObject> vmr , int phase, String v) throws Exception {
//
//        System.out.println( vmr.size() + " Reihen in Phase: " + phase );
//        MultiChart.open(vmr, "Messwerte - [Phase " + phase + "] (all rows)", "t", "y(s)", false, "?");

        tool = new MultiDFATool4();

        kurven = new Vector<TimeSeriesObject>(); // für jede Ordnung eine ...

        doClear = true;
        
        tool.value = v;
        
        // nun werden die Berechnungen für verschiedene Ordnungen durchgeführt
        int[] orders = {2};
        
        for (int i : orders) {
            if (vmr.size() > 0) {

                tool.runDFA(vmr, i);
                kurven.add( tool.Fs.elementAt(0) );
                
                // System.out.println( tool.getMWKurve() );

            }
            else {
                System.out.println("Der Vector<Messreie> vmr ist leer");
            }
            tool.Fs.clear();
        }
    }

    public static void main(String[] main) {

        MultiDFATool4 tool = new MultiDFATool4();
        tool.logLogResults = true;
        tool.showCharts = true;
        tool.storeCharts = true;

        Vector<TimeSeriesObject> vmr = getTestReihen( 4 );

        // nun werden die Berechnungen für verschiedene Ordnungen durchgeführt
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
    public void runDFA(Vector<TimeSeriesObject> vmr, int order) throws Exception  {

         
        
        // s bestimmen ... anhand der längsten Reihe
        int zahlW = 0;
        int zahlT = 0;
        int zahlS = 0;

        TimeSeriesObject laengsteReihe = vmr.elementAt(0);
        
//        int max = 10;
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

      
        
        dfa = (DFAmulti)DetrendingMethodFactory2.getDetrendingMethod(order);

        dfa.setZR( laengsteReihe.getData()[1] );

        // Anzahl der Werte in der Zeitreihe
        dfa.setNrOfValues((int)(laengsteReihe.getXValues().size() / 4));

        // die Werte für die Fensterbreiten sind zu wählen ...

        int s_max = (int)(zahlW);
        int s_min = Math.abs( order + 2 );

//        dfa.initIntervalS_FULL_LOG( s_min , s_max );
         dfa.initIntervalS_FULL2( s_min , s_max );

        S=dfa.getS();
//        dfa.showS();
        
        zahlS = dfa.getS().length;
        
        System.out.println( " <<< " + zahlS + "=s >>> werden betrachtet.");

        _initPuffer(zahlS);

        String savePath = "/Volumes/MyExternalDrive/CALCULATIONS/data/out/";
        

        double fit_min[] = { 0.8, 1.5, 2.2 };
        double fit_max[] = { 1.3, 2.1, 2.8 };
                
//        double alpha_cor_min = 0.0;  // zu klären was hier gemeint ist !!!

        System.out.println(
                "> Berechne DFA für " + vmr.size() +
                " Messreihen mit order=" + order + ".");

        StringBuffer info = new StringBuffer("> Infos zur DFA-Rechnung");
        info.append("\nnr.\t| file\t| save path");

        // je Zeitreihe wird ein alpha bestimmt ....
        // double[] alpha = new double[vmr.size()];    
        HZDouble hzd1 = new HZDouble();
        HZDouble hzd2 = new HZDouble();
        HZDouble hzd3 = new HZDouble();
        

        for (int z = 0; z < vmr.size(); z++) {

            System.out.println(  (z*1.0) / vmr.size() );
            
            double[][] dfa_results = null;

            TimeSeriesObject mr = vmr.elementAt(z);
            
            System.gc();

            if (mr != null ) {

                // set number of values
                double[] zr = mr.getData()[1];
                dfa.setZR(zr);
                dfa.setNrOfValues(zr.length);

                dfa.debug = false;

                dfa.calc();

                // System.out.println( "> useLogLogResulst: " + logLogResults );
                if ( logLogResults ) {
                   Fs.add( dfa.getResultsMRLogLog() );
                }
                else {
                   Fs.add( dfa.getResultsMR() );
                }

                

//                System.out.println( "Reihe=" + z + " d < " + dfa.sMax );
//                System.out.println( (dfa.sMax) + " => maximaler Index für größtes s der längsten Reihe.");

                
                // Für alle s nun die Beiträge für die MW-Funktion sammeln
//                for( int d = 0; d < dfa.sMax ; d++ ) {
//                    sum_xs_xs_durch_s[d] = dfa.FSMW[0][d];
//                    anzSegmente[d] = dfa.FSMW[1][d];
//                   
//                    sum_xs_xs_durch_s_ALL[d] = sum_xs_xs_durch_s_ALL[d] + dfa.FSMW[0][d];
//                    anzSegmente_ALL[d] = anzSegmente_ALL[d]+ dfa.FSMW[1][d];
//                }

                TimeSeriesObject res_mr = dfa.getResultsMRLogLog();
                
                hzd1.addData( res_mr.linFit(fit_min[0], fit_max[0]).getSlope() );
                hzd2.addData( res_mr.linFit(fit_min[1], fit_max[1]).getSlope() );
                hzd3.addData( res_mr.linFit(fit_min[2], fit_max[2]).getSlope() );
                
            }
            else {
                System.out.println( "MR = null !!! ");
            }
        }

        hzd1.calcWS();
        hzd2.calcWS();
        hzd3.calcWS();
        
        Vector<TimeSeriesObject> m = new Vector<TimeSeriesObject>();
        m.add( hzd1.getHistogram() );
        m.add( hzd2.getHistogram() );
        m.add( hzd3.getHistogram() );
        
        MultiChart.open(m, "alpha (order=" + order +")", "alpha", "#" , true);
        
        // zur Illustration ...         
        // MultiChart.open(dfa.getMRFit(), "fit", "s", "F(s)", false, "?");

        // und nun sammeln wir die Werte ein und berechnen ein Fs
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

//        fsFMW = new Vector<TimeSeriesObject>();
//        fsFMW.add(finalFS);
        
        
        fsFMW = Fs;
//
//        fsF2 = new Vector<TimeSeriesObject>();
//        fsF2.add(finalZ);
//
//        mw = finalFS;
//        mw.setLabel("<F(s)> order=" + order + " (log=" + logLogResults + ")");
//
//        try{
//            double TEST_alpha = finalFS.linFit( 1.25, 2.0 ).getSlope();
//            System.out.println( ">>> alpha=" + TEST_alpha );
//        }
//        catch( Exception ex ) {
//            System.err.println( ex.getCause() );
//            //ex.printStackTrace();
//        };


//      System.out.println(info.toString());

//      SimpleRegression reg = finalFS.linFit(fit_min, fit_max);
//      System.out.println("alpha=" + (reg.getSlope()));

        String[] normal_Lable = {"s", "F(s)" };
        String[] logLog_Lable = {"log(s)" , "log( F(s) )" };

        String[] label = normal_Lable;

        if ( logLogResults ) label = logLog_Lable;
        
        if (showCharts) {
//            MultiChart.open(Fs, "[Phase " + phase + "] F(s) [DFA-order:" + order + "] {" + value + "}", label[0], label[1], true, "?");
//            MultiChart.open(fsF2, "Anzahl Segmente je s [order:" + order + "] (all rows)", "s", "z", true, "?");
            MultiChart.open(fsFMW, "<F(s)> order="+order, label[0], label[1], true, "*", null);

        }
        else {   // Fs, "[Phase " + phase + "] F(s) [DFA-order:" + order + "] {" + value + "}", "log(s)", "log(F(s))", false, "?");
            String file= " DFA-order_"+ order + " v=" + value;
            System.out.println( file );

//            MultiChart.openAndStore( Fs, "F(s) - [Phase " + phase + "] [DFA-order:" + order + "] {" + value + "}",  "log(s)", "log(F(s))", true, "data/dfa", file, "?");
//            MultiChart.open(Fs, "F(s)", "s", "F(s)", false, "?");
//            MultiChart.open(fsFMW, "<F(s)> - [Phase " + phase + "] F(s) [order:" + order + "] (all rows)", "log(s)", "log(<F(s)>)", false, "?");
//            MultiChart.open(fsF2, "Anzahl Segmente je s - [Phase " + phase + "]  [order:" + order + "] (all rows)", "s", "#Segmente", false, "?");
        }

        resetPuffer();
        
    }

    static TimeSeriesObject mw = null;
    
    /**
     * zurückgeben nur einmal möglich !!!
     * @return 
     */
    static public TimeSeriesObject _getMWKurve() {
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
        }  // nun liegen verschieden Lange Testreihen vor ...

        return vmr;
    }

   


}
