package org.opentsx.algorithms.analysis;

import org.opentsx.data.series.MRT;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;

import java.io.Writer;
import java.util.Vector;

/**
 * Just to show another example of a TSTool
 *  
 * TODO: Create a better JSON representation ...
 * 
 * @author kamir
 */
public class SingleTSToolPeakDetector extends SingleRowTSO {
    
    public String processReihe(Writer resultWriter, TimeSeriesObject reihe) throws Exception {

        double sum = reihe.summeY(); 
        
        String line = "";
        
        // Detrending
        TimeSeriesObject normalized1 = MRT.normalizeByPeriodeTrend(reihe, 24*7);
        
        TimeSeriesObject peaks = normalized1.setBinningX_sum(24);
        peaks.add_to_Y( -24.0 );
           
        int[] tss = { 2, 4, 6, 8, 10 };

        line = line.concat( "{Peaks:\t"  );
        for( int ts : tss ) {
            Vector<Integer> p = MRT.getPeaksDaysOverTS( ts , peaks );
            int nr = p.size();
            line = line.concat( ts +":"+nr + "{" );
            for( int i : p ) {
                line = line.concat( i + "," );
            }
            line = line.concat("}") ;
        }
        line = line.concat( "}"  );
        
        return line;
        
    }

    @Override
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Writer exploder) throws Exception {
        return null;
    }

    @Override
    public TimeSeriesObject processReihe(Writer fw, TimeSeriesObject reihe, Object para, Writer exploder) throws Exception {
        return null;
    }
    
}
