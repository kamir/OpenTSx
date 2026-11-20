package org.opentsx.algorithms.univariate;

/**
 *  SingleTsFilterTool 
 *  works on one series and can write to two
 *  different FileWriters.
 * 
 *  
 */


import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;


import java.io.Writer;

/**
 *
 * @author kamir
 */
public class SingleTsARIMATool extends SingleRowTSO {

    public void init() { 

    }


    @Override
    public String processReihe(Writer fw, TimeSeriesObject reihe ) throws Exception {


        return null;
    }


    public static void main( String[] ARGS ) {

        // https://github.com/jrachiele/java-timeseries/blob/master/timeseries/src/test/java/timeseries/models/arima/ArimaSpec.java


    }
    
}
