/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package app.thesis.experimental;

import chart.simple.MultiChart;
import data.series.Messreihe;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class StdTester {
    
    
    public static void main( String[] args ) {
        
        stdlib.StdRandom.initRandomGen(1);
        
        Messreihe r1 = Messreihe.getGaussianDistribution( 500, 10, 2);
        Messreihe r2 = Messreihe.getGaussianDistribution( 500, -10, 2);
        
        Messreihe r3 = r1.exchangeRankWise( r2 );
        
        Vector<Messreihe> vmr = new Vector<Messreihe>();
        vmr.add( r1);
        vmr.add( r2);
        vmr.add( r3 );
        
//        Messreihe c3 = r3.getRanks()[0];
//        c3.add_to_Y(-10.0);
        
//        vmr.add( r1.getRanks() );
//        vmr.add( r2.getRanks() );
//        vmr.add( c3 );

        MultiChart.open(vmr, "GENERATED DATA", "t", "f(t)", true, "");
        
        
        
    }
}
