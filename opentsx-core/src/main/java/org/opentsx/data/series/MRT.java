package org.opentsx.data.series;

import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Enumeration;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class MRT {

    private static final Logger LOG = LoggerFactory.getLogger(MRT.class);

    public static double[] calcPeriodeTrend(TimeSeriesObject mr, int d) {

        double[] sum = new double[d];
        double[] count = new double[d];
        double[] trend = new double[d];

        double[][] data = mr.getData();
        int z = mr.yValues.size();

        for (int i = 0; i < z; i++) {
            double v = data[1][i];
            int c = i % d;
            sum[c] = sum[c] + v;
            count[c]++;
        }

        for (int i = 0; i < d; i++) {
            trend[i] = sum[i] / count[i];
        }

        return trend;
    }

    public static TimeSeriesObject normalizeByPeriodeTrend(TimeSeriesObject mr, int d) {

        TimeSeriesObject mr2 = new TimeSeriesObject();
        mr2.setLabel(mr.getLabel() + "_t" + d + "_removed");

        double[] trend = calcPeriodeTrend(mr, d);
        double[][] data = mr.getData();

        int z = mr.yValues.size();

        for (int i = 0; i < z; i++) {
            int c = i % d;
            double t = data[0][i];
            double v = data[1][i] / trend[c];
            mr2.addValuePair(t, v);
        }
        return mr2;
    }

    public static int getPeaksOverTS(int ts, TimeSeriesObject peaks) {
        int c = 0;
        for (double p : peaks.getYData()) {
            if (p >= ts) {
                c++;
            }
        }
        return c;
    }
    
    public static Vector<Double> getPeaksDaysOverTSdouble(double ts, TimeSeriesObject row) {
        Vector<Double> peakINDEX = new Vector<Double>();
        int c = 0;
        double d = 0;
        for (double p : row.getYData()) {
            if (p >= ts) {
                c++;
                peakINDEX.add(d);
            }
            d = d + 1.0;
        }
        return peakINDEX;   }

    public static Vector<Integer> getPeaksDaysOverTS(int ts, TimeSeriesObject row) {
        Vector<Integer> peakINDEX = new Vector<Integer>();
        int c = 0;
        int d = 0;
        for (double p : row.getYData()) {
            if (p >= ts) {
                c++;
                peakINDEX.add(d);
            }
            d++;
        }
        return peakINDEX;
    }
    
    /**
     * Returns the index of every value at or above ts.
     * 
     * @param ts
     * @param mr
     * @return 
     */
    public static Vector<Long> getTimesOfPeaksDaysOverTS(double ts, TimeSeriesObject mr) {
        
        Vector<Long> peaksDays = new Vector<Long>();
        long d = 0;
        double pLAST = 0.0;
        for (double p : mr.getYData()) {
            if (p >= ts ) {
                
                peaksDays.add(d);
                
            }
            d++;
            pLAST = p;
        }

        return peaksDays;
    }

    public static TimeSeriesObject convertDates2Messreihe(Vector<Long> v, String label, String descr) {


        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(label);
        mr.setDescription(descr);

        for (Long l : v) {
            mr.addValuePair(l, 1);
        }
        LOG.debug("converted dates series={} dates={} sum={}", mr.getLabel(), v.size(), mr.summeY());
        return mr;
    }

    public static TimeSeriesObject expand(TimeSeriesObject mr, Calendar start, Calendar end, double sf, boolean MODE_ONE_PER_dt ) {
        
        if( !start.before( end ) ) { 
            throw new IllegalArgumentException( "expand: the end " + end.getTimeInMillis() + " ms is not after the start "
                    + start.getTimeInMillis() + " ms; expected an end after the start" );
        }
        
        TimeSeriesObject m = new TimeSeriesObject();
        m.setLabel( mr.getLabel() );
        m.setIdentifier( mr.getIdentifier() );
        m.setDescription( mr.getDescription() );
        
        // milliseconds
        long s=start.getTimeInMillis();
        long e=end.getTimeInMillis();
        long n = e - s;
         
        
        // number of fields: nn = n / resolution
        int nn = (int) (n / (1000 * sf));
        int[] data = new int[nn];
        
        for( int i = 0 ; i < nn; i++ ) { 
            data[i]=0;
        };
        
        Enumeration en = mr.xValues.elements();
        while( en.hasMoreElements() ) { 
           // in milliseconds
           Double t = (Double)en.nextElement();
           
           if ( t < s ) {
               throw new IllegalArgumentException( "expand: the event at " + t + " ms of " + mr.getLabel()
                       + " is before the start " + s + " ms; expected events at or after the start" );
           }
           int dt = (int)( (t-s) / (1000 * sf));
           if ( t < e && dt < nn ) {
                // one value per field or all of them
                if( MODE_ONE_PER_dt ) data[dt]=1;
                else data[dt]=data[dt]+1;
           }
           else { 
               LOG.debug("skipped value after the last field series={} t={} end={}", mr.getLabel(), t, e);
           }
        }
        for( int i = 0 ; i < nn; i++ ) { 
            m.addValuePair((double)i, (double)data[i]);
        };
        return m;
    }

    public static TimeSeriesObject fromByteArray(byte[] get) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public static Object getAsByteArray() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public static final int STRING_convMode = 0;
    public static final int String_convMode_XSTREAM = 0;
    
    public static TimeSeriesObject deserializeFromXMLString(String m) {
        XStream xstream = new XStream();
        Object o = xstream.fromXML( m ); 
        TimeSeriesObject d = (TimeSeriesObject)o;
        return d;
    }
    
    public static String getAsString( TimeSeriesObject mr ) {
        String s1 = "";
        XStream xstream = new XStream();
        s1 = xstream.toXML( mr );
        
        
        return s1;
    }


}
