/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.thesis.experimental;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.Vector;

/**
 *
 * @author kamir
 */
public class StdTester {
    
    
    public static void main( String[] args ) {

        RNGWrapper.init();

        TimeSeriesObject r1 = TimeSeriesObject.getGaussianDistribution( 500, 10, 2);
        TimeSeriesObject r2 = TimeSeriesObject.getGaussianDistribution( 500, -10, 2);
        
        TimeSeriesObject r3 = r1.exchangeRankWise( r2 );
        
        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
        vmr.add( r1);
        vmr.add( r2);
        vmr.add( r3 );
        
//        TimeSeriesObject c3 = r3.getRanks()[0];
//        c3.add_to_Y(-10.0);
        
//        vmr.add( r1.getRanks() );
//        vmr.add( r2.getRanks() );
//        vmr.add( c3 );

        MultiChart.open(vmr, "GENERATED DATA", "t", "f(t)", true, "", null);

    }
}
