package org.opentsx.algorithms.ris;

/**
 * Computes the distribution of return intervals.
 * 
 * - The values are scaled with Rq when calcMessreihen() is called and
 *   when the instance is added to a container instance
 * - A container instance combines the results of several interval
 *   series
 * - A container instance gives a histogram of the Rq values
 * - Shows the data of single series
 */

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.exporter.OriginProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Enumeration;
import java.util.Vector;

import org.opentsx.algorithms.ris.experimental.ReturnIntervallStatistik2;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehler;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehlerDouble;


/**
 * 
 * Results of single return interval statistics can only be combined
 * for series with the same Rq.
 * 
 * When the Rq values differ a lot, the values are scaled with Rq.
 * 
 * The scaled values can be combined.
 * 
 * add( RIS2 )                  => collect in the container
 * _addData( Vector<Double> ) => points in time in milliseconds
 * addDistData( Vector<Double)  => collect time intervals
 * 
 * 
 * 
 * @author Mirko Kämpf
 */

public class RISTool {

    private static final Logger LOG = LoggerFactory.getLogger(RISTool.class);
    
    static public int[] grenzen = { 1,8,16,32,64,128,256,512,1024 };
    
    public static HaeufigkeitsZaehlerDouble debug1[] = null;
    
    /** 
     * Marks a container instance; the data methods check it.
     */
    public boolean isContainerInstanz = false;

    
    public String label;
    public String name;
    public String folder = null;
    
    /*
     * plain counts
     */
    public TimeSeriesObject mrHaeufigkeit = new TimeSeriesObject();
    /*
     * normalized frequencies, so the sum of all values is 1
     */
    public TimeSeriesObject mrVerteilung = new TimeSeriesObject();
    public TimeSeriesObject mrVerteilungSkaliert = new TimeSeriesObject();

    private int binning = 300;   // number of values per interval
    private int scale = 24;    // largest value
    
    // default, not set in the constructor
    private static int binningSKALIERT = 8;   // number of values per interval
    private static int scaleSKALIERT = 5;    // largest value

    private double _Rq = 0.0;
    private int zElements = 0;

    /* 
     * the binned values, an array of counters
     */
    int data[] = null;
    int dataSKALIERT[] = null;
    double probSKALIERT[] = null;
    
    Vector<Double> dataRawFILTERED = null;
    Vector<Double> dataRawTIMES = null;
    
    /**
     * @deprecated Has no effect; the level of the SLF4J logger of this class decides.
     */
    @Deprecated
    static public boolean debug = false;
    /**
     * @deprecated Has no effect; the level of the SLF4J logger of this class decides.
     */
    @Deprecated
    static public boolean verbose = false;
    
    // smallest value of r that is used
    public static double offset = 0;

    public RISTool(String l, int bin, int s) {
        binning = bin;
        scale = s;
        name = l;
        initArray();
        label = this.getClass().getName();
        dataRawFILTERED = new Vector<Double>();
        dataRawTIMES = new Vector<Double>();
    }

    RISTool(int binning, int scale) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
 
    private void clear() {
        initArray();
    }

    private void initArray() {
        
        data = new int[ ( binning * scale ) + 1 ];
        
        dataSKALIERT = new int[ ( binningSKALIERT * scaleSKALIERT ) + 1 ];
        probSKALIERT = new double[ ( binningSKALIERT * scaleSKALIERT ) + 1 ];
        for( int i = 0; i < dataSKALIERT.length ; i++ ) { 
            dataSKALIERT[i] = 0;
        }
        for( int i = 0; i < probSKALIERT.length ; i++ ) { 
            probSKALIERT[i] = 0.0;
        }
    }

    /** 
     *
     * Collects the RIS of many series of a group whose values are not
     * scaled with Rq yet.
     * 
     */
    Vector<Double> allRqs = new Vector<Double>();
    TimeSeriesObject test = new TimeSeriesObject();
    public void add( RISTool ris ) throws Exception {
        if ( !this.isContainerInstanz ) {
            throw new IllegalStateException( "add: " + name + " is not a container; set isContainerInstanz to true before adding results");
        }
        
        if ( ris.data.length != data.length ) {
            throw new IllegalArgumentException( "add: " + ris.name + " has " + ris.data.length + " bins and " + name
                    + " has " + data.length + "; expected the same binning and scale");
        }
        
        if ( ris.dataRawFILTERED == null ) { 
            throw new IllegalArgumentException( "add: " + ris.name + " has no return intervals; add intervals with addDistData first");
        }
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("adding result container={} result={} rq={}", name, ris.name, ris._Rq);
        }
        
