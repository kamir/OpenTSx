package org.opentsx.tsbucket;

public class TSBucketStore {

    static TSOWriter tsow = new TSOWriter();
    static TSOReader tsor = new TSOReader();

    public static TSOWriter getWriter(){
        return tsow;
    };

    public static TSOReader getReader() {
        return tsor;
    }

}



