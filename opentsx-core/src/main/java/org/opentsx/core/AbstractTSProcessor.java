/*
 *
 * 
 */
package org.opentsx.core;

import org.opentsx.tsbucket.BucketLoader;
import java.io.File;
import java.io.IOException;

/**
 *
 * @author kamir
 */
public abstract class AbstractTSProcessor {
    
    TSOperation operation = null;

    public TSOperation getTSOperation() {
        return operation;
    }

    public void setTSOperation(TSOperation operation) {
        this.operation = operation;
    }

    /**
     * load and get the TSBucket
     */
    public static TSBucket loadBucket(String name) throws IOException {
        BucketLoader bl = new BucketLoader();
        TSBucket tsb = bl.loadBucket(name);
        return tsb;
    }
    
    abstract public void workOnBucketFolder(File[] files) throws IOException;
        
    abstract public void setFilter( String fn ) throws IOException;
    abstract public void initFilter() throws IOException;
    
    
}
