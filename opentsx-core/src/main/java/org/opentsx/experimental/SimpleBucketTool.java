/**
 * 
 * BucketTool loads a full TSBucket into memory. 
 * 
 * To process this data set it uses an instance of a class derived 
 * from "SingleRowTSO".
 *
 * It works like a local mapper, which processes all records idependently from
 * each other.
 *
 */
package org.opentsx.experimental;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.MRT;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.BucketLoader;
import org.opentsx.core.AbstractTSProcessor;
import org.opentsx.core.SingleRowTSO;
import org.opentsx.core.TSBucket;
import org.opentsx.tsbucket.TSBucketFileFilter;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class SimpleBucketTool extends AbstractTSProcessor {
    
    // default tool to use if no other is defined
    public static String toolname = "hadoopts.core.SingleRowTSO";

    /**
     * Here we handle one individual bucket file.
     * This is a sequence file with Vector objects. 
     * 
     * @param f
     * @throws IOException 
     */
    public void workOnBucketFile(File f) throws IOException {
        
        System.out.println(">>> Work on one bucket file ... " + f.getAbsolutePath() );
        System.out.println(">>> (canRead=" + f.canRead() + ")" );


        // we need an array to be more flexible, even if just one is used.
        File[] files = new File[1];
        
        files[0] = f;
        
        workOnBucketFolder( files );
    }
    

    public void workOnBucketFolder(File[] files) throws IOException {
    
        System.out.println(">>> Work on one bucket folder ... nr of files=" + files.length );
        System.out.println(">>> tool name : " + toolname );
        
        // Collect some data to print to output at the end of the run ...
        StringBuffer sb = new StringBuffer();

        // process all files in the folder ...
        int i = 0;
        for (File file : files) {
            i++;
            
            System.out.println(">>> file (" + i + ")         : " + file.getName() + "#canRead()=" + file.canRead());

            switch (procm) {
                
                case procm_BULK: {

                    // Result Writer
                    StringWriter resultWriter = new StringWriter();
                    StringWriter explodeWriter = new StringWriter();

                    System.out.println("[BULK-MODE] => true");
                    bl.loadBucket(file.getAbsolutePath());
                    System.out.println(">>>              : " + bl.getTSBucket());

                    Class c = null;
                    SingleRowTSO srtso = null;

                    try {
                        // we instantiate a series processor
                        c = Class.forName(toolname);
                        srtso = (SingleRowTSO) c.newInstance();
                        srtso.init();

                    } catch (ClassNotFoundException ex) {
                        Logger.getLogger(SimpleBucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (InstantiationException ex) {
                        Logger.getLogger(SimpleBucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (IllegalAccessException ex) {
                        Logger.getLogger(SimpleBucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    }



                    Vector<TimeSeriesObject> data = bl.getBucketData();

                    Vector<TimeSeriesObject> dataNorm = new Vector<TimeSeriesObject>();

                    int d = 7;
                    int t = 24 * d;

                    for (TimeSeriesObject m : data) {

//                        TimeSeriesObject mr = MRT.normalizeByPeriodeTrend(m, t);
//
//                        double[] reihe = MRT.calcPeriodeTrend(m, t);
//                        TimeSeriesObject mr3 = new TimeSeriesObject(reihe);

                        try {


                            TimeSeriesObject result = srtso.processReihe( (Writer)resultWriter , m , explodeWriter);
                            dataNorm.add( result );

                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }

                        dataNorm.add(m.normalizeToStdevIsOne());
                    }

                    sb.append(file.getAbsolutePath() + "   " + data.size() + "\n");

                    boolean legend = false;
                    
                    if( data.size() < 10 ) legend = true;
                    
                    MultiChart.open(data, file.getName(), "t", "raw", legend);
                    MultiChart.open(dataNorm, file.getName(), "t", srtso.getSymbol() + "(" + "raw" + ")", legend);

                    System.out.println( resultWriter.toString() );

                    break;
                }

                case procm_RECORD_STREAM: {

                    System.out.println("[STREAM-MODE] => true");

                    Class c = null;
                    SingleRowTSO srtso = null;
                    
                    try {
                        c = Class.forName(toolname);
                        srtso = (SingleRowTSO) c.newInstance();
                        
                    } catch (ClassNotFoundException ex) {
                        Logger.getLogger(SimpleBucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (InstantiationException ex) {
                        Logger.getLogger(SimpleBucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (IllegalAccessException ex) {
                        Logger.getLogger(SimpleBucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    }

                    bl._processBucket(file.getAbsolutePath(), srtso);

                    break;
                }

            }

        }
        
        System.out.println(sb.toString());
    }

    public SimpleBucketTool() {
        bl = new BucketLoader();
    }
    
    BucketLoader bl;
    public static final int procm_BULK = 0;
    public static final int procm_RECORD_STREAM = 1;
    // default ... work on a stream of time series like in Hadoop but
    //             just within a single JVM ...
    public static int procm = procm_RECORD_STREAM;

    public static int LIMIT = Integer.MAX_VALUE;
    
    public static void main(String[] args) throws IOException {

        System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");

        SimpleBucketTool tool = new SimpleBucketTool();

        /**
         * 
         * default settings 
         * 
         */
        String baseIn = "./tstest/";
        String filter = "sinus";
        File file = new File( baseIn + "/abucket.ts.seq_sinus_.tsb.vec.seq");
        
        tool.procm = procm_BULK;
        tool.bl.setLimit( LIMIT );

        /**
         * 
         * overwrite default settings with args array
         * 
         */
        if ( args != null ) { 
            
            if ( args.length > 0 ) 
                baseIn = args[0];
            
            System.out.println( ">>> base in : " + baseIn );
            file = new File( baseIn );
            
            if ( !file.exists() ) { 
                System.exit(0);
            }
        
            if ( args.length > 1 ) { 
                filter = args[1];
            }
            
            if ( args.length > 2 ) { 
                tool.procm = Integer.parseInt( args[2] );
            }
            
        };    

        System.out.println(">>> input file       : " + file.getAbsolutePath() + " # exists() => " + file.exists());
        System.out.println(">>> processing mode  : " + procm);

        
        TSBucketFileFilter fileFilter = new TSBucketFileFilter() ;
        fileFilter.setFilter(  filter );
        
        // let's stay local ...
        TSBucket.useHDFS = false;
        
        if ( file.isDirectory() ) {
            // PROCESS FULL FOLDER ...
            //
            // get a file-listing of the default input folder 
            // (a folder in the local FS)
            File[] files = file.listFiles( fileFilter );
            //
            tool.workOnBucketFolder(files);
        }
        else { 
            // PROCESS SINGLE FILE ...
            //
            tool.workOnBucketFile(file);
        }

        

    }

    /**
     * get the data as a Vector TimeSeriesObject ...
     */
    public static Vector<TimeSeriesObject> loadBucketData(String name) throws IOException {
        BucketLoader bl = new BucketLoader();
        bl.loadBucket(name);
        Vector<TimeSeriesObject> data = bl.getBucketData();
        return data;
    }

    /**
     *
     * Example procedure to process some time series ...
     *
     * @param tsb
     * @param days
     * @return
     */
    public static Vector<TimeSeriesObject> removePeriodicTrend(TSBucket tsb, int days) {
        Vector<TimeSeriesObject> dataNorm = new Vector<TimeSeriesObject>();

        int d = days;
        int t = 24 * d;

        int c = 0;
        for (TimeSeriesObject m : tsb.getBucketData()) {
            TimeSeriesObject mr = MRT.normalizeByPeriodeTrend(m, t);
            dataNorm.add(mr);
            c++;
        }
        System.out.println(c + " rows in TSB.");
        return dataNorm;
    }

    @Override
    public void initFilter() throws IOException {
        System.out.println(">>> no filter implemented <<<");
    }

    @Override
    public void setFilter(String fn) throws IOException {
        System.out.println(">>> no filter is used <<<");
    }
}
