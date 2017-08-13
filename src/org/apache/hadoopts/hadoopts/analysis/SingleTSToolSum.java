package org.apache.hadoopts.hadoopts.analysis;

import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.core.SingleRowTSO;

import java.io.FileWriter;

/**
 * Just to show an example of a TSTool
 * 
 * Calculate the sum of all y-values
 *  
 * @author kamir
 */
public class SingleTSToolSum extends SingleRowTSO {
    
    public String processReihe( TimeSeriesObject reihe ) throws Exception {

        double sum = reihe.summeY(); 
        
        String line = sum+"\n";
        
        return line;
        
    }

    @Override
    public TimeSeriesObject processReihe(FileWriter fw, TimeSeriesObject reihe, FileWriter exploder) throws Exception {
        return null;
    }

    @Override
    public TimeSeriesObject processReihe(FileWriter fw, TimeSeriesObject reihe, Object para, FileWriter exploder) throws Exception {
        return null;
    }
    
}
