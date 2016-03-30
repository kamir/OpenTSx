package org.apache.hadoopts.app.bucketanalyser;

import org.apache.hadoopts.hadoopts.core.TSBucket;
import java.io.IOException;

/**
 *
 * @author kamir
 */
public class MacroRunner {

    public static void main(String[] args) throws IOException {

        Object sc = null;
  
        TSBucket tsbCollection = TSBucket.loadViaContext( sc, "/TSBASE/EXP3/Collection.tsb.vec.seq" );
        TSBucket tsbComponents = tsbCollection.processBucket( "CACHE", null );
        TSBucket tsbShuffled = tsbComponents.processBucket( "SHUFFLEYVALUES(1)", null );
        TSBucket tsbDFA_2Shuffled = tsbShuffled.processBucket( "DFA_OF_", null );
        TSBucket tsbAC_Components = tsbComponents.processBucket( "AC_OF_", null );
        TSBucket tsbFFT_Components = tsbComponents.processBucket( "FFT_OF_", null );
        TSBucket tsbDFA_2Components = tsbComponents.processBucket( "DFA_OF_", null );
        TSBucket tsbPDF_Components = tsbComponents.processBucket( "PDF_OF_", null );
        TSBucket tsbLogRETURN_Components = tsbComponents.processBucket( "LOGRETURN_OF_", null );
        TSBucket tsbStandardized_Components = tsbComponents.processBucket( "STANDARDIZED_OF_", null );
        TSBucket tsbH_Components = tsbComponents.processBucket( "H_OF_", null );


        // now we can apply this analysis procedure to other TSBuckets automatically.
        // procedures runs locally on Workstation or on a Hadoop cluster.

        
        

       

    }

}
