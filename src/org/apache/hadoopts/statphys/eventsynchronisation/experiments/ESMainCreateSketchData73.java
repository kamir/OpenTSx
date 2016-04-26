/**
 *  Calculates EventSynchronisation for random Event-TimeSeries.
 * 
 *  Create the image:
 * 
 *  (5.6) Visual comparison of two representations of event time series.
 * 
 */
package org.apache.hadoopts.statphys.eventsynchronisation.experiments;

//import hadoopts.analysis.eventsynchronisation.ESCalc;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;
import org.apache.hadoopts.data.series.Messreihe;
import java.io.IOException;
import java.util.Vector;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.statphys.eventsynchronisation.ESCalc2;

/**
 * @author kamir
 */
public class ESMainCreateSketchData73 {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        
        /**
         * Export location for OriginPro integration 
         */
        OriginProject op = new OriginProject();
        op.initBaseFolder("/Users/kamir/Documents/THESIS/dissertationFINAL/main/FINAL/LATEX/semanpix/ESIllustration");
        op.initFolder( "data2" );
        
        // The final data export goes via two tables ...
        Vector<Messreihe> topRow = new Vector<Messreihe>();
        Vector<Messreihe> bottomRow = new Vector<Messreihe>();
        
        // collect the logs
        StringBuffer sb = new StringBuffer();
        
        // length of series (hourly resolution for one year
        int length = 24 * 365;
        
        // how many events should be created per series ? 
        int nr1 = 50;
        int nr2 = 500;
        int nr3 = length * 2;
       
        /**
         * Define properties for EventSynchronization algorithm ...
         */
        ESCalc2.op = op;
        ESCalc2.debug = false;
        ESCalc2.store_charts = true;
        
        // only one event per bin
        int[] r1 = ESCalc2.createEventTS_SINGLE(length, nr1); 
        int[] r2 = ESCalc2.createEventTS_SINGLE(length, nr1); 

        // many events per bin
        int[] r3 = ESCalc2.createEventTS_INCREMENT(length, nr2); 
        int[] r4 = ESCalc2.createEventTS_INCREMENT(length, nr2); 

        int[] r5 = ESCalc2.createEventTS_INCREMENT(length, nr3); 
        int[] r6 = ESCalc2.createEventTS_INCREMENT(length, nr3); 

        MultiChart.xRangDEFAULT_MIN = 0;
        MultiChart.xRangDEFAULT_MAX = length;

        MultiChart.yRangDEFAULT_MIN = 0;
        MultiChart.yRangDEFAULT_MAX = 3;
        Messreihe[] rs4 = ESCalc2.checkRows(r1, r2, "Events - binary");
        
        Messreihe[] rs5 = ESCalc2.checkRows(r3, r4, "Events - incremental (low density)");
        
        MultiChart.yRangDEFAULT_MAX = 12;
        Messreihe[] rs6 = ESCalc2.checkRows(r5, r6, "Events - incremental (high density)");
        
        MesswertTabelle mwt1 = new MesswertTabelle();
        mwt1.setLabel("topRow.csv");
        mwt1.singleX = false;
        
        mwt1.addMessreihe( rs4[0] );
        mwt1.addMessreihe( rs4[1] );
        mwt1.addMessreihe( rs5[0] );
        mwt1.addMessreihe( rs5[1] );
        mwt1.addMessreihe( rs5[0] );
        mwt1.addMessreihe( rs5[1] );
        
        int[] r1ET = ESCalc2.getEventIndexSeries(r1);
        int[] r2ET = ESCalc2.getEventIndexSeries(r2); 
        
        int[] r3ET = ESCalc2.getEventIndexSeries(r3);
        int[] r4ET = ESCalc2.getEventIndexSeries(r4); 
        
        int[] r5ET = ESCalc2.getEventIndexSeries(r5);
        int[] r6ET = ESCalc2.getEventIndexSeries(r6); 
        
        MultiChart.yRangDEFAULT_MAX = 50;
        Messreihe[] rs1 = ESCalc2.checkRowsInverse(r1ET, r2ET, "ETS - binary");            
        MultiChart.yRangDEFAULT_MAX = 500;
        Messreihe[] rs2 = ESCalc2.checkRowsInverse(r3ET, r4ET, "ETS - incremental (low density)");            
        MultiChart.yRangDEFAULT_MAX = length;
        Messreihe[] rs3 = ESCalc2.checkRowsInverse(r5ET, r6ET, "ETS - incremental (high density)");            
        
        MesswertTabelle mwt = new MesswertTabelle();
        mwt.setLabel("bottomRow.csv");
        mwt.singleX = false;

        mwt.addMessreihe( rs1[0] );
        mwt.addMessreihe( rs1[1] );
        mwt.addMessreihe( rs2[0] );
        mwt.addMessreihe( rs2[1] );
        mwt.addMessreihe( rs3[0] );
        mwt.addMessreihe( rs3[1] );
        
        double[] es1 = ESCalc2.calcES( r1ET, r1ET );
        double[] es2 = ESCalc2.calcES( r2ET, r2ET );

        double[] es3 = ESCalc2.calcES( r1ET, r2ET );

        System.out.println( "TEST1: " + es1[0] + " " + es1[1] );
        System.out.println( "TEST2: " + es2[0] + " " + es2[1] );
        System.out.println( "TEST3: " + es3[0] + " " + es3[1] );
        
        int[] R1 = { 0, 0, 0, 0, 1 ,0 , 0};
        int[] R2 = ESCalc2.moveEventRows(R1, 2);
        
        for( int v: R2 ) System.out.print(v + " " );
        
        System.out.println(  );
        
        int[] R3 = ESCalc2.moveEventRowsN(R1, 2);
        
        for( int v: R3 ) System.out.print(v + " " );

        System.out.println( sb.toString() );
        
        op.storeMesswertTabelle( mwt1 );
        op.storeMesswertTabelle( mwt );
        
        op.closeAllWriter();
        
        System.exit(0);
    }
}
