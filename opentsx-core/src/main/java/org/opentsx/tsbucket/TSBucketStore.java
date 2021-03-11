package org.opentsx.tsbucket;

abstract public class TSBucketStore {

    static TSOWriterInterface tsow = null;


    static TSOReaderInterface tsor = null;

    public static TSOWriterInterface getWriter(){
        return tsow;
    };

    public static TSOReaderInterface getReader() {
        return tsor;
    }


}



