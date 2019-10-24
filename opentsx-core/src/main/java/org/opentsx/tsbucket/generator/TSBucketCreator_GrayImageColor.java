/**
 * Create a TSBucket from an Gray-Scale image files.
 * 
 * Each line becomes one time series. Y position of each pixel
 * gives us the time index. Y-value is the color.
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
public class TSBucketCreator_GrayImageColor {
    
    public static String experiment = "ex2";
        
    public static String baseOut = "./tsbucket/";
    public static String baseIn = "./data/in/grayimage/";

    public static Properties PARAM = new Properties();
        
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        RNGWrapper.init();

        File f = new File( baseIn + "/" + experiment );

        System.out.println(">>> Text based TSBucketCreator (" + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">   OUT : " + f.getAbsolutePath() );

        String s = "grayImageBasedTSBucket.ts.seq";

        TSBucket tsb = new TSBucket();

        File[] folder = f.listFiles();
        
        try {
            tsb.createBucketFromGrayImageFile( folder[0] , baseOut, experiment);
        } 
        catch (Exception ex) {
            Logger.getLogger(TSBucketCreator_GrayImageColor.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
}
