/***
 *
 *  Calculates EventSynchronisation for random Event-TimeSeries
 * 
 ***/
package org.apache.hadoopts.statphys.eventsynchronisation.experiments;

//import hadoopts.analysis.eventsynchronisation.ESCalc;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import java.io.IOException;
import java.util.Vector;

import org.apache.hadoopts.statphys.eventsynchronisation.ESCalc2;

/**
 *
 * @author kamir
 */
public class ESMain {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        
        OriginProject op = new OriginProject();
        op.initBaseFolder("/Users/kamir/Documents/Cloudera/github/dissertation/main/FINAL/LATEX/semanpix/ESIllustration");
        op.initFolder( "data" );
        
        Vector<TimeSeriesObject> topRow = new Vector<TimeSeriesObject>();
        Vector<TimeSeriesObject> bottomRow = new Vector<TimeSeriesObject>();
        
        StringBuffer sb = new StringBuffer();
        
        int length = 24 * 365;
        
        int nr1 = 150;
        int nr2 = 1000;
        int nr3 = 50000;
       
        ESCalc2.debug = false;
        ESCalc2.op = op;
        ESCalc2.store_charts = true;
        
        
        int[] r1 = ESCalc2.createEventTS_SINGLE(length, nr1); 
        int[] r2 = ESCalc2.createEventTS_SINGLE(length, nr1); 

        int[] r3 = ESCalc2.createEventTS_INCREMENT(length, nr2); 
        int[] r4 = ESCalc2.createEventTS_INCREMENT(length, nr2); 

        int[] r5 = ESCalc2.createEventTS_INCREMENT(length, nr3); 
        int[] r6 = ESCalc2.createEventTS_INCREMENT(length, nr3); 

        TimeSeriesObject[] rs4 = ESCalc2.checkRows(r1, r2, "Events - binary");
        TimeSeriesObject[] rs5 = ESCalc2.checkRows(r3, r4, "Events - incremental (low density)");
        TimeSeriesObject[] rs6 = ESCalc2.checkRows(r5, r6, "Events - incremental (high density)");
        
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
        
        TimeSeriesObject[] rs1 = ESCalc2.checkRowsInverse(r1ET, r2ET, "ETS - binary");
        TimeSeriesObject[] rs2 = ESCalc2.checkRowsInverse(r3ET, r4ET, "ETS - incremental (low density)");
        TimeSeriesObject[] rs3 = ESCalc2.checkRowsInverse(r5ET, r6ET, "ETS - incremental (high density)");
        
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
