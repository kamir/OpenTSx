package org.opentsx.data.series;

import com.thoughtworks.xstream.XStream;

import java.util.Calendar;
import java.util.Enumeration;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class MRT {

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
            //System.out.println( c + " " + i );
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
     * ermittelt nur die ansteigende Flanke ...
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
                
                // System.out.print( p+ ", " + d + ", " );
            }
            d++;
            pLAST = p;
        }
        System.out.println();

        return peaksDays;
    }

    public static TimeSeriesObject convertDates2Messreihe(Vector<Long> v, String label, String descr) {


        System.out.println(">> size => " + v.size());

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(label);
        mr.setDescription(descr);

        for (Long l : v) {
            mr.addValuePair(l, 1);
        }
        System.out.println("> " + mr.getLabel() + " >> " + mr.summeY());
        return mr;
    }

    public static TimeSeriesObject expand(TimeSeriesObject mr, Calendar start, Calendar end, double sf, boolean MODE_ONE_PER_dt ) {
        
        if( !start.before( end ) ) { 
            System.err.println( "***** ENDE muss nach ANFANG liegen ... " );
            
        }
        
        TimeSeriesObject m = new TimeSeriesObject();
        m.setLabel( mr.getLabel() );
        m.setIdentifier( mr.getIdentifier() );
        m.setDescription( mr.getDescription() );
        
        // MILLISEKUNDEN 
        long s=start.getTimeInMillis();
        long e=end.getTimeInMillis();
        long n = e - s;
         
        
        /** 
         * ANZAHL Felder ...
         * nn = n / AUFLÖSUNG
         */
        int nn = (int) (n / (1000 * sf));
//        System.out.println( "n=" + n );
//        System.out.println( "e=" + e + " s="+s );
//        System.out.println( "nn=" + nn + " sf="+sf );
        int[] data = new int[nn];
        
        for( int i = 0 ; i < nn; i++ ) { 
            data[i]=0;
        };
        
        Enumeration en = mr.xValues.elements();
        while( en.hasMoreElements() ) { 
           // in Millis ...
           Double t = (Double)en.nextElement();
           
           if ( t < e ) {
                int dt = (int)( (t-s) / (1000 * sf));
//                System.out.println( dt + " t= " + t );
                
                // pro Auflösungsfeld nur einen Wert oder alle ?
                if( MODE_ONE_PER_dt ) data[dt-1]=1;
                else data[dt-1]=data[dt-1]+1;
           }
           else { 
               System.out.println( "t is out of range ... " + t + " > " + e );
           }
        }
        for( int i = 0 ; i < nn; i++ ) { 
            m.addValuePair((double)i, (double)data[i]);
//            System.out.println( i + " data " + data[i] );
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
    // public static final int String_convMode_JSON = 1;
    
    public static TimeSeriesObject deserializeFromXMLString(String m) {
        XStream xstream = new XStream();
        // System.out.println(">>> mr.toString() ... " + m );
        Object o = xstream.fromXML( m ); 
        TimeSeriesObject d = (TimeSeriesObject)o;
        // System.out.println( d.getStatisticData( ">>> " ));
        return d;
    }
    
    public static String getAsString( TimeSeriesObject mr ) {
        String s1 = "";
        XStream xstream = new XStream();
        s1 = xstream.toXML( mr );
        
        
        return s1;
    }


}
