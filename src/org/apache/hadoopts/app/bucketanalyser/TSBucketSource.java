package org.apache.hadoopts.app.bucketanalyser;

import java.util.Properties;

/**
 *
 * @author kamir
 */
public class TSBucketSource {
    
    public String techSource = "FILE";

    public String cacheFolder = ".sdl-cache/RDD-stockdata";
    
    public String hint = "extract via SDL2";
    
    Properties parameters = new Properties();
    
    public TSBucketSource() {
        parameters.put("p1", "v1");
        parameters.put("p2", "v2");
    }
    
    public static TSBucketSource getSource( String s )  {
        
        TSBucketSource T = new TSBucketSource();
        
        T.techSource = s;

        return T;
    }
    
}
