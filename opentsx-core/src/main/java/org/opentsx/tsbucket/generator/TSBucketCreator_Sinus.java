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
package org.opentsx.tsbucket.generator;

import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.core.TSBucket;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class TSBucketCreator_Sinus {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        long t0 = System.currentTimeMillis();

        RNGWrapper.init();

        String baseOut = "./tstest/";
        
        if ( args!= null ) { 
            if ( args.length > 0 ) baseOut = baseOut + args[0];
            File f = new File( baseOut );
            if ( !f.exists() ) { 
                f.mkdirs();
            }
        }

        System.out.println(">>> SinusTSBucketCreator (" + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">   OUT : " + baseOut);

        String s = "abucket.ts.seq";

        // We do no load data, but we use a GENERATOR here ...

        int ANZ = 26 * 2 * 5;

        TSBucket tsb = new TSBucket();

        double fMIN = 1.0; 
        double fMAX = 2000.0; 
        double aMIN = 1.0; 
        double aMAX = 10.0; 
        
        double SR = 4.0 * fMAX;         // pro Sekunde
        
        double time = 1;           // Sekunden

        Vector<TSData> tsbd = null;

        try {

            tsbd = tsb.createBucketWithRandomTS_sinus(baseOut + s, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, null );

        }
        catch (Exception ex) {
            Logger.getLogger(TSBucketCreator_Sinus.class.getName()).log(Level.SEVERE, null, ex);
        }

        long t1 = System.currentTimeMillis();
        System.out.println( "DURATION 1 : " + ((t1 - t0)/1000.0) );




        /***
         * TODO:  * Kafka specific stuff has to be reconstructed ....
         *
        TSOProducer prod = new TSOProducer();
        System.out.println( ">>> " + tsbd.size() );

        prod.pushTSDataAsEpisodesToKafka( tsbd );

        long t2 = System.currentTimeMillis();
        System.out.println( "DURATION 2 : " + ((t2 - t1)/1000.0) );
*/
    }
}
