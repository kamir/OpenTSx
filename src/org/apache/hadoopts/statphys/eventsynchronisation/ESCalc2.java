
package org.apache.hadoopts.statphys.eventsynchronisation;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.export.OriginProject;
import org.apache.hadoopts.data.series.Messreihe;
import java.util.Collections;
import java.util.Vector;


public class ESCalc2 {
    
    public static boolean debug = true;
    public static OriginProject op;
    public static boolean store_charts;

    /**
     * Calculate the ES measure according to Quiroga et al.
     * 
     * For two given time-series tx and ty, the "Event-Synchronisation" 
     * is calculated. 
     * 	
     * The second delay q_ji(=-q_ij) is not given, as well as Q_ji(=Q_ij).
     * 
     * @param tx
     * @param ty
     * 
     * @return
     *
     * sync:   Strength of Event Synchronization (Q_ij)
     * delay:  Delay quantity (q_ij)
     *
     */
    
    public static double[] calcES(int[] tx , int[] ty) {
        
        double[] data = new double[2];
        
        data[0] = -2.0;
        data[1] = -2.0;

        // NR of event in each row 
        int M1 = tx.length;
        int M2 = ty.length;
        
        if ( M1 < 3 || M2 < 3 ) {
            System.err.println(">>> ERROR <<< # of events is to small!   ::   length(tx) = " + M1 + " length(ty) = " + M2 );
            return data;
        }
        
        double synch = 0.0;  // Q
        double delay = 0.0;  // q     

        double fNorm = Math.sqrt( M1 * M2 );

        if ( M1 == 0 ) return data;
        if ( M2 == 0 ) return data;
                
        double cij = calc_C_ij( tx, ty );
        
        double cji = calc_C_ij( ty, tx );
        
        double f = Math.sqrt( (M1-1)*(M2-1) );

        synch=(cij+cji) / f ;
        delay=(cij-cji) / f ;
 
        data[0] = synch;
        data[1] = delay;
        
        return data;
    }

    /**
     * Length is the number of maximal possible bins to collect events.
     * 
     * Nr is the number of events to place.
     * Each bin can handle multiple events.
     * 
     * Higher density leads finally to a continuous time series.
     * 
     * @param length
     * @param nr
     * @return 
     */
    public static int[] createEventTS_INCREMENT(int length, int nr) {
        
        int[] r1 = new int[length];
        
        for (int i = 0; i < length; i++) {
            r1[i] = 0;
        }

        // index of current event 
        int e = 0;
        while (e < nr) {
            // At a random position in the full series we add an event 
            int pos = (int) (Math.random() * (double) (length));
            
            // Just register this event be increasing a counter...
            r1[pos]++;
            
            e++; // next event
            
        }
        return r1;
    }
    
    /**
     * Length is the number of maximal possible "rastered" 
     * events.
     * 
     * Nr is the number of events to place.
     * 
     * Events are placed in individual bins of equal distance randomly.
     * Multiple events per bin are collapsed to one event.
     * 
     * @param length
     * @param nr
     * @return 
     */
    public static int[] createEventTS_SINGLE(int length, int nr) {
        
        int[] r1 = new int[length];
        
        for (int i = 0; i < length; i++) {
            r1[i] = 0;
        }

        // index of current event 
        int e = 0;
        while (e < nr) {
            
            // At a random position in the full series we add an event 
            int pos = (int) (Math.random() * (double) (length));
            
            // only one event is registered 
            if (r1[pos] != 1) {
                r1[pos] = 1;
                e++; // next event
            }
            
        }
        return r1;
    }
    
    public static int[] moveEventRows2(int[] r1, int dt) {
        
        // shift the series by dt steps
        
        int[] r2 = new int[r1.length];

        int j = 0;
        for (int i = dt; i < r1.length; i++) {
            r2[j] = r1[i];
            j++;
        }
        for (int i = 0; i < dt; i++) {
            r2[j] = r1[i];
            j++;
        }
        return r2;
    }
    
    /**
     * Verschiebung der Reihen für Methoden-Tests
     * 
     * @param r1
     * @param dt
     * @return 
     */
    public static int[] moveEventRows(int[] r1, int dt) {
        int[] r2 = new int[r1.length];
        
        int j = 0;
        for (int i = r1.length - dt; i < r1.length; i++) {
            r2[j] = r1[ i];
            j++;
        }
        for (int i = 0; i < r1.length - dt; i++) {
            r2[j] = r1[i];
            j++;
        }
        return r2;
    }
    
    public static int[] moveEventRowsN(int[] r1, int dt) {

        int[] r2 = new int[r1.length];

        int j = 0;
        for (int i = dt; i < r1.length-dt; i++) {
            r2[j] = r1[ i];
            j++;
        } 
        for (int i = 0; i < dt; i++) {
            r2[j] = r1[i];
            j++;
        }
        return r2;
    }

    
    /**
     * Konversion of equidistant counter time series into 
     * inter event time time series. The result is a sparse
     * representation.
     * 
     * Each value represents the time index for which an event or 
     * even multiple events have been found. We loose the information
     * about how many events per unit are detected. Either 0 or a value
     * greater than zero is registered.
     * 
     * @param rowWithEventCounterPerTimeUnit
     * @return 
     */
    public static int[] getEventIndexSeries(int[] rowWithEventCounts) {
        
        Vector<Integer> rr2 = new Vector<Integer>();
        int et = 0;
        
        for (int i : rowWithEventCounts) {
            et++;
            if (i != 0) { 
                
                // wann war der EVENT?
                rr2.add(et);
            
                // Here we loose information !!! 
                // if multiple events have been counted at this point in time.
                
            }    
        }    
        int p = 0;
        int[] r1 = new int[rr2.size()];
 
        for (int i : rr2) {
            r1[p] = i;
            p++; 
        }
        return r1;
    }

