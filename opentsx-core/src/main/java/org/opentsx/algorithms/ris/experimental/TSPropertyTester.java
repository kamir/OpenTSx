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

import org.apache.commons.math3.stat.regression.SimpleRegression;

import org.opentsx.generators.LongTermCorrelationSeriesGenerator;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehler;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehlerDouble;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.Vector;

import static org.opentsx.generators.LongTermCorrelationSeriesGenerator.nrOfSValues;


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

public class TSPropertyTester {

    private static final Logger LOG = LoggerFactory.getLogger(TSPropertyTester.class);
    
    public static int zSAMPLES = 1;

    static Vector<TimeSeriesObject> reihen = new Vector<TimeSeriesObject>();
    static Vector<TimeSeriesObject> reihen2 = new Vector<TimeSeriesObject>();
    
    public static void addSample(TimeSeriesObject mr) {
        reihen.add(mr);
    }

    public static void addSampleAddNoCorrelations(TimeSeriesObject mr) {
        reihen2.add(mr);
    }

    private static void runDFA(Vector<TimeSeriesObject> mrs, int i) throws Exception {
    
         Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();
         Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
        
        int order = 0;
        for( TimeSeriesObject d4 : mrs ) {
        
            DecimalFormat df = new DecimalFormat("0.000");
            int N = d4.yValues.size();
            double[] zr = new double[N];
            

            TimeSeriesObject rawN = d4.copy();
            rawN.normalize();
            vr.add( rawN );

            zr = d4.getData()[1];

            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
            order = dfa.getPara().getGradeOfPolynom();

            dfa.getPara().setzSValues( nrOfSValues );

            // number of values in the series
            dfa.setNrOfValues(N);

            // choose the window sizes
            dfa.initIntervalSlog();
            dfa.showS();


            // http://stackoverflow.com/questions/12049407/build-sample-data-for-apache-commons-fast-fourier-transform-algorithm

            dfa.setZR( zr );

            dfa.calc();

            TimeSeriesObject mr4 = dfa.getResultsMRLogLog();
            mr4.setLabel( d4.getLabel() );
            v.add(mr4);

            String status = dfa.getStatus();

            SimpleRegression alphaSR = mr4.linFit(1.2, 3.5);

            double alpha = alphaSR.getSlope();
                        

        
        }
        
        TimeSeriesObject ref1 = getRef( 0.5 , 2.5);
        TimeSeriesObject ref2 = getRef( 0.9 , -5 );
        
        v.add( ref1 );
        v.add( ref2 );
        
        MultiChart.open(v, "F(s) [order:" + order + "] ", "log(s)", "log( F(s) )", true );
        MultiChart.open(vr, "RAW", "t", "y(t)", true );
        
        MeasurementTable tab1 = new MeasurementTable();
        tab1.singleX = false;
        tab1.setHeader("# F(s) [order:" + order + "] , log(s), log(F(s))");
        tab1.setMessReihen(v);
        tab1.writeToFile( new File("./TSPropertyTester_hadoopts_image_b.dat") );
        
        MeasurementTable tab2 = new MeasurementTable();
        tab2.singleX = false;
        tab2.setHeader("# RAW data");
        tab2.setMessReihen(vr);
        tab2.writeToFile( new File("./TSPropertyTester_hadoopts_image_d.dat") );
            
        
    }

