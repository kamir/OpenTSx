package org.opentsx.algorithms.statistics.experiments;

import org.opentsx.algorithms.dataqualitytest.InfluenceOfSinglePeakTester;

import org.opentsx.data.generator.RNGWrapper;

/**
 *
 * @author kamir
 */
public class PeaksAndShapiroWilkTest {

    public static void main(String[] args) throws Exception {

        RNGWrapper.init();

        InfluenceOfSinglePeakTester.NR_OF_ROWS = 100;
   
        InfluenceOfSinglePeakTester.calcShapiroValuesForPeaks(  );
        
    }
    
}
