package org.opentsx.algorithms.analysis;

import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;

import java.io.Writer;

/**
 * Just to show an example of a TSTool
 * 
 * Calculate the sum of all y-values
 *  
 * @author kamir
 */
public class SingleTSToolSum extends SingleRowTSO {
    
    public String processReihe(Writer resultWriter, TimeSeriesObject reihe) throws Exception {

        double sum = reihe.summeY(); 
        
        String line = sum+"\n";
        
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