    public static void checkRows(int[] r1, int[] r2) {
        checkRows(r1, r2, "ESCalc" );
    }

    /**
     * Calculate the average inter event time for a series.
     * 
     * @param r1 
     * @return 
     */
    public static double getAVIET(int[] r1) {
        Vector<Integer> r = new Vector<Integer>();
        int iet = 0;
        for (int i : r1) {
            if (i == 1) {
                r.add(iet);
                iet = 0;
            } else {
                iet++;
            }
        }
        int sum = 0;
        for (int i : r) {
            sum = sum + i;
        }
        double av = (double) sum / (double) r.size();
        return av;
    }

    /**
     *  Erzeuge Messreihen und zeige diese dann an. 
     */
    public static Messreihe[] checkRowsInverse(int[] r1, int[] r2, String title) {
        
        Messreihe a1 = new Messreihe();
        Messreihe a2 = new Messreihe();

        Messreihe[] r = new Messreihe[2];
        r[0] = a1;
        r[1] = a2;

        int z = 0;
        for (int i : r1) {
            z++;
            a1.addValuePair( i , z );
        }
        z = 0;
        for (int i : r2) {
            z++;
            a2.addValuePair( i, z );
        }

        a1.setLabel( "z=" + z );
        a2.setLabel( "z=" + z );

        String xLabel = "time";
        String yLabel = "event index";
        
//        MultiChart.open(r , title , "time", "events" , true);
        op.storeChart(r, true, title, title + "_", xLabel, yLabel );
        op.storeChart(r, false, "", title, xLabel, yLabel );

        return r;
    }

        /**
     *  Erzeuge Messreihen und zeige diese dann an. 
     */
    public static Messreihe[] checkRows(int[] r1, int[] r2, String title) {
        
        Messreihe a1 = new Messreihe();
        Messreihe a2 = new Messreihe();

        Messreihe[] r = new Messreihe[2];
        r[0] = a1;
        r[1] = a2;

        for (int i : r1) {
            a1.addValue(i);
        }
        for (int i : r2) {
            a2.addValue(i);
        }

        a1.setLabel( "rho(0:" + ESCalc2.getDensity( a1, 0.0 ) +
                     "; 1:" + ESCalc2.getDensity( a1, 1.0 ) +
                     "; 10:" + ESCalc2.getDensity( a1, 10.0 ) + ")");

        a2.setLabel( "rho(0)=" + ESCalc2.getDensity( a2, 0.0 ) +
                     "; 1:" + ESCalc2.getDensity( a2, 1.0 ) +
                     "; 10:" + ESCalc2.getDensity( a2, 10.0 ) + ")");


        String xLabel = "time";
        String yLabel = "nr of events";
        
//        MultiChart.open(r , title , xLabel, yLabel , true);
        op.storeChart(r, true, title, title + "_", xLabel, yLabel );
        op.storeChart(r, false, "", title, xLabel, yLabel );
       
        return r;
    }

    /**
     * Implementation of ES measure according to Quiroga et al. 
     * 
     * @param tx
     * @param ty
     * @return 
     */
    private static double calc_C_ij(int[] tx, int[] ty ) {
        double cij = 0.0;
        int M1 = tx.length;
        int M2 = ty.length;
                    
        Vector<Double> temp = new Vector<Double>();

        for( int i=1; i < M1-1; i++) {
          for( int j=1; j < M2-1; j++) {

            temp.clear();

            double a = tx[i+1]-tx[i];
            double b = tx[i]-tx[i-1];
            double c = ty[j+1]-ty[j];
            double d = ty[j]-ty[j-1];
            
            temp.add( a );
            temp.add( b );
            temp.add( c );
            temp.add( d );
            double tau = Collections.min( temp );
            tau = tau * 0.5;
            
//            System.err.println( a + " " + b + " " + c + " " + d + " " + " --- " + tau );
            
            // here we need the event times ... not the distances
            
            double e = tx[i];
            double f = ty[j];
            
            double DIFF = e-f;
            double dij = 0.0;

            if( 0.0 < DIFF && DIFF <= tau ) { 
              dij=1.0; 
            }
            else if ( e == f ) {
                    dij = 0.5;  
            }
            else {
                dij = 0.0;
            }
            cij = cij + dij;            
          }
        }

        return cij;
    }

    private static double getDensity(Messreihe a1, double ts ) {
        int i = 0;
        for( Object y : a1.getYValues() ) {
           if ( (Double)y > ts ) i++;
        }
        double rho = (double)i / (double)a1.getYValues().size();
        return rho;
    }
    
}
