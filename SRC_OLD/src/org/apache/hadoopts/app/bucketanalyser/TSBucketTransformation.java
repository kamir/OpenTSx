package org.apache.hadoopts.app.bucketanalyser;

import java.util.Properties;

/**
 *
 * @author kamir
 */
public class TSBucketTransformation {
    
    public String source = "A";
    
    public String target = "B";
    
    public String operation = "TRANSFORM";

    public String type = "SIMPLE per Record Operation";

    public  Properties parameters = new Properties();
    
    public TSBucketTransformation() {
        parameters.put("p1", "v1");
        parameters.put("p2", "v2");
    }
    
    public static TSBucketTransformation getTransformation( String s, String t, String operation)  {
        TSBucketTransformation T = new TSBucketTransformation();
        T.source = s;
        T.target = t;
        T.operation = operation;
        return T;
    }
    
}
