package org.opentsx.algorithms.univariate;

/**
 *  SingleTsPeakFilterTool
 *
 *  works on one time series and writes to two writers.
 */


import org.opentsx.data.series.MRT;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;

import java.io.Writer;
import java.util.Vector;

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

        fw.write( reihe.getLabel() + " " + reihe.summeY() + line + "\n" );
        fw.flush();
        
        return reihe;
        
    }
    
}
