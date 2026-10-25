package org.opentsx.algorithms.statistics;

import org.opentsx.data.series.TimeSeriesObject;

/**
 *
 * @author kamir
 */
public class DatasetNormalizationTool {

        public static TimeSeriesObject normalizeToMWZeroANDSTDevONE(TimeSeriesObject mrA) {
            
            if (mrA == null || mrA.xValues == null || mrA.yValues == null
                    || mrA.yValues.size() < 2 || mrA.xValues.size() != mrA.yValues.size()) {
                throw new IllegalArgumentException("Normalization requires at least two coordinate-value pairs");
            }
            for (int i = 0; i < mrA.yValues.size(); i++) {
                Object x = mrA.xValues.get(i);
                Object y = mrA.yValues.get(i);
                if (!(x instanceof Double coordinate) || !(y instanceof Double value)
                        || !Double.isFinite(coordinate) || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("Normalization requires finite coordinates and values");
                }
            }
            
            double stdev = mrA.getStddev();
            if (!Double.isFinite(stdev) || stdev <= 0.0 || !Double.isFinite(1.0 / stdev)) {
                throw new IllegalArgumentException("Normalization requires a finite positive standard deviation");
            }
            TimeSeriesObject mrB = mrA.subtractAverage();
            double stdevR = 1.0/stdev;
            
            mrB = mrB.scaleY_2( stdevR );
            
            mrB.setLabel( mrA.getLabel() );
            
            return mrB;
        }
        
}
