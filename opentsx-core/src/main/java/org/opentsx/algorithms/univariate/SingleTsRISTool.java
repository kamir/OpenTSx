package org.opentsx.algorithms.univariate;

/*
 *  wird auf eine einzelne Reihe angewendet ...
 * 
 *  schreibt in eine Datei ...
 * 
 */
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;
import org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2;

import java.io.IOException;
import java.io.Writer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class SingleTsRISTool extends SingleRowTSO {
    
    int debugViews = 0;
    int maxDebugViews = 0;

    public void init() {
         
         ReturnIntervallStatistik2.initRqCollectors();
         
         risCollector = new ReturnIntervallStatistik2("RISdata");
         risCollector.isContainerInstanz = true;
         
         av11 = new Vector<TimeSeriesObject>();
         av21 = new Vector<TimeSeriesObject>();
         av31 = new Vector<TimeSeriesObject>();

         av12 = new Vector<TimeSeriesObject>();
         av22 = new Vector<TimeSeriesObject>();
         av32 = new Vector<TimeSeriesObject>();
    }
    
    public void finish() {
        try {
            risCollector.showData();
        } 
        catch (Exception ex) {
            Logger.getLogger(SingleTsRISTool.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    static public Vector<TimeSeriesObject> av11 = new Vector<TimeSeriesObject>();
    static public Vector<TimeSeriesObject> av21 = new Vector<TimeSeriesObject>();
    static public Vector<TimeSeriesObject> av31 = new Vector<TimeSeriesObject>();

    static public Vector<TimeSeriesObject> av12 = new Vector<TimeSeriesObject>();
    static public Vector<TimeSeriesObject> av22 = new Vector<TimeSeriesObject>();
    static public Vector<TimeSeriesObject> av32 = new Vector<TimeSeriesObject>();

    public SingleTsRISTool(String[] args) {
    }

    public SingleTsRISTool() {   }

    static boolean sLN = true;
    static boolean sLOG = true;
    static boolean scaleRQ = true;
    
    private double linFit_MIN = 1.0;
    private double linFit_MAX = 3.0;
    
    public static ReturnIntervallStatistik2 risCollector = null;

    /**
     * OUTPUT is not collected, it is written directly to the FW
     */
    @Override
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject r, Writer explodeWriter) throws Exception {

        String line = "";

        ReturnIntervallStatistik2 rr1;
        rr1 = new ReturnIntervallStatistik2("R1");
               
        // calc averag inter-event time
        double vRQ1 = calcAverageOfDist(r.yValues );
        System.out.println( vRQ1 );
        
        rr1.setRQ(vRQ1);
        
        // create interevent time series ... on the way ...
        // set data
        rr1._addData( r.yValues, vRQ1 );        
        
        if ( debugViews < maxDebugViews ) {
            rr1.showData();
            debugViews++;
                    
        };    
             
        risCollector.add( rr1 );



 

        line = line + "\t" + vRQ1 + "\t"; //  + vRQ2 + "\t" + vRQ3;



        /**
         * Problem: in den einzelnen Reihen kann man nicht sinnvoll den Anstieg
         * bestimmen !!!
         *
         */

        try {
            fw.write(r.getLabel() + "\t" + line + "\n");
            fw.flush();
        } 
        catch (IOException ex) {
            Logger.getLogger(SingleTsRISTool.class.getName()).log(Level.SEVERE, null, ex);
        }

        return r;

    }

    /**
     *
     *
     * @param resultWriter
     * @param reihe ..
     * @return ...
     * @throws Exception
     */
    public String processReihe(Writer resultWriter, TimeSeriesObject reihe) throws Exception {
        return "... (" + this.getClass().getName() + ") ";
    }

    private double calcAverageOfDist(Vector<Double> rr1) {
        double sum = 0.0;
        double c = 0.0;

        double last = 0;
        double delta = 0;
        
        for( double l : rr1 ) { 
            delta = l - last;
            sum = sum + delta;
            last = l;
            c++;
        }
        return sum / (1.0*c);
    }
    
    private double calcAverage(Vector<Double> rr1) {
        double sum = 0.0;
        double c = 0.0;
       
        for( double l : rr1 ) { 
            sum = sum + l;
            c++;
        }
        return sum / (1.0*c);
    }
}
