package app.utils;

import java.io.File;

/**
 * The ProjectContexts help to organize the process data which emerges during
 * an analysis procedure.
 * 
 * It is the connector to the knowledge management system, in our case an 
 * instance of semantic media wiki (SMW). This plays a comparable role like the
 * Hive-Metastore.
 * 
 * SMW integration helps to track all the parameters and hints during 
 * data analysis procedure.
 *
 * @author kamir
 */
public class ProjectContext {

//    public static String basePathIN = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    
//    public static String basePathOUT_dashboard = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT_rawTS = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT_corpus = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT_listfile = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT_cclog = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT_eslog = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    
//    public static String basePathOUT_networks = "/Volumes/MyExternalDrive/CALCULATIONS/"; 
//    public static String basePathOUT_LRI = "LRI/"; 
    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) { 
    }
    
    /**
     * requires the WIKI API ...
     */
    boolean SMWinitilized = false;
    public void initSMWInstance() { 
        // TODO: implement the step-log-connector
    };
    
    
    
    private static String projectName;

    public static void initProject( String name ) { 
        projectName = name;
    }
    
    public static String _getProjektPath_CALC_LRI() {
        
        String n = "./LRI_" + projectName;
        
        n = ".";
        
        boolean goOn = false;
        
        File f = new File(n);
        if ( f.exists() ) { 
            if ( f.canWrite() ) { 
                goOn = true;
            }
        }
        else { 
            f.mkdirs();
            goOn = true;
        }
        
        System.err.print( "ProjectContext:: Create file: " + f.getAbsolutePath() );
        
        
        if ( !goOn ) { 
            System.err.print( "Can not create file: " + n );
            System.exit( -2 );
        }
        
        return n; 
    }
 
}