        // an empty result has NaN probabilities (0 of 0 intervals) and adds nothing
        if ( ris.dataRawFILTERED.size() < 1 ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("skipped empty result container={} result={}", name, ris.name);
            }        
            return;
        } 

        for( int i = 0; i < probSKALIERT.length; i++ ) { 
            double prob = ris.probSKALIERT[i];
            if ( Double.isNaN( prob ) || Double.isInfinite( prob ) ) {
                throw new IllegalArgumentException( "add: probability " + i + " of " + ris.name + " is " + prob
                        + "; expected a finite probability" );
            }
        }

        checkSetup( ris );

        _checkRIS( ris );

        for( int i = 0; i < probSKALIERT.length; i++ ) { 
            probSKALIERT[i] = probSKALIERT[i] + ris.probSKALIERT[i];
            dataSKALIERT[i] = dataSKALIERT[i] + 1;
            
        }
        
        allRqs.add(ris._Rq);
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("added result container={} result={} intervals={}", name, ris.name, ris.dataRawFILTERED.size());
        }
        
    }

    private void checkSetup( RISTool ris ) {
        int n = ris.anzFiltered;
        if ( n <= grenzen[0] || n > grenzen[grenzen.length - 1] ) {
            return;
        }
        String result = ris.name + ", which has " + n + " intervals";
        if ( projekt == null ) {
            throw new IllegalStateException( "add: RISTool.projekt is not set; set RISTool.projekt and RISTool.logFileNames"
                    + " and call RISTool.initRqMIN() before adding " + result );
        }
        if ( logFileNames == null || logFileNames.length < grenzen.length - 1 ) {
            throw new IllegalStateException( "add: RISTool.logFileNames has "
                    + ( logFileNames == null ? "no" : String.valueOf( logFileNames.length ) ) + " names; set "
                    + ( grenzen.length - 1 ) + " names, one per interval range, before adding " + result );
        }
        if ( rqMIN == null ) {
            throw new IllegalStateException( "add: RISTool.rqMIN is not set; call RISTool.initRqMIN() before adding " + result );
        }
        if ( offset != 0 && debug1 == null ) {
            throw new IllegalStateException( "add: RISTool.debug1 is not set while RISTool.offset is " + offset
                    + "; call RISTool.initRqCollectors() before adding " + result );
        }
    }

    /** 
     * Adds the intervals between consecutive times in milliseconds as hours with addDistData, or writes them to the
     * log LENGTH when they add up to more than 7200 hours. Equal times give an interval of 0, which addDistData
     * refuses while offset is 0.
     *  
     * @throws IllegalArgumentException when a time is not finite, is before the time before it or is more than
     *         Double.MAX_VALUE after it
     **/
    public void _addData( Vector<Double> times ) throws Exception {

        if ( this.isContainerInstanz ) {
            throw new IllegalStateException("_addData: " + name + " is a container; add times to a result that is not a container");
        }

        if ( LOG.isDebugEnabled() ) {
            LOG.debug("computing intervals result={} intervals={}", name, Math.max(0, times.size() - 1));
        }

        Vector<Double> intervals = new Vector<Double>();
        
        // points in time
        Enumeration<Double> en = times.elements();
        
        // first element (index 0)
        double vor = 0.0;
        if ( en.hasMoreElements() ) {
            vor = en.nextElement();
            if ( !Double.isFinite( vor ) ) {
                throw new IllegalArgumentException( "_addData: time 0 of " + name + " is " + vor + "; expected a finite time" );
            }
        }
        
        int k = 0;
        while( en.hasMoreElements() ) {
            Double now = en.nextElement();
            k++;
            if ( !Double.isFinite( now ) ) {
                throw new IllegalArgumentException( "_addData: time " + k + " of " + name + " is " + now + "; expected a finite time" );
            }
            if ( now < vor ) {
                throw new IllegalArgumentException( "_addData: time " + k + " of " + name + " is " + now + ", before time "
                        + (k - 1) + " at " + vor + "; expected times that do not decrease" );
            }
            double dist = now - vor;
            if ( Double.isInfinite( dist ) ) {
                throw new IllegalArgumentException( "_addData: time " + k + " of " + name + " is " + now
                        + ", more than Double.MAX_VALUE after time " + (k - 1) + " at " + vor
                        + "; expected times whose difference is finite" );
            }
            // intervals in hours
            dist = dist / (60 * 60 * 1000);
            intervals.add(dist);
            if ( LOG.isDebugEnabled() ) LOG.debug("interval result={} interval={} now={} before={}", name, dist, now, vor);
            vor = now;
        } 

        if ( preCheck( intervals ) ) {
            addDistData(intervals);
        }
        else { 
            errosLENGTH++;            
        }
        dataRawTIMES = intervals;
        
    }
    
    public static int errosLENGTH = 0;
    
    
    /**
     * kept for the whole run
     */
    public static HaeufigkeitsZaehler anzRowRAW = new HaeufigkeitsZaehler("length of rows (raw)");
    public static HaeufigkeitsZaehler anzRowsFILTERED = new HaeufigkeitsZaehler("length of rows (filtered, offset="+RISTool.offset+"h)");

    int cutOff = 0;
    public int anzFiltered = 0;
    /**
     * Adds a vector of return intervals.
     *
     * The intervals are sorted into the bins and scaled with Rq
     * at once.
     *
     * The existing array is cleared first.
     *
     * @throws IllegalStateException when this result is a container, when an interval is 0 or negative while offset
     *         is 0, or when intervals are left out and RISTool.projekt is not set or has no log of the name set with
     *         setLofFileNameKey_1
     * @throws IllegalArgumentException when an interval is NaN or infinite
     * @throws UncheckedIOException when the log line of the intervals left out cannot be written
     **/
    public void addDistData( Vector dists ) throws Exception {
        if ( this.isContainerInstanz ) {
            throw new IllegalStateException("addDistData: " + name + " is a container; add intervals to a result that is not a container");
        }

        for( int k = 0; k < dists.size(); k++ ) {
            double da = (Double) dists.elementAt(k);
            if ( !Double.isFinite( da ) ) {
                throw new IllegalArgumentException( "addDistData: interval " + k + " of " + name + " is " + da + "; expected a finite interval" );
            }
        }
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("adding intervals result={} intervals={}", name, dists.size());
        }
        
        long anzahlVor = (long)dists.size();
        Vector<Double> kept = new Vector<Double>();
        int cut = 0;
        Enumeration en1 = dists.elements();
        while( en1.hasMoreElements() ) {
            double da = (Double)en1.nextElement();
            if ( da >= offset && da != 0.0  ) {
                kept.add( da );
            }
            else {
                cut++;
            }
        }

        if ( cut != 0 && offset == 0 ) {
            throw new IllegalStateException( "addDistData: " + cut + " intervals of " + name
                    + " are 0 or negative; expected positive intervals while offset is 0" );
        }

        long anzahlNach = (long)kept.size();
        long delta = (anzahlVor-anzahlNach);
        
        if ( delta > 0) {
            String line = label + "\t" + anzahlVor + "\t" + anzahlNach + "\t" + delta + "\n";
            logLineToFile( logFileKey, line);
        }

        anzRowRAW.addData( anzahlVor  );
        dataRawFILTERED = kept;
        cutOff = cut;
        anzRowsFILTERED.addData( anzahlNach );
        
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("filtered intervals result={} cutOff={} offset={}", name, cutOff, offset);
        }
        
        anzFiltered = dataRawFILTERED.size();
        
        clear();
        
        // Rq of all intervals
        double Rq_alt = ReturnIntervallStatistik2.meanOf( dists );
        
        // Rq of the filtered intervals
        double Rq_filtered = ReturnIntervallStatistik2.meanOf( dataRawFILTERED );
        zElements = dataRawFILTERED.size();
       
        if ( LOG.isDebugEnabled() ) LOG.debug("mean interval result={} rqAll={} rqFiltered={}", name, Rq_alt, Rq_filtered);
        _Rq = Rq_filtered;

        
        
        
        // count the filtered intervals scaled with their Rq
        Enumeration en = dataRawFILTERED.elements();
        while( en.hasMoreElements() ) {
             double dist1 = (Double) en.nextElement();
             double v = dist1  / (double)Rq_filtered;
             int index = (int)(v * (double)scaleSKALIERT);
             if ( index >= dataSKALIERT.length ) index = dataSKALIERT.length-1;
             dataSKALIERT[index]++;
        }
        
        // probability of each bin
        for( int i = 0; i < probSKALIERT.length; i++ ) { 
            probSKALIERT[i] = (double)( dataSKALIERT[i] / (double)zElements );
        }              
    }
    
    double epsilon = 1e-6; 
    
    public static void logLineToFile( String key, String line ) { 
        if ( projekt == null ) {
            throw new IllegalStateException( "logLineToFile: RISTool.projekt is not set; set RISTool.projekt before"
                    + " writing to the log " + key );
        }
        try {
            projekt.logLine(key, line);
        } 
        catch (IOException ex) {
            throw new UncheckedIOException( key + ": cannot write the log line (" + ex + ")", ex );
        }
    }
    
    public void calcMessreihen() throws Exception {
        if ( this.isContainerInstanz ) { 
            calcContainerMR();
        }
    }
    
    private void calcContainerMR() throws Exception {
        
        mrVerteilungSkaliert = new TimeSeriesObject( "dist_scaled");
        
        for (int i = 0; i < dataSKALIERT.length; i++) {
            if ( dataSKALIERT[i] != 0 ) {
                double vx = ((double)i + 0.5) / scaleSKALIERT;
                double vy = probSKALIERT[i] / (double)dataSKALIERT[i];
                if ( vy != 0.0 ) {
                    double logVy = Math.log(vy);
                    if ( Double.isInfinite( logVy ) || Double.isNaN( logVy ) ) {
                        LOG.debug("skipped bin without a finite logarithm container={} x={} y={}", name, vx, vy);
                    }
                    else {
                        mrVerteilungSkaliert.addValuePair( vx, logVy );
                    }
                }
            }
        }
    }
    
    
    public void showData() throws Exception {
        calcMessreihen();
        v1 = new Vector<TimeSeriesObject>();
        showRawData();      
        showVerteilungen();
        MultiChart.open(v1,true, name);
        if ( !this.isContainerInstanz ) MultiChart.open(v2,true, 
                this.name + "(RawData)");
    }
    
    Vector<TimeSeriesObject> v1 = new Vector<TimeSeriesObject>();
    Vector<TimeSeriesObject> v2 = new Vector<TimeSeriesObject>();
    
    private void showVerteilungen() {
        // distributions
        if ( !this.isContainerInstanz ) v1.add( this.mrVerteilung);
        v1.add( this.mrVerteilungSkaliert);        
    }


    private void showRawData() {
        // raw data (intervals)
        TimeSeriesObject mr = new TimeSeriesObject( this.name + " (rawdata)");
        for( double y: this.dataRawFILTERED ) {
            mr.addValue(y);
        }
        v2.add(mr);
     }

    @Override
    public String toString() {
        return toString_Verteilung();
    }

    public String toString_Haeufigkeit() {
        StringBuffer sb = new StringBuffer();
        sb.append("\n#---------------------\n# " +
                 label + "\n#---------------------\n");
        sb.append("# Rq            =" + _Rq + "\n");
        sb.append("# Values        =" + zElements + "\n");
        sb.append("# Binning       =" + binning + "\n" );
        sb.append("# [ --- frequency --- ]" );
        mrHaeufigkeit.setLabel(sb.toString());
        return mrHaeufigkeit.toString();
    }


    public String toString_Verteilung() {
        StringBuffer sb = new StringBuffer();
        sb.append("\n#---------------------\n# " +
                 label + "\n#---------------------\n");
        sb.append("# Rq            =" + _Rq + "\n");
        sb.append("# Values        =" + zElements + "\n");
        sb.append("# Binning       =" +binning + "\n" );
        sb.append("# Scale         =" +scale + "\n" );
        sb.append("# [ --- distribution --- ]" );
        mrVerteilung.setLabel(sb.toString());
        return mrVerteilung.toString();
    }


    public void store() throws IOException {
        try (FileWriter fw = new FileWriter( folder + label )) {
            fw.write( mrVerteilung.toString() );
        }

        if ( LOG.isDebugEnabled() ) {
            LOG.debug("stored distribution result={} file={} bins={}", name, folder + label, mrVerteilung.xValues.size());
        }
    };

    public void setRQ(double vRQ) {
        _Rq = vRQ;
    }
    
    
    public double getRq() {
        return _Rq;
    }

    /**
     * Computes Rq from the filtered intervals.
     * @param offset 
     */
    int zahl = 0;
    int verlust = 0;
    private void calcRq(double offset) throws Exception {
        if ( this.isContainerInstanz ) {
            throw new IllegalStateException( "calcRq: " + name
                    + " is a container; R_q is computed for a result that is not a container" );
        }
        zahl = 0;
        verlust = 0;
        double summe = 0.0;
        Enumeration<Double> en = this.dataRawFILTERED.elements();
        while( en.hasMoreElements() ) {
            Double v = en.nextElement();
            if ( v > offset ) {
                summe = summe + v;
                zahl = zahl + 1;
            }
            else { 
                verlust = verlust + 1;
            }
        }
        setRQ( summe / zahl );
    }
        private static boolean storeSingleDistributions = false;
    
    static FileWriter fw;
    
    static Vector<TimeSeriesObject> mrv = null;
    
    static int risBinning = 300*24;  // resolution in hours
    static int risScale = 1;

    static int maxB = 5; // number of input files
    static int maxROWS = -1; // default -1
    
    public static String logFileNames[] = null;
    public static OriginProject projekt = null;
    
    static int anzNullEdits[] = null;

    static HaeufigkeitsZaehlerDouble[] mrRQ = null;
    static HaeufigkeitsZaehlerDouble hzANZ = new HaeufigkeitsZaehlerDouble();
    static HaeufigkeitsZaehlerDouble[] mrANZ = null;
    
    public static void initRqCollectors() {
        
        anzNullEdits = new int[maxB];
        RISTool.debug1 = new HaeufigkeitsZaehlerDouble[grenzen.length];
                
        mrRQ = new HaeufigkeitsZaehlerDouble[grenzen.length];
        mrANZ = new HaeufigkeitsZaehlerDouble[grenzen.length];
        for( int i = 0; i < grenzen.length; i++ ) { 
            mrRQ[i] = new HaeufigkeitsZaehlerDouble(); // "R_q " + labels[i] );
            mrRQ[i].intervalle = 300*24;
            mrRQ[i].max = 300*24;

            mrANZ[i] = new HaeufigkeitsZaehlerDouble();
            mrANZ[i].intervalle = 300*24;
            mrANZ[i].max = 300*24;
            
            RISTool.debug1[i] = new HaeufigkeitsZaehlerDouble();
            RISTool.debug1[i].label = "upTo="+grenzen[i];
        }
    }
    
    public static void main( String[] args ) throws Exception {
        
        initRqMIN();
        
        initRqCollectors();
        
        Vector<Double> data1 = new Vector<Double>();
        Vector<Double> data2 = new Vector<Double>();
        Vector<Double> data3 = new Vector<Double>();

        int _binning = 12;
        int _scale = 10;         
        
        int n1 = 2;
        int n2 = 3;
        
        data1.add( 1.0 );
        data1.add( 1.0 );            
        data1.add( 5.5 );
        data1.add( 5.5 );            
        data1.add( 5.0 );
        data1.add( 5.0 );            

        data2.add( 2.5 );
        data2.add( 2.5 );            
        data2.add( 5.5 );
        data2.add( 5.5 );            
        data2.add( 6.5 );
        data2.add( 6.5 );    
        data2.add( 6.5 );
        data2.add( 6.5 );   
        data2.add( 6.5 );
        data2.add( 6.5 );   
        
        data3.add( 10.0 );
        data3.add( 10.0 );  
        data3.add( 3.5 );
        data3.add( 3.5 );   
        data3.add( 3.5 );
        data3.add( 3.5 );   
        
        for ( int i = 0; i < 20; i++ ) { 
            data1.add( 3.0 );
            data2.add( 3.0 );
            data3.add( 3.0 );
        }

        
        RISTool ris1 = new RISTool( 
                "ris1", _binning, _scale);
               
        RISTool ris2 = new RISTool( 
                "ris2", _binning, _scale);
       
        RISTool ris3 = new RISTool( 
                "ris3", _binning, _scale);
       
        // the intervals are added now
        data1 = RISTool.initTestData( 100, 10, 3);
        ris1.addDistData(data1);
        ris1.calcMessreihen();
        
        data2 = RISTool.initTestData( 100, 10, 10);
        ris2.addDistData(data2);
        ris2.calcMessreihen();

        data3 = RISTool.initTestData( 100, 10, 100);
        ris3.addDistData(data3);
        ris3.calcMessreihen();

        RISTool ris4 = new RISTool( 
                "ris4", _binning, _scale);
        ris4.isContainerInstanz = true;
        
        ris4.add(ris1);
        ris4.add(ris2);
        ris4.add(ris3);
        
        ris4.calcMessreihen();
        
        RISTool ris5 = new RISTool( 
                "ris5", _binning, _scale);
        ris5.isContainerInstanz = true;
        
        ris5.add(ris3);
        ris5.add(ris2);
        ris5.add(ris1);
        ris5.calcMessreihen();
        
        
        
        Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();
        
        ris1.toString_Haeufigkeit();
        ris2.toString_Haeufigkeit();
        ris3.toString_Haeufigkeit();
        ris4.toString_Haeufigkeit();
        ris5.toString_Haeufigkeit();
        
        ris1.toString_Verteilung();
        ris2.toString_Verteilung();
        ris3.toString_Verteilung();
        ris4.toString_Verteilung();
        ris5.toString_Verteilung();
        
        
        
        
    
        
        ris1.showData();
        ris2.showData();
        ris3.showData();
        ris4.showData();
        ris5.showData();
    }

    static public Vector<Double> initTestData( int mi, int sj, int loops) { 
        Vector<Double> dv = new Vector<Double>();
        for( int l = 0; l < loops; l++ ) {
            for( int i = 1; i < mi; i++ ) {
                for( int j = 1; j < (mi- i * sj) ; j++ ) { 
                    dv.add( i * 1.0 );   
                }
            }
        }
        for( int l = 0; l < 500; l++ ) {
            dv.add( 1.0 );
        }
        return dv;
    };

    String logFileKey = null;
    public void setLofFileNameKey_1(String string) {
        logFileKey = string;
    }
    
        
    public static double rqMIN[] = null;
    public static void initRqMIN() { 
       
        
        rqMIN = new double[grenzen.length];
        
        for( int i = 1; i < grenzen.length; i++ ) {
             double Rq_max = (double)( 300 * 24 )/ (double) grenzen[i-1];
             
             rqMIN[i] = RISTool.offset / Rq_max;
             
             LOG.debug("interval range from={} to={} rqMax={} rqMin={}", grenzen[i-1], grenzen[i], Rq_max, rqMIN[i]);
        }
    }    

    private static void _checkRIS(RISTool ris) {
        for ( int i = 1; i < grenzen.length ; i++ ) {
            if ( ris.anzFiltered <= grenzen[i] && ris.anzFiltered > grenzen[i-1] ) { 

                // is the sum of all r larger than the whole series?
                double sum = 0.0;
                for( double v3 : ris.dataRawFILTERED ) {
                    sum = sum + v3;
                }
                
                
                for( double v : ris.dataRawFILTERED ) {
               
                                  
                    String line = "\t\tr=" + v + "\tRq=" + ris._Rq + "\tr/Rq=(" + (v/ris._Rq) + ")\t rq_min=" + rqMIN[i] + "\t sum(r_i)=" + sum;
 
                    if ( ( v / ris._Rq ) < rqMIN[i] ) {
                        LOG.debug("interval below the minimum result={} line={}", ris.name, line);
                        debug1[i].addData( v/ris._Rq );
                        line = "* " + line;
                    }
                    
                    logLineToFile( logFileNames[i-1], line + "\n" );

                }
                
                if ( sum > 300*24 ) {
                    LOG.debug("intervals longer than 300 days result={} sum={}", ris.name, sum);
                }
                double RQMAX = 300.0 * 24.0 / (1.0 * grenzen[i-1] );
                if( ris._Rq > RQMAX ) {
                    logLineToFile( "RqTooLarge", ris._Rq + " " + ris.label );
                }
            }
        }
    }

    private boolean preCheck(Vector<Double> dataRawTIMES) {
        
        boolean b = true;
        
        double sum = 0.0;

        for( double v : dataRawTIMES ) { 
            sum = sum + v;
        }
        if ( sum > (300 * 24) ) { 
            b = false;
            logLineToFile("LENGTH", sum + " : " + dataRawTIMES +"\n");
        }
        return b;
    }
  
}
