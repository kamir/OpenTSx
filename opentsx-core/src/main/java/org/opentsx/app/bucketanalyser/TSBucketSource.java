package org.opentsx.app.bucketanalyser;

import java.util.Properties;

/**
 *
 * @author kamir
 */
public class TSBucketSource {
    
    public String techSource = "FILE";

    public String cacheFolder = ".sdl-cache/RDD-stockdata";
    
    public String hint = "extracted via SDL2";
    
    Properties parameters = new Properties();
    
    public TSBucketSource() {
    }
    
    public static TSBucketSource getSourceStockData( String s )  {
        
        TSBucketSource t = new TSBucketSource();
        
        t.cacheFolder = ".sdl-cache/RDD-stockdata";
    
        t.hint = "extracted via SDL2";
    
        t.techSource = s;

        return t;
    }
    
    public static TSBucketSource getSource( String s )  {
        
        TSBucketSource t = new TSBucketSource();
        
        t.techSource = s;

        return t;
    }
    
}
