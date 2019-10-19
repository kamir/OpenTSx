package org.opentsx.tsbucket.metadata;

import java.io.File;

public class TSStoreDescriptor {

    public static String getPathFor_TSCharts() {

        String fn = System.getProperty("user.home" ) + "/stsx/plots";
        File f = new File( fn );
        f.mkdirs();

        System.out.println( "****");
        System.out.println( "* Use the STSX home folder : " + f.getAbsolutePath() + " (" + f.canWrite() + ")" );
        System.out.println( "****");

        return f.getAbsolutePath();

    }

}
