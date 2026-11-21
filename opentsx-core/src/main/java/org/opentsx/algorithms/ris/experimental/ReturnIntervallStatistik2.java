package org.opentsx.algorithms.ris.experimental;

/**
 * Computes the distribution of return intervals.
 *
 * - The values are scaled with Rq when calcMessreihen() is called and
 *   when the instance is added to a container instance
 * - A container instance combines the results of several interval
 *   series
 * - A container instance keeps the Rq of every result it adds
 * - Shows the data of single series
 */

import org.opentsx.generators.LongTermCorrelationSeriesGenerator;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.series.MRT;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehler;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehlerDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;



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
 * _addData( Vector<Double>, Rq ) => points in time, adds the intervals between them
 * addDistData( Vector<Double)  => collect time intervals
 *
 * @author Mirko Kämpf
 */

public class ReturnIntervallStatistik2 {

    private static final Logger LOG = LoggerFactory.getLogger(ReturnIntervallStatistik2.class);
    
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
    /**
     * For a result that is not a container: the share of the intervals in scaled bin i at
     * x = (i + 0.5) * scale / binning, the center of the bin in units of R_q. When offset and R_q are above 0, the
     * bins that start below offset / R_q are left out.
     */
    public TimeSeriesObject mrVerteilungSkaliert = new TimeSeriesObject();

    public static int binning = 400;   // number of values per interval
    public static int scale = 8;    // largest value
    

    private double _Rq = 0.0;
    private int zElements = 0;
    
    /* 
     * the binned values, an array of counters
     */
    int data[] = null;
    int dataSKALIERT[] = null;
    double probSKALIERT[] = null;
    
    Vector<Double> dataRawFILTERED = null;
    Vector<Double> dataRaw_InterEventTIMES = null;
    
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

    public ReturnIntervallStatistik2(String l, int bin, int s) {
        binning = bin;
        scale = s;
        name = l;
        initArray();
        label = this.getClass().getName();
        dataRawFILTERED = new Vector<Double>();
        dataRaw_InterEventTIMES = new Vector<Double>();
        
        ZZZ = bin * s;
    }

    public ReturnIntervallStatistik2(int binning, int scale) {
        this( "unnamed" , binning, scale );
    }
    
    public ReturnIntervallStatistik2(String name) {
        this( name , ReturnIntervallStatistik2.binning, ReturnIntervallStatistik2.scale );
    }
 
    private void clear() {
        initArray();
    }

