package org.opentsx.core;

import org.apache.hadoop.io.Writable;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.*;

/**
 * The data, used in TimeSeriesObject ...
 * 
 * @author Mirko Kämpf
 */
public class TSData implements Writable {

    public void setT0(long t0) {
        this.t0 = t0;
    }

    public void setDt(double dt) {
        this.dt = dt;
    }

    long t0 = 0;

    public void settE(long tE) {
        this.tE = tE;
    }

    long tE = 0;

    double dt = 1000.0;  // in ms

    double[] dataset = null;

    public String label = null;

    /**
     * We take the label and the value array from the TSO.
     *
     * Timing metadata is ignored.
     *
     * @param mr
     * @return 
     */
    public static TSData convertMessreihe(TimeSeriesObject mr) {
        
        TSData data = new TSData();
        data.label = mr.getLabel();

        data.dataset = mr.getYData();
        
        return data;

    }

    public TSData(String id, String label) {
        this.t0 = System.currentTimeMillis();
        this.dt = 1000.0;
        this.dataset = new double[1];
        this.dataset[0] = 0.0;
        this.label = label;
    };

    public TSData() {
        this.t0 = System.currentTimeMillis();
        this.label = "empty_series";
        this.dt = 1000.0;
        dataset = new double[1];
        dataset[0] = 0.0;
    };


    
    public void setData( long t_0, double d_t, double[] d, String l ) {
        label = l;
        dataset = d;
        dt = d_t;
        t0 = t_0;
    }
    public double[] getData() { 
        return dataset;
    }

    public void write(DataOutput d) throws IOException {
        d.writeLong( t0 );
        d.writeDouble( dt);
        d.writeUTF( label );
        d.writeInt( dataset.length );
        for( double v : dataset ) d.writeDouble( v );
    }

    public void readFields(DataInput d) throws IOException {
        t0 = d.readLong();
        dt = d.readDouble();
        label = d.readUTF();
        int z = d.readInt();
        dataset = new double[z];
        for( int i = 0; i < z ; i++ ) dataset[i] = d.readDouble();
    }

    /**
     * Just for internal Tests.
     * 
     * @return 
     */
    public double[] getRandomData() {
        return getRandomData(7200);
    }
    
    /**
     * @param z
     * @return 
     */
    static public double[] getRandomData( int z) {
        double[] d = new double[z];
        for(int i = 0 ; i < z; i++ ) {
            d[i] = RNGWrapper.getStdPoisson( 1.0 );
        }
        return d;
    }

    
    
    public TimeSeriesObject getMessreihe() {
        TimeSeriesObject mr = new TimeSeriesObject();
        for( double y : dataset) { 
            mr.addValue(y);
        }    
        return mr;
    }

    public void setDataset(double[] randomData) {
        this.dataset = randomData;
    }

}
