package org.apache.hadoopts.statistics.experiments;

import org.apache.hadoopts.app.thesis.dataqualitytest.InfluenceOfSinglePeakTester;
import org.apache.hadoopts.data.RNGWrapper;

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
