package org.opentsx.algorithms.univariate;

/**
 *  SingleTsFilterTool 
 * 
 *  arbeitet mit einer Zeitreihe und schreibt ggf. in zwei
 *  verschiedene FileWriter.
 *  
 */


import org.opentsx.data.series.MRT;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;

import java.io.IOException;
import java.io.Writer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class SingleTsPeakFilterTool extends SingleRowTSO {
    
    @Override
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer explodeWriter ) throws Exception {

        String line = "\t";
   
        // Detrending
        TimeSeriesObject normalized1 = MRT.normalizeByPeriodeTrend(reihe, 24*7);
        
        TimeSeriesObject peaks = normalized1.setBinningX_sum(24);
        peaks.add_to_Y( -24.0 );
        
        int[] tss = { 2, 4, 6, 8, 10 };
        line = line.concat( "Peaks:\t"  );
        for( int ts : tss ) {
            Vector<Integer> p = MRT.getPeaksDaysOverTS( ts , peaks );
            int nr = p.size();
            line = line.concat( nr + "\t" );
            for( int i : p ) {
                explodeWriter.write( reihe.getLabel() + "\t" + ts + "\t" + i + "\n" );
            }
        }

        try {
            fw.write( reihe.getLabel() + " " + reihe.summeY() + line + "\n" );
            fw.flush();
        } 
        catch (IOException ex) {
            Logger.getLogger(SingleTsPeakFilterTool.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        return reihe;
        
    }
    
}
