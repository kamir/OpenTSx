/**
 * Create a TSBucket from a set of text files.
 * Each word's length is used as the value in a time series
 * at time index i where the word position defines i.
 *
 * See: http://arxiv.org/pdf/physics/0607095.pdf
 * 
 * "LANGUAGE TIME SERIES ANALYSIS"
 * 
 * https://scholar.google.de/scholar?ion=1&espv=2&bav=on.2,or.r_cp.&biw=1921&bih=752&dpr=1.33&um=1&ie=UTF-8&lr&cites=18000101701931494559
 * 
 */
package org.opentsx.tsbucket.generator;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.core.TSBucket;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
public class TSBucketCreator_TFIDFRecursive {
     
    public static String experiment = "exp3";
        
    public static String baseOut = "./tsbucket/";
    public static String baseIn = "./data/in/textcorpus/";

    
    public static Properties PARAM = new Properties();
        
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        RNGWrapper.init();

        File f = new File( baseIn + "/" + experiment );

        System.out.println(">>> Text based TSBucketCreator (" + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">   OUT : " + f.getAbsolutePath() );

        String s = "TfIdfTSBucket.ts.seq";

        TSBucket tsb = new TSBucket();

        File[] folder = f.listFiles();
        
        try {
            tsb.createBucketFromFilesViaTFIDFRecursive( folder , baseOut, experiment);
        } 
        catch (Exception ex) {
            Logger.getLogger(TSBucketCreator_TFIDFRecursive.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
}
