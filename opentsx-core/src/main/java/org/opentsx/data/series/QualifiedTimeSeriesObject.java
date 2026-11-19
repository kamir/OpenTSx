/**
 *
 *  Not every operation respects the validity flag of the values yet.
 *
 **/

package org.opentsx.data.series;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Enumeration;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class QualifiedTimeSeriesObject extends TimeSeriesObject {

    private static final Logger LOG = LoggerFactory.getLogger(QualifiedTimeSeriesObject.class);

    Vector<Boolean> correct = new Vector<Boolean>();

    @Override
    public QualifiedTimeSeriesObject add(TimeSeriesObject mr2) {
        QualifiedTimeSeriesObject mrNeu = (QualifiedTimeSeriesObject)super.add(mr2);
        checkCorrectnessOfRow( mrNeu );
        return mrNeu;
    }

    /*
     * Checks the value only when it is not a double.
     */
    public void addValue(Object y) {

        double vy = 0.0;
        boolean b = true;

        try {
            vy = (Double)y;
        }
        catch(Exception ex) { 
            b = false;
        } 

        if( !b ) { 
            try {
                vy = Double.parseDouble( (String)y );    
                b=true;
            }
            catch(Exception ex) { 
                
            }
        }
        addValue(vy,b);
    }



    /**
     * The value is taken as correct.
     * @param y
     */
    @Override
    public void addValue(double y) {
        int x = this.getXValues().size() + 1;
        this.addValuePair( 1.0 * x , y );
    }

    private void addValue(double y, boolean b) {
        int x = this.getXValues().size() + 1;
        this.addValuePair( 1.0 * x , y , b);
    }


    private void addValuePair(double x, double y, boolean q) {
        super.addValuePair(x, y);
        correct.add(q);
    }

    @Override
    public void addValuePair(double x, double y) {
        super.addValuePair(x, y);
        correct.add(Boolean.TRUE);
    }

    @Override
    public void calcAverage() {
        int z = this.xValues.size();
        double sum = 0.0;

        int nrOfGood = 0;
        for (int i = 0 ; i < z; i++  ) {
            sum = sum + (Double)getYValues().elementAt(i);
            if ( correct.elementAt(i).booleanValue() ) nrOfGood++;
        }
        av = sum / nrOfGood;
    }

    @Override
    public TimeSeriesObject copy() {
        return super.copy();
    }

    @Override
    public TimeSeriesObject cut(int nrOfValues) {
        return super.cut(nrOfValues);
    }

    @Override
    public TimeSeriesObject diff(TimeSeriesObject mr2) {
        return super.diff(mr2);
    }

    @Override
    public void divide_Y_by(double divBy) {
        super.divide_Y_by(divBy);
    }

    @Override
    public QualifiedTimeSeriesObject setBinningX_average(int bin ) {

        QualifiedTimeSeriesObject mr = new QualifiedTimeSeriesObject();
        mr.binning = bin;
        mr.setLabel( this.getLabel() + " BIN=" +bin );
        Enumeration<Double> en = this.yValues.elements();

        int anzahl = this.yValues.size();

        int rest = anzahl % bin;
        int elements = anzahl - rest;
        int blocks = elements / bin;


        mr.addStatusInfo( "[BINNING] bin=" + bin + "\trest=" + rest + "\tblocks=" + blocks   );

        int i=0;
        int j=0;
        double v = 0;

        // counts only the good values of the block
        int zBlock = 0;
        boolean isBlockGood = false;

        while ( i < elements ) {

            boolean isValueGood = correct.elementAt(i);
            isBlockGood = isBlockGood || isValueGood;

            double vv = en.nextElement();
            if ( isValueGood ) {
                v = v + vv;
                zBlock++;
            }

            if( i % bin == (bin-1) ) {
                
                mr.addValuePair( j , v/zBlock, isBlockGood );
                v = 0.0;
                zBlock = 0;
                isBlockGood = false;
                j++;
            }
            i++;
            LOG.debug("binned value series={} index={} blockGood={} goodInBlock={} sum={}", label, i, isBlockGood, zBlock, v);


        }
        return mr;
    };


    // sums the values
    @Override
    public TimeSeriesObject setBinningX_sum(int bin) {
        return super.setBinningX_sum(bin);
    }

    @Override
    public TimeSeriesObject shift(int offset) throws Exception {
        return super.shift(offset);
    }

    @Override
    public TimeSeriesObject shrinkX(double min, double max) {
        return super.shrinkX(min, max);
    }

    @Override
    public TimeSeriesObject[] split(int length, int anzahl) {
        return super.split(length, anzahl);
    }

    @Override
    public double summeY() {
        return super.summeY();
    }

    /**
     * Applies the rule of the validity check.
     *
     * @param mrNeu
     */
    private void checkCorrectnessOfRow(QualifiedTimeSeriesObject mrNeu) {
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        int size = xValues.size();

        sb.append("#\n# Time series \n# Class : " + this.getClass() + " \n# Label  : " + this.label + "\n#\n# Number of value pairs = " + size + "\n#\n");
        sb.append("#\n#" + this.addinfo + "\n#\n");
        if ( size > 0 ) {
            sb.append( getStatisticData("# ") );
        }
        sb.append( getStatusInfo() );
        sb.append("\n");
        for (int i = 0 ; i < size; i++  ) {
            double x = (Double)getXValues().elementAt(i);
            double y = (Double)getYValues().elementAt(i);
            String q = "-";
            if( correct.elementAt( i ) ) q = "+";
            sb.append( "[" + q + "]  \t" + x + "\t " + y + "\n");
        }
        sb.append("#\n" + sbComments.toString() );
        return sb.toString();
    };


    public void checkKonsistenz() {
        int z = 0;
        int y = 0;
        Enumeration<Boolean> en = correct.elements();
        while( en.hasMoreElements() ) {
            if( en.nextElement() ) z++;
            else y++;
        }        
        LOG.debug("value counts series={} x={} y={} flags={} good={} bad={}", label, xValues.size(), yValues.size(), correct.size(), z, y);
    };


    
}