    private static TimeSeriesObject getRef(double d, double n) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("m=" + d +")");
        int i = 0;
        while( i < 10 ) {
            mr.addValuePair(i , i*d + n );
            i++;
        }
        return mr;
        
    }

    private static TimeSeriesObject getRefExp(double d, double n) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("exp(tau * x) (tau=" + d +")");
        int i = 0;
        while( i < 10 ) {
            mr.addValuePair(i , d*(double)i + n );
            i++;
        }
        return mr;    
    }

    private static TimeSeriesObject getRefStretchedExp(double a, double b, double gamme) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("stretched_exp(x) (alpha=" + a +",beta="+b+",gamma="+gamme+")");
        int i = 0;
        while( i < 10 ) {
            double B = Math.pow( b*(double)i, gamme);
            double y = a * Math.exp(-1.0 * B);
            mr.addValuePair(i , y );
            i++;
        }
        return mr;    
        
    }
    
    public TSPropertyTester() { }
    
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

    public int binning = 400;   // number of values per interval
    public int scale = 8;    // largest value
    
    public static int binningD = 400;   // number of values per interval
    public static int scaleD = 8;    // largest value
    

    private double _Rq = 1.0;
    private int zElements = 0;
    private double Rq = 1.0;
    
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

    
    public TSPropertyTester(String l, int bin, int s) {
        binning = bin;
        scale = s;
        name = l;
        initArray();
        label = this.getClass().getName();
        dataRawFILTERED = new Vector<Double>();
        dataRaw_InterEventTIMES = new Vector<Double>();
        
        ZZZ = bin * s;
        
        initRqCollectors();
    }

    public TSPropertyTester(int binning, int scale) {
        this( "unnamed" , binning, scale );
    }
    
    public TSPropertyTester(String name) {
        this( name , TSPropertyTester.binningD, TSPropertyTester.scaleD );
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
    
    private static Vector<Double> calcDist(Vector<Double> rr1, double ts) {
        double sum = 0.0;
        double c = 0.0;

        Vector<Double> dat = new Vector<Double>();
        
        double t = 1;
        
        double last = 0;
        double delta = 0;
        
        
        for( double l : rr1 ) { 

            if ( l > ts ) {
                t++;
                dat.add(t);
                t=1;
                c++;
            }
            else {
                t++;
                c++;
            }
            

            
        }
        return dat;
    }
    
    static private double calcAverageOfDist(Vector<Double> rr1, double ts) {
        double sum = 0.0;
        double c = 0.0;

        int t = 1;
        
        double last = 0;
        double delta = 0;
        
        for( double l : rr1 ) { 
        
            if ( l > ts ) {
                t++;
                sum = sum + t;
                t=1;
                c++;
            }
            else {
                t++;
            }
            
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
    
    public void add( TSPropertyTester ris ) throws Exception {
        
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
     * Adds the intervals between consecutive times with addDistData. They replace the intervals of an earlier
     * call. Rq is not used.
     *
     * @throws IllegalArgumentException when a time is not finite or is before the time before it
     **/
    public void _addData( Vector<Double> times, double Rq ) throws Exception {


        if ( LOG.isDebugEnabled() ) {
            LOG.debug("computing intervals result={} intervals={}", name, times.size() - 1);
        }

        Vector<Double> intervals = new Vector<Double>();
        
        // points in time
        Enumeration<Double> en = times.elements();
        
        // first element (index 0)
        double vor = en.nextElement(); 
        if ( !Double.isFinite( vor ) ) {
            throw new IllegalArgumentException( "_addData: time 0 of " + name + " is " + vor + "; expected a finite time" );
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
            intervals.add(dist);
            vor = now;
        } 

        dataRaw_InterEventTIMES = intervals;
        addDistData(dataRaw_InterEventTIMES);
        
    }
    
    public static int errosLENGTH = 0;
    
    
    /**
     * kept for the whole run
     */
    public static HaeufigkeitsZaehler anzRowRAW = new HaeufigkeitsZaehler("length of rows (raw)");
    public static HaeufigkeitsZaehler anzRowsFILTERED = new HaeufigkeitsZaehler("length of rows (filtered, offset="+TSPropertyTester.offset+"h)");

    Vector<Double> dataRawNOT_FILTERED = new Vector<Double>();
    
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
        
        if ( LOG.isDebugEnabled() ) LOG.debug("adding intervals result={} intervals={}", name, dists.size());
        
        
        long anzahlVor = (long)dists.size();
        anzRowRAW.addData( anzahlVor  );
        
        dataRawFILTERED = new Vector<Double>();
        dataRawNOT_FILTERED = new Vector<Double>();
        
        Enumeration en1 = dists.elements();
        while( en1.hasMoreElements() ) {
            
            double da = (Double)en1.nextElement();
            dataRawNOT_FILTERED.add(da);
            
            
                dataRawFILTERED.add( da );
        }
        
        long anzahlNach = (long)dataRawFILTERED.size(); 
        
        
        long delta = (anzahlVor-anzahlNach);
        
        if ( delta > 0) {
            String line = "### FILTER : " + label + "\t" + anzahlVor + "\t" + anzahlNach + "\t" + delta + "\n";
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
             
             double v = dist1 / getRq() ;
             
             v = v * ( (double)binning / (double)scale );
             
             int index = (int)v;
             
             if ( index >= dataSKALIERT.length ) index = dataSKALIERT.length-1;
             else if ( index < 0 ) index = 0;
             else dataSKALIERT[index]++;
        }
        
        // probability of each bin
        for( int i = 0; i < probSKALIERT.length; i++ ) { 
            probSKALIERT[i] =  (double)( dataSKALIERT[i] / (double)zElements );
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
    
    boolean useLog = false;
    
    private void calcContainerMR() throws Exception {
        
        mrVerteilungSkaliert = new TimeSeriesObject( name + " (res=" + ((double)scale/(double)binning) + ")");
        
        
        for (int i = 0; i < dataSKALIERT.length; i++) {
            if ( dataSKALIERT[i] != 0 ) {
                double vx = ((double)i + 0.5) * (double)scale / (double)binning;
                double vy = probSKALIERT[i] / (double)dataSKALIERT[i];
                if ( vy != 0.0 ) {
                    double logVy = 0.0;

                    if ( useLog ) logVy = Math.log(vy);
                    else logVy = vy;

                    if ( Double.isInfinite( logVy ) || Double.isNaN( logVy ) ) {
                        LOG.debug("skipped bin without a finite value container={} x={} y={}", name, vx, vy);
                    }
                    else {
                        mrVerteilungSkaliert.addValuePair( vx, logVy );
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
        Rq = vRQ;
    }
    
    
    public double getRq() {
        return Rq;
    }

    private static boolean storeSingleDistributions = false;
    
    static FileWriter fw;
    
    static Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();
    static Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();

    static Vector<TimeSeriesObject> mrv2B = null;
    static Vector<TimeSeriesObject> mrvB = new Vector<TimeSeriesObject>();

    static int maxB = 5; // number of input files
    static int maxROWS = -1; // default -1
    
    public static String logFileNames[] = null;
    public static OriginProject projekt = null;
    

    static HaeufigkeitsZaehlerDouble hzANZ = new HaeufigkeitsZaehlerDouble();
    static HaeufigkeitsZaehlerDouble[] mrRQ = null;
    static HaeufigkeitsZaehlerDouble[] mrANZ = null;
    
    public void initRqCollectors() {
        
        
        TSPropertyTester.debug1 = new HaeufigkeitsZaehlerDouble[grenzen.length];
                
        mrRQ = new HaeufigkeitsZaehlerDouble[grenzen.length];
        mrANZ = new HaeufigkeitsZaehlerDouble[grenzen.length];
        
        for( int i = 0; i < grenzen.length; i++ ) { 
            mrRQ[i] = new HaeufigkeitsZaehlerDouble(); // "R_q " + labels[i] );
            mrRQ[i].intervalle = binning;
            mrRQ[i].max = binning;

            mrANZ[i] = new HaeufigkeitsZaehlerDouble();
            mrANZ[i].intervalle = binning;
            mrANZ[i].max = binning;
            
            TSPropertyTester.debug1[i] = new HaeufigkeitsZaehlerDouble();
            TSPropertyTester.debug1[i].label = "upTo="+grenzen[i];
        }
    }
    
    
    
    public static void main( String[] args ) throws Exception {
        
        RNGWrapper.init();

        mrv = new Vector<TimeSeriesObject>();
        mrv2 = new Vector<TimeSeriesObject>();
        
        mrvB = new Vector<TimeSeriesObject>();
        mrv2B = new Vector<TimeSeriesObject>();
        
        double min;
        
        TimeSeriesObject mr1 = null;
        

        
        // V1
        mr1 = TimeSeriesObject.getGaussianDistribution( (int)Math.pow(2, N), 1.0, 1.0);
        TSPropertyTester.addSample(mr1);

 
        mr1 = TimeSeriesObject.getGaussianDistribution( (int)Math.pow(2, N), 1.0, 0.5);
        min = mr1.getMinY();
        mr1.add_value_to_Y( -1.0 * min);
        TSPropertyTester.addSample(mr1);
                
        // V1
        mr1 = TimeSeriesObject.getUniformDistribution( (int)Math.pow(2, N), 0.0, 2.0);
        TSPropertyTester.addSample(mr1);
 
        // V1
        mr1 = TimeSeriesObject.getExpDistribution((int)Math.pow(2, N), 5.0);
        TSPropertyTester.addSample(mr1);

        // V1
        mr1 = TimeSeriesObject.getParetoDistribution((int)Math.pow(2, N), 1.0);
        TSPropertyTester.addSample(mr1);

        // V3
        TimeSeriesObjectFFT mr3 = (TimeSeriesObjectFFT) LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, N), 0.8, false, false);
        mr3.normalize();
        min = mr3.getMinY();
        System.out.println( min );
        mr3.add_value_to_Y( -1.0 * min);
        min = mr3.getMinY();
        System.out.println( min );
        TSPropertyTester.addSample(mr3);
      
        // V3
        mr3 = (TimeSeriesObjectFFT) LongTermCorrelationSeriesGenerator.getRandomRow((int) Math.pow(2, N), 0.9, false, false);
        mr3.normalize();
        min = mr3.getMinY();
        System.out.println( min );
        mr3.add_value_to_Y( -1.0 * min);
        min = mr3.getMinY();
        System.out.println( min );
        TSPropertyTester.addSample(mr3);
        
        Color[] colors = new Color[8];
        colors[0] = Color.BLUE;
        colors[1] = Color.cyan ;
        colors[2] = Color.ORANGE;
        colors[3] = Color.GREEN;
        colors[4] = Color.red;
        colors[5] = Color.darkGray;
        colors[6] = Color.lightGray;
        
        MultiChart.setSmallFont();
        MultiChart._initColors(colors);
      
  
        TSPropertyTester.showTestResult();
    }
    
    static int N = 16;
    static double ts = 0.5;
    /**
     * 
     * @param m
     * @throws Exception 
     */     
    public static void addRowB( TimeSeriesObject m ) throws Exception {
 
        int binA = 15;
        int scaleA = 6;
        
 
        TSPropertyTester ris1 = new TSPropertyTester( 
               m.getLabel() , binA, scaleA);
          
        
        Vector<Double> p = calcDist( m.yValues, ts );
        LOG.debug("intervals above the threshold series={} intervals={}", m.label, p.size());
        
        
        if ( p.size() > 10 ) {
            
            ris1.addDistData(p);
            ris1.useLog = true;
        
        
            ris1.calcMessreihen(); 

            TSPropertyTester ris4 = new TSPropertyTester( m.getLabel(), binA, scaleA);
            ris4.isContainerInstanz = true;
            ris4.add(ris1);
            ris4.useLog = true;
            ris4.calcMessreihen();

            mrvB.add( ris4.mrVerteilungSkaliert );
        }
    }
        
    public static void addRow( TimeSeriesObject m ) throws Exception {
        
        int binA = 120;
        int scaleA = 4;
                
        addRowB(m.copy());
        
        Vector<Double> data1 = m.yValues;
        LOG.debug("adding row series={} values={}", m.label, m.yValues.size());
        
        TSPropertyTester ris1 = new TSPropertyTester( 
               m.getLabel() , binA, scaleA);
                       
        double ts = 0.0;
        double rq = ris1.calcAverageOfDist(data1,ts);
        LOG.debug("mean interval series={} rq={}", m.label, rq);
        
        m.calcAverage();
        ris1.useLog = false;
        
        ris1.setRQ( m.getAvarage() );
        ris1.addDistData(data1);
        ris1.calcMessreihen();

        TSPropertyTester ris4 = new TSPropertyTester( 
                m.getLabel(), binA, scaleA);
        ris4.isContainerInstanz = true;
        ris4.add(ris1);
        ris4.setRQ( 1.0 );
        ris4.calcMessreihen();

        mrv.add( ris4.mrVerteilungSkaliert ); 
        mrv2.add( ris4.mrHaeufigkeit ); 
    }
    
    public static int ZZZ = 0;
        
    

    

    static public void showTestResult() throws Exception {
        
        Vector<TimeSeriesObject> mrs = new Vector<TimeSeriesObject>();
        
        for( TimeSeriesObject mr : reihen ) {
            addRow( mr );
            mrs.add(mr);  // DFA
        }
        
        
        


         
        runDFA(mrs, 2);
      
        TimeSeriesObject ref2 = getRefExp( -1.0, 0.0 );
        mrvB.add( ref2);
        
        TimeSeriesObject ref3 = getRefStretchedExp( 126.0, 15120.0 , 0.2);
        ref3.calcLog10_for_Y();
        mrvB.add( ref3);
        
        MultiChart.open(mrv, "distribution (raw data)", "y/<y>", "P(X=y)", true);

        MultiChart.open(mrvB, "RIS", "r/RQ", "RQ * P(r)",  true);
 
        MeasurementTable tab1 = new MeasurementTable();
        tab1.singleX = false;
        tab1.setHeader("# distribution (raw data), y/<y>, P(X=k)");
        tab1.setMessReihen(mrv);
        tab1.writeToFile( new File("./TSPropertyTester_hadoopts_image_a.dat") );
        
        
        MeasurementTable tab2 = new MeasurementTable();
        tab2.singleX = false;
        tab2.setHeader("#RIS, r/RQ, RQ * P(r)");
        tab2.setMessReihen(mrvB);
        tab2.writeToFile( new File("./TSPropertyTester_hadoopts_image_c.dat") );
        
        
    }


  
}


