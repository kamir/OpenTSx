package org.apache.hadoopts.algorithms.univariate;

/**
 *  SingleTsFilterTool 
 *  arbeitet mit einer Zeitreihe und schreibt ggf. in zwei
 *  verschiedene FileWriter.
 * 
 *  
 */


import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.core.SingleRowTSO;

import java.io.Writer;

/**
 *
 * @author kamir
 */
public class SingleTsTrendCalculatorTool extends SingleRowTSO {
    
    static TimeSeriesObject[] sum = null;
    
    public void init() { 
        sum = new TimeSeriesObject[3];
        sum[0] = new TimeSeriesObject();
        sum[1] = new TimeSeriesObject();
        sum[2] = new TimeSeriesObject();
        for( int i=0; i < 300; i++ ) { 
            sum[0].addValuePair(i, 0);
            sum[1].addValuePair(i, 0);
            sum[2].addValuePair(i, 0);
        }
    }
   
    public static int getKlasse( int z ) { 
        if ( z < 1000 ) return 0;
        else if ( z < 10000 ) return 1;
        else return 2;
    }
    
    @Override
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer explodeWriter ) throws Exception {

        TimeSeriesObject binned = reihe.setBinningX_sum(24);
        
        int z = (int)binned.summeY();
        int k = getKlasse( z );

//        System.out.println( k );
        
        TimeSeriesObject summe = sum[ k ];
        sum[k] = sum[k].add( binned );
                
        return null;
        
    }
    
}
