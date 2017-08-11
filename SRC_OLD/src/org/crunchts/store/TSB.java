/**
 * 
 * This is a helper class to handle default storage locations for
 * time series tools which are build by using CrunchTS.
 */
package org.crunchts.store;

import java.io.File;

/**
 *
 * @author kamir
 */
public class TSB {
    
    /**
     * This is a GLOBAL directory for all studies, managed on one 
     * workstation. 
     * 
     * @return 
     */
    public static File getFolderForTimeSeriesMetadata() {
        File f = new File( timeSeriesMetadata );
        if ( !f.exists() ) f.mkdir();
        return f;
    }
    
    public static File getFileForTimeSeriesMetadata(String fType, String label) {
        File f = new File( getFolderForTimeSeriesMetadata().getAbsolutePath() + 
                           "/" + fType + "_" + label );
        return f;
    }
    
    
    
//    private static String timeSeriesMetadata = "/Volumes/MyExternalDrive/TSB.Store/metadata";
//
//    private static String timeSeriesExportLocation = "/Volumes/MyExternalDrive/TSB.Store/data";
//    
//    public static String timeSeriesProfiles = "/Volumes/MyExternalDrive/TSB.Store/profiles";

    private static String timeSeriesMetadata = "/TSB.Store/metadata";

    private static String timeSeriesExportLocation = "/TSB.Store/data";
    
    public static String timeSeriesProfiles = "/TSB.Store/profiles";

    
    public static File getAspectFolder(String typ, String studie, String gL, String ext) {

        String fn = gL + ext;

        File f = new File( timeSeriesExportLocation + "/" + studie + "/" + typ + "/" + fn  );
        if ( !f.getParentFile().exists() ) f.getParentFile().mkdirs();
        return f;
    }

    public static File getAspectFolder(String typ, String studie) {
        File f = new File( timeSeriesExportLocation + "/" + studie + "/" + typ );
        if ( !f.exists() ) f.mkdirs();
        return f;
    }
    
    public static File getFileForAspect(String aspekt, String studie, String fileName) {
        File folder = getAspectFolder( aspekt, studie );
        if ( !folder.exists() ) folder.mkdirs();
        File f = new File( folder.getAbsolutePath() + "/" + fileName );
        return f;
    }



}
