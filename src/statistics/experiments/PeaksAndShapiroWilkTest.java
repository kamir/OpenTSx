package statistics.experiments;

import app.thesis.dataqualitytest.InfluenceOfSinglePeakTester;
import stdlib.StdRandom;

/**
 *
 * @author kamir
 */
public class PeaksAndShapiroWilkTest {

    public static void main(String[] args) throws Exception {
        
        StdRandom.initRandomGen((long) 1.0);
        
        InfluenceOfSinglePeakTester.NR_OF_ROWS = 100;
   
        InfluenceOfSinglePeakTester.calcShapiroValuesForPeaks(  );
        
    }
    
}
