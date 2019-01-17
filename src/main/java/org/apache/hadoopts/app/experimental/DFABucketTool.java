/**
 * 
 * BucketTool loads a full TSBucket into the workstations memory. 
 * 
 * To process this data set it uses an instance of class derived 
 * from "SingleRowTSO".
 *
 * It works like a local mapper, which processes all records idependently from
 * each other.
 *
 */
package org.apache.hadoopts.app.experimental;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.MRT;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.core.AbstractTSProcessor;
import org.apache.hadoopts.hadoopts.buckets.BucketLoader;
import org.apache.hadoopts.hadoopts.core.SingleRowTSO;
import org.apache.hadoopts.hadoopts.core.TSBucket;
import org.apache.hadoopts.hadoopts.filter.TSBucketFileFilter;
import java.io.File;
import java.io.IOException;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class DFABucketTool extends AbstractTSProcessor {
    
    public void workOnBucketFile(File f) throws IOException {
        System.out.println(">>> one file ..." );

        File[] files = new File[1];
        files[0] = f;
        workOnBucketFolder( files );
    }

    public void workOnBucketFolder(File[] files) throws IOException {

        String toolname = "hadoopts.core.SingleRowTSO";

        // Collect some data to print to output at the end of the run ...
        StringBuffer sb = new StringBuffer();

        // process all files in the folder ...
        int i = 0;
        for (File file : files) {
            i++;
            System.out.println(">>> file (" + i + ")         : " + file.getName() + "#canRead()=" + file.canRead());

            switch (procm) {
                case procm_BULK: {
                    
                    System.out.println(">>>                  : BULK-MODE=true");
                    
                    bl.loadBucket(file.getAbsolutePath());
                    
                    System.out.println(">>>              : " + bl.getTSBucket());

                    Vector<TimeSeriesObject> data = bl.getBucketData();

                    Vector<TimeSeriesObject> dataNorm = new Vector<TimeSeriesObject>();

                    int d = 7;
                    int t = 24 * d;

                    for (TimeSeriesObject m : data) {

//                        TimeSeriesObject mr = MRT.normalizeByPeriodeTrend(m, t);
//
//                        double[] reihe = MRT.calcPeriodeTrend(m, t);
//                        TimeSeriesObject mr3 = new TimeSeriesObject(reihe);

                        dataNorm.add(m);
                    }

                    sb.append( file.getAbsolutePath() + "   " + data.size() + "\n");

                    MultiChart.open(dataNorm, file.getName(), "", "", true);

                    break;
                }

                case procm_RECORD_STREAM: {

                    System.out.println(">>>              : STREAM-MODE=true");

                    Class c = null;
                    SingleRowTSO srtso = null;
                    try {
                        c = Class.forName(toolname);
                        srtso = (SingleRowTSO) c.newInstance();
                    } catch (ClassNotFoundException ex) {
                        Logger.getLogger(DFABucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (InstantiationException ex) {
                        Logger.getLogger(DFABucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (IllegalAccessException ex) {
                        Logger.getLogger(DFABucketTool.class.getName()).log(Level.SEVERE, null, ex);
                    }

                    bl._processBucket( getResultFolderPath( file ), srtso);

                    break;
                }

            }

        }
        
        System.out.println(sb.toString());
    }
    
    public static String getResultFolderPath( File f ) {
        return f.getAbsolutePath();
    }

    public DFABucketTool() {
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

        DFABucketTool tool = new DFABucketTool();

        String baseIn = "./tstest/";
        String filter = "sinus";
        
        // default settings 
        File file = new File( baseIn + "/abucket.ts.seq_sinus_.tsb.vec.seq");
//        File file = new File("tstest/buckets/");
        
        if ( args!= null ) { 
            
            if ( args.length > 0 ) 
                baseIn = baseIn + args[0];
            
            System.out.println( ">>> base in : " + baseIn );
            file = new File( baseIn );
            
            if ( !file.exists() ) { 
                System.exit(0);
            }
        
            if ( args.length > 1 ) { 
                filter = args[1];
            }
        };    

        System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");

        TSBucketFileFilter fileFilter = new TSBucketFileFilter() ;
        fileFilter.setFilter(  filter );
        
        tool.procm = procm_BULK;
        tool.bl.setLimit( LIMIT );

        System.out.println(">>> input file       : " + file.getAbsolutePath() + " # exists() => " + file.exists());
        System.out.println(">>> processing mode  : " + procm);

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
     * get the data as a Vector TimeSeriesObject  ...
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
