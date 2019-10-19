package org.opentsx.algorithms.univariate;

/**
 *  SingleTsFilterTool 
 *  arbeitet mit einer Zeitreihe und schreibt ggf. in zwei
 *  verschiedene FileWriter.
 * 
 *  
 */


import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.SingleRowTSO;

//import timeseries.TestData;
//import timeseries.TimePeriod;
//import timeseries.TimeSeries;
//import timeseries.models.arima.Arima;
//import timeseries.models.arima.ArimaOrder;

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
//
//        TimeSeries series = TestData.livestock;
//        ArimaOrder order = ArimaOrder.order(1, 1, 1, 1, 1, 1);
//
//        Arima model1 = Arima.model(series, order, TimePeriod.oneYear(), Arima.FittingStrategy.CSS);
//        Arima model2 = Arima.model(series, order, TimePeriod.oneYear(), Arima.FittingStrategy.CSSML);
//        Arima model3 = Arima.model(series, order, TimePeriod.oneYear(), Arima.FittingStrategy.ML);
//
//        System.out.println( model1.coefficients().toString() );
//        System.out.println( model2.coefficients().toString() );
//        System.out.println( model3.coefficients().toString() );

        // model.fittedSeries().plotAcf( 10 );

    }
    
}
