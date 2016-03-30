/**
 * Create a TSBucket from an Gray-Scale image files.
 * 
 * Each line becomes one time series. Y position of each pixel
 * gives us the time index. Y-value is the color.
 * 
 */
package org.apache.hadoopts.hadoopts.buckets.generator;

import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.hadoopts.topics.wikipedia.AccessFileFilter;
import org.apache.hadoopts.hadoopts.core.TSBucket;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

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

        stdlib.StdRandom.initRandomGen(1);

        
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
