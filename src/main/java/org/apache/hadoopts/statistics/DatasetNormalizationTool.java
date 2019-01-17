package org.apache.hadoopts.statistics;

import org.apache.hadoopts.data.series.TimeSeriesObject;

/**
 *
 * @author kamir
 */
public class DatasetNormalizationTool {

        public static TimeSeriesObject normalizeToMWZeroANDSTDevONE(TimeSeriesObject mrA) {
            
            TimeSeriesObject mrB = mrA.subtractAverage();
            
            double stdev = mrA.getStddev();
            double stdevR = 1.0/stdev;
            
            mrB = mrB.scaleY_2( stdevR );
            
            mrB.setLabel( mrA.getLabel() );
            
            return mrB;
        }
        
}
