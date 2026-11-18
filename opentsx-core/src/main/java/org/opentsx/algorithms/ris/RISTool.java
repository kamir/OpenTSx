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
import java.io.FileWriter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * addData( Vector<Long> ...  ) => points in time
 * addDistData( Vector<Double)  => collect time intervals
 * 
 * 
 * 
 * @author Mirko Kämpf
 */

public class RISTool {
    
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
    
    static public boolean debug = false;
    static public boolean verbose = false;
    
    // smallest value of r that is used
//    public static final double offset = 24*1;
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
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
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
        
        if ( debug ) {
            System.out.println("added single RIS to collect. R_q="+ris._Rq );
            // ris.showData();
        }
        
        // an empty result has NaN probabilities (0 of 0 intervals) and adds nothing
        if ( ris.dataRawFILTERED.size() < 1 ) {
            if ( debug ) {
                System.out.println(">>> " + ris.label + " is empty ..." + ris._Rq );
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

        _checkRIS( ris );

        for( int i = 0; i < probSKALIERT.length; i++ ) { 
            probSKALIERT[i] = probSKALIERT[i] + ris.probSKALIERT[i];
            dataSKALIERT[i] = dataSKALIERT[i] + 1;
            
        }
        
        allRqs.add(ris._Rq);
        
        if ( debug ) {
            System.out.println( ris.dataRawFILTERED.size() + " values arrive ...");
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
    public void _addData( Vector<Double> times ) throws Exception {

        if ( this.isContainerInstanz ) {
            throw new IllegalStateException("_addData: " + name + " is a container; add times to a result that is not a container");
        }

        if ( debug ) {
            System.out.println( 
                    ">>> computing n=" + (times.size()-1) + " intervals." );
        }

        
        // recreate the container
        dataRawTIMES = new Vector<Double>();

        int nullDistCounter = 0;
        
        // points in time
        Enumeration<Double> en = times.elements();
        
        // first element (index 0)
        double vor = en.nextElement(); 
        
        while( en.hasMoreElements() ) {
            Double now = en.nextElement();
            double dist = (double)(now - vor);
            if ( dist > 0.0 ) {
                
                // intervals in hours
                dist = dist / (60 * 60 * 1000);
                dataRawTIMES.add(dist);
            }
            else {
                nullDistCounter++;
            }
            if (debug )System.out.println(dist + " \t now:=" + now + "\t before:=" + vor);
            vor = now;
        } 

        if ( preCheck( dataRawTIMES ) ) {
            addDistData(dataRawTIMES);
        }
        else { 
            errosLENGTH++;            
        }
        
        
        // System.out.println( "#>>> nullDistCounter=" + nullDistCounter + ";");
    }
    
    public static int errosLENGTH = 0;
    
    
    /**
     * kept for the whole run
     */
    public static HaeufigkeitsZaehler anzRowRAW = new HaeufigkeitsZaehler("length of rows (raw)");
    public static HaeufigkeitsZaehler anzRowsFILTERED = new HaeufigkeitsZaehler("length of rows (filtered, offset="+RISTool.offset+"h)");

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
        
        if ( debug ) {
            System.out.println( 
                    ">>> n=" + dists.size() + " intervals to insert ...");
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
                cutOff++;
            }
        }

        if ( cutOff != 0 && offset == 0 ) {
            throw new IllegalStateException( "addDistData: " + cutOff
                    + " intervals are 0 or negative; expected positive intervals while offset is 0" );
        }
        
        long anzahlNach = (long)dataRawFILTERED.size();
        anzRowsFILTERED.addData( anzahlNach );
        long delta = (anzahlVor-anzahlNach);
        
        if ( delta > 0) {
            String line = label + "\t" + anzahlVor + "\t" + anzahlNach + "\t" + delta + "\n";
            logLineToFile( logFileKey, line);
        }
        
        
        if ( debug ) {
            System.out.println( 
                    ">>> n_cutoff=" + cutOff + 
                    " intervals too small (offset:="+offset+")");
        }
        
        anzFiltered = dataRawFILTERED.size();
        
        clear();
        
        double sumRq_alt = 0.0;
        int anzRq_alt =0;
        
        // Rq of all intervals
        Enumeration enUM_Rq_alt = dists.elements();
        while( enUM_Rq_alt.hasMoreElements() ) {
            double v = (Double) enUM_Rq_alt.nextElement();
             // System.out.println( v);
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
             // System.out.println( v);
             sumRq_filteres = sumRq_filteres + v;
             anzRq_filterd++;
        }
        double Rq_filtered = sumRq_filteres/(1.0*anzRq_filterd);
        zElements = anzRq_filterd;
       
        if ( debug ) System.out.println(">>> R_q of all=" + Rq_alt + "\tR_q filtered=" + Rq_filtered );
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
        try {
            projekt.logLine(key, line);
        } 
        catch (IOException ex) {
            Logger.getLogger(RISTool.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public void calcMessreihen() throws Exception {
        if ( this.isContainerInstanz ) { 
            calcContainerMR();
        }
        else { 
            // calcSingleMR();
        }
    }
    
    private void calcContainerMR() throws Exception {
        
        mrVerteilungSkaliert = new TimeSeriesObject( "dist_scaled");
        
        for (int i = 0; i < dataSKALIERT.length; i++) {
            if ( dataSKALIERT[i] != 0 ) {
                double vx = ((double)i + 0.5) / scaleSKALIERT;
                double vy = probSKALIERT[i] / (double)dataSKALIERT[i];
                try {
                    if ( vy != 0.0 ) {
                        double logVy = Math.log(vy);
                        if ( Double.isInfinite( logVy ) || Double.isNaN( logVy ) ) {
                            System.err.println("ERROR 2:" + vx + "\t" + vy + "\t" );
                            
                        }
                        else {
                            mrVerteilungSkaliert.addValuePair( vx, logVy );
                        }
                    }    
                    
                }
                catch(Exception ex) {
                    System.err.println("ERROR 1:" + vx + "\t" + vy + "\t" );
                    
                }
            }
        }
    }
    
    
    void _calcSingleMR() throws Exception {

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
                
                // System.out.println( i+ "\t"+anzahlProSpalte + "\t" + 
                // Rq+ "\t" + relAnzahl );
                
            summe = summe + relAnzahl;
           
            
//        // leave out the first values
//        if ( i >= (offset*scale / Rq) ) { 
//                
//          anzahlProSpalte = dataSKALIERT[i];
//                                
//          relAnzahl = dataSKALIERT[i] / this.zElements;
// 
//          mrVerteilungSkaliert.addValuePair( i / ( scale ), relAnzahl);
//        }
        }
        // mrVerteilungSkaliert = mrVerteilungSkaliert.scaleY_2(1.0/summe);
        // if ( summe-1.0 > epsilon ) 
        //   throw new Exception(
        //     "Norm. Sum wrong! \n(epsilon="+epsilon+", sum="+summe+")");
        if ( debug ) {
            System.out.println(
               "(a) number of elements=" + this.zElements);
            System.out.println(
               "(b) Rq=" + _Rq + "\t sum=" + summe );    
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
        //return toString_Haeufigkeit();
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
        FileWriter fw = new FileWriter( folder + label );
        fw.write( mrVerteilung.toString() );
        fw.flush();
        fw.close();

        if ( verbose ) { 
            System.out.println(
               "\n>>> RIS-Data:\n" + mrVerteilung.toString() );
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
        
        debug = true;
        
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

//        for( int i = 1; i <= 25; i++  ) {            
//            
//            double z = new Double( i );
//            double z2 = new Double( 1 );
//            
//            data1.add( z );
//            data1.add( z2 );            
//        
//            double zz = new Double(i+5);
//            double zz2 = new Double(1);
//            
//            data2.add( zz );
//            data2.add( zz2 );            
//        
//        }    
        
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
        
//        System.out.print(ris1.toString_Haeufigkeit());
//        System.out.print(ris1.toString_Verteilung());
        
//        ris1.add(ris2);
//        ris1.calcMessreihen();
//        
//        ris3.add(ris2);
//        ris3.calcMessreihen();
        
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
//        
//        
//        mrv.add( ris1.mrHaeufigkeit );
//        mrv.add( ris2.mrHaeufigkeit);
//        mrv.add( ris3.mrHaeufigkeit);
//        
//        mrv.add( ris4.mrHaeufigkeit);
//        mrv.add( ris5.mrHaeufigkeit);
        
//        mrv.add(ris1.mrVerteilungSkaliert);
//        mrv.add(ris2.mrVerteilungSkaliert);
//        mrv.add(ris3.mrVerteilungSkaliert);
        
        
//        System.out.print(ris1.toString_Haeufigkeit());
//        System.out.print(ris2.toString_Haeufigkeit());
//        System.out.print(ris3.toString_Haeufigkeit());
//        System.out.print(ris4.toString_Haeufigkeit());
//        System.out.print(ris5.toString_Haeufigkeit());
        
//        System.out.print(ris1.toString_Verteilung());
    
//        MultiChart.open(mrv, true);
        
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
             
             System.out.println( grenzen[i-1] + " ... " + grenzen[i] + "\tRq_max=" + Rq_max + "\t" + rqMIN[i] );
        }
//        javax.swing.JOptionPane.showMessageDialog(null, "go");
    }    

    private static void _checkRIS(RISTool ris) throws IOException {
        for ( int i = 1; i < grenzen.length ; i++ ) {
            if ( ris.anzFiltered <= grenzen[i] && ris.anzFiltered > grenzen[i-1] ) { 

                // is the sum of all r larger than the whole series?
                double sum = 0.0;
                for( double v3 : ris.dataRawFILTERED ) {
                    sum = sum + v3;
                }
                
                
                for( double v : ris.dataRawFILTERED ) {
               
                                  
                    String line = "\t\tr=" + v + "\tRq=" + ris._Rq + "\tr/Rq=(" + (v/ris._Rq) + ")\t rq_min=" + rqMIN[i] + "\t sum(r_i)=" + sum;
                    // String line = "\t\tr=" + v + "\tRq=" + ris._Rq + "\tr/Rq=(" + (v/ris._Rq) + ")\t sum(r_i)=" + sum;
 
                    if ( ( v / ris._Rq ) < rqMIN[i] ) {
                        
                       
                        
                        // javax.swing.JOptionPane.showMessageDialog(null,"r=" + v + "\nRq=" + ris.Rq + "\n" + (v/ris.Rq) +"\n"+ Topic2.rqMIN[i]  );
                        System.out.println( line  );
                        debug1[i].addData( v/ris._Rq );
                        line = "* " + line;
                    }
                    
                    projekt.logLine( logFileNames[i-1] , line +"\n");

                }
                
                if ( sum > 300*24 ) {
                    System.out.println( "SUM >>> 300*24 " + sum );
                }
                double RQMAX = 300.0 * 24.0 / (1.0 * grenzen[i-1] );
                if( ris._Rq > RQMAX ) {
                    projekt.logLine(  "RqTooLarge" , ris._Rq + " " + ris.label );
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
            try {
                projekt.logLine("LENGTH", sum + " : " + dataRawTIMES +"\n");
            } catch (IOException ex) {
                Logger.getLogger(RISTool.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return b;
    }
  
}
