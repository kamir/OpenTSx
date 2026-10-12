package data.io.wikipedia;

/*
 *  Create some Time-Series and store them in an HBase table.
 */

import data.io.adapter.HBaseTSAdapter3;
import data.ts.TSDataWrapper;
import java.io.IOException;


/**
 *
 * @author kamir
 */
public class SimpleTSTool {
    
    public static void main( String[] args ) throws IOException, Exception {
        
        /**
         * Where is the Zookeeper ?
         */
        // String defaultIP = "192.168.3.171";
        
//        if ( args != null && args.length > 0 ) 
//            defaultIP = args[0];
        
//        System.out.println( "> zookeeper for simple TS-Test is: " + defaultIP );
        
        boolean doCreate = false;

        boolean doLoad = true;
        
        int nrOfRows = 25;
        
        System.out.println( "> create table : " + doCreate );
        System.out.println( "> nr of rows   : " + nrOfRows );
        
        /**
         * Init the Singleton-Adapter instance ...
         */
        HBaseTSAdapter3.init();

        if ( doCreate ) {
            for( int i = 0; i < nrOfRows ; i++ ) {
                
//                TSDataWrapper mapper = new TSDataWrapper( 24*299 );
//                
//                HBaseTSAdapter3.putEditTS( "wikinodes.2", mapper , ""+i );
            }
            System.out.println( "+ Creation is done!" );
        }
        
        if ( doLoad ) {
            System.out.println( "+ Start loading ... ");
            // load all rows from HBase ...
            for( int i = 0; i < nrOfRows ; i++ ) {
                
                //Object o = HBaseTSAdapter3.getAccessTS( "wikinodes.2", ""+i );
                //TSDataWrapper mapper = (TSDataWrapper)o;
                // System.out.println( mapper.data.length );
            
            }
        }
        
    }
    
}
