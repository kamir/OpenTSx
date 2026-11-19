
package org.opentsx.algorithms.statistics;

import org.opentsx.data.series.TimeSeriesObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Vector;
import org.apache.commons.math3.stat.Frequency;

/**
 *
 * @author kamir
 */
public class HaeufigkeitsZaehlerDouble {

    private static final Logger LOG = LoggerFactory.getLogger(HaeufigkeitsZaehlerDouble.class);

    public double min = -100.0;
    public double max = 100.0;

    public Frequency f = new Frequency();

    public int intervalle = 2000;

    public Vector<Double> dists = new Vector<Double>();

    public double[] getDataArray() {

        int i = 0;
        
        Vector<Double> clear = new Vector<Double>();
        
        for( Double d : this.dists ) {
            if ( !d.isNaN() ) {
                clear.add( d );
            }
        }
        double[] data = new double[ this.dists.size() ];
        for( double d : clear ) {
           data[i] = d;
           i++;
        }
        return data;
    }

    public String label = "HISTOGRAM";

    public TimeSeriesObject mr = null;
    public TimeSeriesObject mr2 = null;
    public String folder = ".";

    public TimeSeriesObject mrCUMUL = new TimeSeriesObject();
    TimeSeriesObject mrCONF = new TimeSeriesObject();


    public double SUMME = 0.0;
    
    private boolean debug = false;


    /**
     * Adds a value to the frequency count.
     * 
     * @param dates
     */
    public void addData(Double value) {
         dists.add(value);
    };
    
    public void addData(Vector<Double> data) {
        for( double v: data) addData(v);
    };

    /**
     * Adds a series of points in time and computes all distances between
     * the points of this series.
     *
     * @param dates
     */
    public void addDataForAll_DISTS(Vector<Double> dates) {

        
        int anzahl = dates.size();

        double[][] l = new double[anzahl][anzahl];

        for (int i = 0; i < anzahl; i++) {
            for (int j = 0; j < anzahl; j++) {

                double timeA = dates.elementAt(i);
                double timeB = dates.elementAt(j);

                double dist = Math.abs(timeA - timeB);

                l[i][j] = dist;

                dists.add(dist);

                if (dist > max) {
                    max = dist;
                }
                if (dist < min) {
                    min = dist;
                }

            }
        }

    }



    public void calcWS() {

        mr = new TimeSeriesObject( label );
        int st = intervalle;
        
        int anz = dists.size();
        mr2 = new TimeSeriesObject( label + "/"+anz );

        Vector<Long> steps = new Vector<Long>();
        for (int i = 0; i < dists.size(); ++i) {
            f.addValue(Double.valueOf(dists.elementAt(i)));
        }



        min--;

        double delta = (max - min + 1) / st;

        if( debug ) LOG.debug("histogram bins histogram={} width={}", label, delta);

        long vor = 0;

        mr.setLabel(label);
        
        
        for (int i = 0; i < st; i++) {

            double x = i * delta + min + 1;
            long w = f.getCumFreq(Double.valueOf(x));

            long y = w - vor;
            vor = w;

            if( debug ) LOG.debug("histogram bin histogram={} x={} count={} cumulative={}", label, x, y, vor);

            mrCUMUL.addValuePair(x,vor);
            
            if ( y != 0 ) {
                mr.addValuePair(x, y);
                double vv =  y /(double)anz;
                mr2.addValuePair(x, vv );
                
            }

        }

        SUMME = vor;

        long f30 = f.getCumFreq(Double.valueOf(min));

        long f20 = f.getCumFreq(Double.valueOf(max));

        long f00 = f.getSumFreq();




    }

    public void store() throws IOException {
            FileWriter fw = new FileWriter( folder + label );
            fw.write( mr.toString() );
            fw.flush();
            fw.close();
    };

    public TimeSeriesObject getConfidence() {
        
        mrCONF = new TimeSeriesObject("CONFIDENC Check");

        for( int i = 0; i < mrCUMUL.yValues.size(); i++ ) {
            Double perc = (Double)mrCUMUL.yValues.elementAt(i) / SUMME;
            if ( perc.isInfinite() ) perc = 1.0;

            LOG.debug("confidence histogram={} x={} share={}", label, mr.xValues.elementAt(i), perc);
            this.mrCONF.addValuePair( (Double)mr.xValues.elementAt(i) , perc );
        }
        return mrCONF;
    };

    public TimeSeriesObject getHistogram() {
        mr.setLabel(labelOfHist);
        return mr;
    }
    
    public TimeSeriesObject getHistogramNORM() {
        return mr2;
    }

    public TimeSeriesObject getHistogram(double NR_OF_SHUFFLINGS) {
        return mr.scaleY_2( NR_OF_SHUFFLINGS );
    }

    String labelOfHist = null;
    public void setLabel(String s) {
        labelOfHist = s;
    }
}
