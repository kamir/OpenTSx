package org.opentsx.data.series;

import java.util.Vector;

/**
 * Keeps a marker per value, which can be used to split the raw data.
 * 
 * @author kamir
 */
public class MarkableTimeSeriesObject extends TimeSeriesObject {
    
    Vector<String> marker = new Vector<String>();

    public MarkableTimeSeriesObject(String l) {
        super( l );
    }

    public MarkableTimeSeriesObject() {
        super("-");
    }
    
    public void addValue(double y, String mark) {
        super.addValue(y);
        setMarker(mark);
    }

    public void addValuePair(double x, double y, String mark) {
        super.addValuePair(x, y);
        setMarker(mark);
    }

    public void addValuePair(ValuePair vp, String mark) {
        super.addValuePair(vp);
        setMarker(mark);
    }

    /**
     * Formats the series with the marker of every value.
     *
     * @return
     */
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        int size = xValues.size();

        sb.append("#\n# Time series \n# Class : " + this.getClass() + " \n# Label  : " + this.label + "\n#\n# Number of value pairs = " + size + "\n#\n");
        sb.append("#\n#" + this.addinfo + "\n#");
        if ( size > 0 ) {
            sb.append( getStatisticData("# ") +"\n" );
        }
        for (int i = 0 ; i < size; i++  ) {
            double x = (Double)getXValues().elementAt(i);
            double y = (Double)getYValues().elementAt(i);
            String z = (String)marker.elementAt(i);
            
            sb.append(  decimalFormat_X.format(x) + "\t" + decimalFormat_Y.format(y) + "\t" + z + "\n");
        }
        sb.append("#\n" + sbComments.toString() );
        return sb.toString();
    };


    @Override
    public void addValue(double y) {
        super.addValue(y);
        setMarker("");
    }

    @Override
    public void addValuePair(double x, double y) {
        super.addValuePair(x, y);
        setMarker("");
    }

    @Override
    public void addValuePair(ValuePair vp) {
        super.addValuePair(vp);
        setMarker("");
    }

    @Override
    public void addValues(TimeSeriesObject mr) {
        super.addValues(mr);
        setMultiMarker( "", mr.xValues.size() );
    }

    @Override
    public void addValues(Vector<Double> dataRawTIMES) {
        super.addValues(dataRawTIMES);
        setMultiMarker( "", dataRawTIMES.size() );
    }
    
    void setMarker(String mark) {
        marker.add(mark);
    }        
    
    void setMultiMarker(String mark, int nr ) { 
        for( int i = 0; i < nr; i++ ) { 
            setMarker(mark);
        }
    }
    
    
    
}
