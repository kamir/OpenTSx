/**
 * An HDFS client to create a SequenceFile with time series.
 *
 * KEY = LongWriteable VALUE = MessreiheData
 *
 * We do no load data, but we use a GENERATOR here ...
 *
 *
 *
 */
package org.apache.hadoopts.hadoopts.buckets.generator;

import org.apache.hadoopts.data.RNGWrapper;
import org.apache.hadoopts.hadoopts.core.TSBucket;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author sebastian
 */
public class TSBucketCreator_Uncorrelated {
    
    public static final int mode_GAUSSIAN = 0;
    public static final int mode_UNIFORM = 1;
    public static final int mode_POISSON = 2; 
    
    public static int MODE = 0;
    public static Properties PARAM = new Properties();
        
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        RNGWrapper.init();

        String baseOut = "./tstest/";
        
        if ( args!= null ) { 
            if ( args.length > 0 ) baseOut = baseOut + args[0];
            File f = new File( baseOut );
            if ( !f.exists() ) { 
                f.mkdirs();
            }
            
            if ( args.length > 1 ) MODE = Integer.parseInt(args[1]);

        }

        System.out.println(">>> UncorrelatedTSBucketCreator (" + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">   OUT : " + baseOut);

        String s = "abucket.ts.seq";

        // We do not load data, but we use a GENERATOR here ...

        int ANZ = 100;

        int EXP = 16;


        TSBucket tsb = new TSBucket();

        try {
            tsb.createBucketWithRandomTS(baseOut + s, EXP, ANZ);
        } 
        catch (Exception ex) {
            Logger.getLogger(TSBucketCreator_Uncorrelated.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
}
