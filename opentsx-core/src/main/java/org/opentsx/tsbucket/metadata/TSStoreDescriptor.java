package org.opentsx.tsbucket.metadata;

import java.io.File;

public class TSStoreDescriptor {

    public static String _getPathFor_TSCharts() {

        String fn = System.getProperty("user.home" ) + "/opentsx/plots/";
        File f = new File( fn );
        f.mkdirs();

        System.out.println( "******");
        System.out.println( "*** Use the OpenTSx home folder : " + f.getAbsolutePath() + " (" + f.canWrite() + ")" );
        System.out.println( "******");

        return f.getAbsolutePath() + "/";

    }

}
