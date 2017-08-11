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
public class TSBucketCreator_WordLengthRecursive {
     
    public static String experiment = "exp3";
        
    public static String baseOut = "./tsbucket/";
    public static String baseIn = "./data/in/textcorpus/";

    
    public static Properties PARAM = new Properties();
        
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        stdlib.StdRandom.initRandomGen(1);

        
        File f = new File( baseIn + "/" + experiment );

        System.out.println(">>> Text based TSBucketCreator (" + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">   OUT : " + f.getAbsolutePath() );

        String s = "wordBasedTSBucket.ts.seq";

        TSBucket tsb = new TSBucket();

        File[] folder = f.listFiles();
        
        try {
            tsb.createBucketFromFilesViaWordLengthRecursive( folder , baseOut, experiment);
        } 
        catch (Exception ex) {
            Logger.getLogger(TSBucketCreator_WordLengthRecursive.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
}
