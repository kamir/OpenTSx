/**
 *  An HDFS client to create a SequenceFile with time series.
 *
 *  KEY   = LongWriteable
 *  VALUE = MessreiheData
 *
 *  We do no load data, but we use a GENERATOR here ...
 * 
 *  A loop defines several BETA values and creates one 
 *  TS Bucket File for each beta.
 *
 **/

package org.apache.hadoopts.hadoopts.buckets.generator;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoopts.data.RNGWrapper;
import org.apache.hadoopts.hadoopts.core.TSBucket;

import java.io.File;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Mirko
 */
public class TSBucketCreator_FFMLRC extends Configured implements Tool {
 
	/**
     * @param args the command line arguments
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {    	
    	
    	int res = ToolRunner.run( new Configuration(), new TSBucketCreator_FFMLRC(), args);

        System.exit(res);

    }

	@Override
	public int run(String[] args) throws Exception {

        int stat = 0;
        
    	System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");
                
        String baseOut = "tstest/";
        String s = "abucket.ts.seq";

		if ( args!= null ) { 
            if ( args.length > 0 ) baseOut = baseOut + args[0];
            File f = new File( baseOut );
            if ( !f.exists() ) { 
                f.mkdirs();
            }
        }
        
	    // Conf object will read the HDFS configuration parameters from these
        // XML files.
        Configuration conf = getConf();
        conf.addResource(new Path("/etc/hadoop/conf/core-site.xml"));
		
        System.out.println( ">>> FFMLRCBucketCreator : " + new Date( System.currentTimeMillis() ) );

        RNGWrapper.init();

        System.out.println( ">   fs.default.name     : " + getConf().get( "fs.default.name" ) );
        System.out.println( ">   baseOut             : " + baseOut );
             
        // We do no load data, but we use a GENERATOR here ...
        int Z = 15;
        double STRETCH = 4;

        int LOOPS = 10;
        
        int EXP = 16;

        for( int i = 0; i < Z; i++ ) {
            
            double beta = 0.1 + ( i * STRETCH * 0.05 ); 
            
            System.out.println(">>> i=" +  i + "\t beta=" + beta );
        
            int z = LOOPS;

            TSBucket tsb = new TSBucket( getConf() );
            
            try {
                tsb.createBucketWithRandomTS( baseOut + s, z, EXP, beta );
            } 
            catch (Exception ex) {
                Logger.getLogger(TSBucketCreator_FFMLRC.class.getName()).log(Level.SEVERE, null, ex);
            }
            
        }        
        
        return stat;

	}

}