    private void initArray() {
        
        data = new int[ ( binning ) + 1 ];
        
        dataSKALIERT = new int[ ( binning ) + 1 ];
        probSKALIERT = new double[ (binning ) + 1 ];
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
    
    public void add( ReturnIntervallStatistik2 ris ) throws Exception {
        
        if ( !this.isContainerInstanz ) {
            throw new IllegalStateException( "add: " + name + " is not a container; set isContainerInstanz to true before adding results");
        }
        
        if ( ris.data.length != data.length ) {
            throw new IllegalArgumentException( "add: " + ris.name + " has " + ris.data.length + " bins and " + name
                    + " has " + data.length + "; expected the same binning and scale");
        }
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("adding result container={} result={} rq={}", name, ris.name, ris._Rq);
        }
        
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

        for( int i = 0; i < probSKALIERT.length; i++ ) {
            probSKALIERT[i] = probSKALIERT[i] + ris.probSKALIERT[i];
            dataSKALIERT[i] = dataSKALIERT[i] + 1;
        }
        for( int i = 0; i < data.length; i++ ) {
            data[i] = data[i] + ris.data[i];
        }

        allRqs.add(ris._Rq);
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("added result container={} result={} intervals={}", name, ris.name, ris.dataRawFILTERED.size());
        }
        
    }

    /**
     * Adds the intervals between consecutive times with addDistData. They replace the intervals of an earlier
     * call. Rq is not used.
     *
     * @throws IllegalArgumentException when a time is not finite, is before the time before it or is more than
     *         Double.MAX_VALUE after it
     **/
    public void _addData( Vector<Double> times, double Rq ) throws Exception {


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
            Double dist = now - vor;
            if ( Double.isInfinite( dist ) ) {
                throw new IllegalArgumentException( "_addData: time " + k + " of " + name + " is " + now
                        + ", more than Double.MAX_VALUE after time " + (k - 1) + " at " + vor
                        + "; expected times whose difference is finite" );
            }
            intervals.add(dist);
            vor = now;
        } 

        addDistData(intervals);
        dataRaw_InterEventTIMES = intervals;
        
    }
    
    public static int errosLENGTH = 0;
    
    
    /**
     * kept for the whole run
     */
    public static HaeufigkeitsZaehler anzRowRAW = new HaeufigkeitsZaehler("length of rows (raw)");
    public static HaeufigkeitsZaehler anzRowsFILTERED = new HaeufigkeitsZaehler("length of rows (filtered, offset="+ReturnIntervallStatistik2.offset+"h)");

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
     * @throws IllegalArgumentException when an interval is NaN or infinite
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
        anzRowRAW.addData( anzahlVor  );
        
        dataRawFILTERED = new Vector<Double>();
        
        Enumeration en1 = dists.elements();
        while( en1.hasMoreElements() ) {
            
            double da = (Double)en1.nextElement();
            
            if ( da >= offset && da != 0.0  ) {
                dataRawFILTERED.add( da );
            }
            else {
                LOG.debug("filtered interval result={} interval={} offset={}", name, da, offset);
                cutOff++;
            }
        }
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("filtered intervals result={} cutOff={} offset={}", name, cutOff, offset);
        }
   
        
        anzFiltered = dataRawFILTERED.size();
        
        clear();
        
        // Rq of all intervals
        double Rq_alt = meanOf( dists );
        
        // Rq of the filtered intervals
        double Rq_filtered = meanOf( dataRawFILTERED );
        zElements = dataRawFILTERED.size();
       
        if ( LOG.isDebugEnabled() ) LOG.debug("mean interval result={} rqAll={} rqFiltered={}", name, Rq_alt, Rq_filtered);
        _Rq = Rq_filtered;

        
        
        
        // count the filtered intervals as they are and scaled with their Rq
        Enumeration en = dataRawFILTERED.elements();
        if ( LOG.isDebugEnabled() ) LOG.debug("counting intervals result={} intervals={}", name, dataRawFILTERED.size());
        
        while( en.hasMoreElements() ) {
             
             double dist1 = (Double) en.nextElement();
             
             double v = dist1 / Rq_filtered;
             
             v = v * ( (double)binning / (double)scale );
             
             int index = (int)v;
             
             if ( index >= dataSKALIERT.length ) index = dataSKALIERT.length-1;
             else if ( index < 0 ) index = 0;
             dataSKALIERT[index]++;

             int bin = (int)( dist1 * (double)scale );
             if ( bin >= data.length ) bin = data.length-1;
             else if ( bin < 0 ) bin = 0;
             data[bin]++;
        }
        
        // probability of each bin
        for( int i = 0; i < probSKALIERT.length; i++ ) { 
            probSKALIERT[i] = (double)( dataSKALIERT[i] / (double)zElements );
        }              
    }
    
    /**
     * Returns the mean of the finite {@code Double} values, also when their sum overflows; NaN for no values and for a NaN or infinite value.
     */
    public static double meanOf( Vector<?> values ) {
        double sum = 0.0;
        double largest = 0.0;
        for ( Object value : values ) {
            double v = (Double) value;
            sum = sum + v;
            largest = Math.max( largest, Math.abs( v ) );
        }
        if ( Double.isInfinite( sum ) ) {
            // each value over the largest magnitude is at most 1, so this sum of finite values cannot overflow
            double scaled = 0.0;
            for ( Object value : values ) {
                scaled = scaled + (Double) value / largest;
            }
            return largest * ( scaled / values.size() );
        }
        return sum / values.size();
    }

    double epsilon = 1e-6; 
    
    public static void logLineToFile( String key, String line ) { 
        LOG.debug("log line key={} line={}", key, line.strip());
    }
    
    public void calcMessreihen() throws Exception {
        if ( this.isContainerInstanz ) { 
            calcContainerMR();
            LOG.debug("computed distributions container={}", name);
        
        }
        else { 
            calcSingleMR();
        }
    }
    
    private void calcContainerMR() throws Exception {
        
        mrVerteilungSkaliert = new TimeSeriesObject( name + " (res=" + ((double)scale/(double)binning) + ")");
        mrHaeufigkeit = new TimeSeriesObject( name );

        for (int i = 0; i < data.length; i++) {
            mrHaeufigkeit.addValuePair( (double)i / (double)scale, data[i] );
        }

        for (int i = 0; i < dataSKALIERT.length; i++) {
            if ( dataSKALIERT[i] != 0 ) {
                double vx = ((double)i + 0.5) * (double)scale / (double)binning;
                double vy = probSKALIERT[i] / (double)dataSKALIERT[i];
                if ( vy != 0.0 ) {
                    if ( Double.isInfinite( vy ) || Double.isNaN( vy ) ) {
                        LOG.debug("skipped bin without a finite value container={} x={} y={}", name, vx, vy);
                    }
                    else {
                        mrVerteilungSkaliert.addValuePair( vx, vy );
                    }
                }
            }
        }
    }
    
    
    void calcSingleMR() throws Exception {

        for ( TimeSeriesObject series : new TimeSeriesObject[] { mrHaeufigkeit, mrVerteilung, mrVerteilungSkaliert } ) {
            series.xValues.clear();
            series.yValues.clear();
        }

        double summe = 0.0;
        
        // for every bin i
        for (int i = 0; i < data.length; i++) {

            double relAnzahl = 0.0;
            double anzahlProSpalte = 0.0;
            
               
            anzahlProSpalte = data[i];
                
            mrHaeufigkeit.addValuePair(
                        (double)i / (double)scale,  anzahlProSpalte  );

            if ( this.zElements == 0 ) {
                continue;
            }

            relAnzahl = (double) data[i] / (double) this.zElements;
                
            mrVerteilung.addValuePair(
                        (double)i / (double)scale , relAnzahl );
                
            summe = summe + relAnzahl;
           
            
        // leave out the first values
        boolean belowOffset = offset > 0 && _Rq > 0 && i < offset * binning / ( (double)scale * _Rq );
        if ( !belowOffset ) {
                
          relAnzahl = (double) dataSKALIERT[i] / (double) this.zElements;
 
          mrVerteilungSkaliert.addValuePair( ((double)i + 0.5) * (double)scale / (double)binning, relAnzahl);
        }
        }
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("distribution result={} intervals={} rq={} sum={}", name, this.zElements, _Rq, summe);
        }
    }
    
    public void showData() throws Exception {
        
        v1 = new Vector<TimeSeriesObject>();
        v2 = new Vector<TimeSeriesObject>();

        calcMessreihen();
        
        showRawData();      
        
        showVerteilungen();
        
        MultiChart.open(v1,true, name + " distribution");
        
        MultiChart.open(v2,true, 
                this.name + " (RawData)");
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
        TimeSeriesObject mr = new TimeSeriesObject( this.name + " (rawdata) " + dataRawFILTERED.size() );
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

    private static boolean storeSingleDistributions = false;
    
    static FileWriter fw;
    
    static Vector<TimeSeriesObject> mrv2 = null;
    static Vector<TimeSeriesObject> mrv = null;

    static Vector<TimeSeriesObject> mrv2B = null;
    static Vector<TimeSeriesObject> mrvB = null;

    static int maxB = 5; // number of input files
    static int maxROWS = -1; // default -1
    
    public static String logFileNames[] = null;
    public static OriginProject projekt = null;
    

    static HaeufigkeitsZaehlerDouble hzANZ = new HaeufigkeitsZaehlerDouble();
    static HaeufigkeitsZaehlerDouble[] mrRQ = null;
    static HaeufigkeitsZaehlerDouble[] mrANZ = null;
    
    public static void initRqCollectors() {
        
        
        ReturnIntervallStatistik2.debug1 = new HaeufigkeitsZaehlerDouble[grenzen.length];
                
        mrRQ = new HaeufigkeitsZaehlerDouble[grenzen.length];
        mrANZ = new HaeufigkeitsZaehlerDouble[grenzen.length];
        
        for( int i = 0; i < grenzen.length; i++ ) { 
            mrRQ[i] = new HaeufigkeitsZaehlerDouble(); // "R_q " + labels[i] );
            mrRQ[i].intervalle = binning;
            mrRQ[i].max = binning;

            mrANZ[i] = new HaeufigkeitsZaehlerDouble();
            mrANZ[i].intervalle = binning;
            mrANZ[i].max = binning;
            
            ReturnIntervallStatistik2.debug1[i] = new HaeufigkeitsZaehlerDouble();
            ReturnIntervallStatistik2.debug1[i].label = "upTo="+grenzen[i];
        }
    }
    
    
    
    public static void main( String[] args ) throws Exception {
        
        RNGWrapper.init();

        binning = 200;
        scale = 4;   
        
        initRqCollectors();
        
        mrv = new Vector<TimeSeriesObject>();
        mrv2 = new Vector<TimeSeriesObject>();
        
        mrvB = new Vector<TimeSeriesObject>();
        mrv2B = new Vector<TimeSeriesObject>();
        
        // V1
        TimeSeriesObject mr1 = TimeSeriesObject.getGaussianDistribution( (int)Math.pow(2, 20), 5.0, 0.5);
        addRow( mr1 );

        mr1 = TimeSeriesObject.getGaussianDistribution( (int)Math.pow(2, 22), 5.0, 4.5);
        shiftToZero( mr1, LOG );
        addRow( mr1 ); 
        
        
        // V1
        TimeSeriesObject mrx = TimeSeriesObject.getUniformDistribution( (int)Math.pow(2, 20), 0.0, 5.0);
        addRow( mrx );
        
        // V3
        TimeSeriesObjectFFT mr3 = (TimeSeriesObjectFFT) LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, 20), 1.4, false, false);
        shiftToZero( mr3, LOG );
        addRow( mr3 );
        
        // V3
        mr3 = (TimeSeriesObjectFFT) LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, 20), 1.2, false, false);
        shiftToZero( mr3, LOG );
        
        addRow( mr3 );
        
        
        MultiChart.open(mrv, true);
        MultiChart.open(mrv2, true);

        MultiChart.open(mrvB, true);
        MultiChart.open(mrv2B, true);
    }

    static void shiftToZero( TimeSeriesObject series, Logger log ) {
        double min = series.getMinY();
        series.add_value_to_Y( -1.0 * min );
        if ( log.isDebugEnabled() ) {
            log.debug("shifted series series={} min={} shiftedMin={}", series.getLabel(), min, series.getMinY());
        }
    }
    
    /**
     * Bins the return intervals of m: the distances in days, one value per day, between consecutive values of at
     * least 0.5. Adds the scaled distribution and the counts of a container that holds the result to mrvB and mrv2B.
     */
    public static void addRowB( TimeSeriesObject m ) throws Exception {
        
        ReturnIntervallStatistik2 ris1 = new ReturnIntervallStatistik2( 
               m.getLabel() , binning, scale);

          
        Vector<Double> p = MRT.getPeaksDaysOverTSdouble( 0.5 , m );
 
        
        
        ris1._addData(p, 0.0);

        ris1.calcMessreihen(); 

        ReturnIntervallStatistik2 ris4 = new ReturnIntervallStatistik2( 
                m.getLabel(), binning, scale);
        ris4.isContainerInstanz = true;
        ris4.add(ris1);
        ris4.calcMessreihen();
        

        mrvB.add( ris4.mrVerteilungSkaliert ); 
        mrv2B.add( ris4.mrHaeufigkeit ); 
        
    }
        
    public static void addRow( TimeSeriesObject m ) throws Exception {
        
        addRowB(m);
        
        Vector<Double> data1 = m.yValues;
        LOG.debug("adding row series={} values={}", m.label, m.yValues.size());
        
        ReturnIntervallStatistik2 ris1 = new ReturnIntervallStatistik2( 
               m.getLabel() , binning, scale);
                       

        ris1.addDistData(data1);
        LOG.debug("mean interval series={} rq={}", m.label, ris1.getRq());
        ris1.calcMessreihen();

        ReturnIntervallStatistik2 ris4 = new ReturnIntervallStatistik2( 
                m.getLabel(), binning, scale);
        ris4.isContainerInstanz = true;
        ris4.add(ris1);
        ris4.calcMessreihen();
        

        mrv.add( ris4.mrVerteilungSkaliert ); 
        mrv2.add( ris4.mrHaeufigkeit ); 
        
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

    /**
     * @deprecated Has no effect; this class writes no log files.
     */
    @Deprecated
    public void setLofFileNameKey_1(String string) {
    }
    
    public static int ZZZ = 0;
        





  
}
