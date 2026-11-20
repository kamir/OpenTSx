package org.opentsx.algorithms.ris.experimental;

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
 * addData( Vector<Long> ...  ) => points in time
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
        
        allRqs.add(ris._Rq);
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("added result container={} result={} intervals={}", name, ris.name, ris.dataRawFILTERED.size());
        }
        
    }

    /** 
     * Vector<Long> times ==> points in time, in order.
     *                        No y values, only the sequence of t.
     *  
     * A vector of times is passed.
     * 
     * The return intervals are computed from it first.
     **/
    public void _addData( Vector<Double> times, double Rq ) throws Exception {


        if ( LOG.isDebugEnabled() ) {
            LOG.debug("computing intervals result={} intervals={}", name, times.size() - 1);
        }

        
        int nullDistCounter = 0;
        
        // points in time
        Enumeration<Double> en = times.elements();
        
        // first element (index 0)
        double vor = en.nextElement(); 
        
        while( en.hasMoreElements() ) {
            Double now = en.nextElement();
            Double dist = now - vor;
            vor = now;
        } 

        addDistData(dataRaw_InterEventTIMES);
        
    }
    
    public static int errosLENGTH = 0;
    
    
    /**
     * kept for the whole run
     */
    public static HaeufigkeitsZaehler anzRowRAW = new HaeufigkeitsZaehler("length of rows (raw)");
    public static HaeufigkeitsZaehler anzRowsFILTERED = new HaeufigkeitsZaehler("length of rows (filtered, offset="+ReturnIntervallStatistik2.offset+"h)");

    /** 
     * Adds a vector of return intervals.
     * 
     * The intervals are sorted into the bins and scaled with Rq
     * at once.
     * 
     * The existing array is cleared first.
     * 
     **/
    int cutOff = 0;
    public int anzFiltered = 0;
    public void addDistData( Vector dists ) throws Exception {
        if ( this.isContainerInstanz ) {
            throw new IllegalStateException("addDistData: " + name + " is a container; add intervals to a result that is not a container");
        }
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("adding intervals result={} intervals={}", name, dists.size());
        }
        
        long anzahlVor = (long)dists.size();
        anzRowRAW.addData( anzahlVor  );
        
        dataRawFILTERED = new Vector<Double>();
        Vector<Double> dataRawNOT_FILTERED = new Vector<Double>();
        
        Enumeration en1 = dists.elements();
        while( en1.hasMoreElements() ) {
            
            double da = (Double)en1.nextElement();
            dataRawNOT_FILTERED.add(da);
            
            
            if ( da >= offset && da != 0.0  ) {
                dataRawFILTERED.add( da );
            }
            else {
                LOG.debug("filtered interval result={} interval={} offset={}", name, da, offset);
                cutOff++;
            }
        }
        
        long anzahlNach = (long)dataRawFILTERED.size(); 
        
        
        long delta = (anzahlVor-anzahlNach);
        
        if ( delta > 0) {
            String line = "### FILTER : " + label + "\t" + anzahlVor + "\t" + anzahlNach + "\t" + delta + "\n";
            logLineToFile( logFileKey, line);
        }
        
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("filtered intervals result={} cutOff={} offset={}", name, cutOff, offset);
        }
   
        
        anzFiltered = dataRawFILTERED.size();
        
        clear();
        
        double sumRq_alt = 0.0;
        int anzRq_alt =0;
        
        // Rq of all intervals
        Enumeration enUM_Rq_alt = dists.elements();
        while( enUM_Rq_alt.hasMoreElements() ) {
            double v = (Double) enUM_Rq_alt.nextElement();
             sumRq_alt = sumRq_alt + v;
             anzRq_alt++;
        }
        double Rq_alt = sumRq_alt /(1.0*anzRq_alt);
        
        // Rq of the filtered intervals
        double sumRq_filteres = 0.0;
        int anzRq_filterd = 0;
        Enumeration enUM_Rq_filteres = dataRawFILTERED.elements();
        while( enUM_Rq_filteres.hasMoreElements() ) {
            double v = (Double) enUM_Rq_filteres.nextElement();
             sumRq_filteres = sumRq_filteres + v;
             anzRq_filterd++;
        }
        double Rq_filtered = sumRq_filteres/(1.0*anzRq_filterd);
        zElements = anzRq_filterd;
       
        if ( LOG.isDebugEnabled() ) LOG.debug("mean interval result={} rqAll={} rqFiltered={}", name, Rq_alt, Rq_filtered);
        _Rq = Rq_filtered;

        
        
        
        // count the intervals scaled with Rq
        Enumeration en = dataRawNOT_FILTERED.elements();
        if ( LOG.isDebugEnabled() ) LOG.debug("counting intervals result={} intervals={}", name, dataRawFILTERED.size());
        
        while( en.hasMoreElements() ) {
             
             double dist1 = (Double) en.nextElement();
             
             double v = dist1 / Rq_alt;
             
             v = v * ( (double)binning / (double)scale );
             
             int index = (int)v;
             
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
        
        mrVerteilungSkaliert = new TimeSeriesObject( name + " (res=" + ((double)scale/(double)binning) );
        
        
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

        double summe = 0.0;
        
        // for every bin i
        for (int i = 0; i < data.length; i++) {

            double relAnzahl = 0.0;
            double anzahlProSpalte = 0.0;
            
               
            anzahlProSpalte = data[i];
            relAnzahl = (double) data[i] / (double) this.zElements;
                
            mrHaeufigkeit.addValuePair(
                        (double)i / (double)scale,  anzahlProSpalte  );
                
            mrVerteilung.addValuePair(
                        (double)i / (double)scale , relAnzahl );
                
            mrVerteilungSkaliert.addValuePair(
                        (double)i  / ( (double)scale * _Rq), relAnzahl * _Rq);
                
                
            summe = summe + relAnzahl;
           
            
        // leave out the first values
        if ( i >= (offset*scale / _Rq) ) { 
                
          anzahlProSpalte = dataSKALIERT[i];
                                
          relAnzahl = dataSKALIERT[i] / this.zElements;
 
          mrVerteilungSkaliert.addValuePair( i / ( scale ), relAnzahl);
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
        
        double min;
        // V1
        TimeSeriesObject mr1 = TimeSeriesObject.getGaussianDistribution( (int)Math.pow(2, 20), 5.0, 0.5);
        addRow( mr1 );

        mr1 = TimeSeriesObject.getGaussianDistribution( (int)Math.pow(2, 22), 5.0, 4.5);
        min = mr1.getMinY();
        mr1.add_value_to_Y( -1.0 * min);
        addRow( mr1 ); 
        
        
        // V1
        TimeSeriesObject mrx = TimeSeriesObject.getUniformDistribution( (int)Math.pow(2, 20), 0.0, 5.0);
        addRow( mrx );
        
        // V3
        TimeSeriesObjectFFT mr3 = (TimeSeriesObjectFFT) LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, 20), 1.4, false, false);
        min = mr3.getMinY();
        System.out.println( min );
        mr3.add_value_to_Y( -1.0 * min);
        min = mr3.getMinY();
        System.out.println( min );
        addRow( mr3 );
        
        // V3
        mr3 = (TimeSeriesObjectFFT) LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, 20), 1.2, false, false);
        min = mr3.getMinY();
        System.out.println( min );
        mr3.add_value_to_Y( -1.0 * min);
        min = mr3.getMinY();
        System.out.println( min );
        
        addRow( mr3 );
        
        
        MultiChart.open(mrv, true);
        MultiChart.open(mrv2, true);

        MultiChart.open(mrvB, true);
        MultiChart.open(mrv2B, true);
    }
    
    /**
     * 
     * @param m
     * @throws Exception 
     */     
    public static void addRowB( TimeSeriesObject m ) throws Exception {
        
        Vector<Double> data1 = m.yValues;
        
        ReturnIntervallStatistik2 ris1 = new ReturnIntervallStatistik2( 
               m.getLabel() , binning, scale);

          
        Vector<Double> p = MRT.getPeaksDaysOverTSdouble( 0.5 , m );
 
        
        
        ris1.addDistData(p);

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
                       
        
        double rq = ris1.calcAverageOfDist(data1);
        LOG.debug("mean interval series={} rq={}", m.label, rq);
        
        ris1.addDistData(data1);
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

    String logFileKey = null;
    public void setLofFileNameKey_1(String string) {
        logFileKey = string;
    }
    
    public static int ZZZ = 0;
        





  
}
