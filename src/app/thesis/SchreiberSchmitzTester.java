/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package app.thesis;

import static app.thesis.LongTermCorrelationSeriesGenerator.tests;
import static app.thesis.SurrogatDataGeneration.doDFA;
import chart.simple.MultiChart;
import data.series.Messreihe;
import data.series.MessreiheFFT;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class SchreiberSchmitzTester {
    
     public static void main(String args[]) throws Exception {
      
        // initilize the stdlib.StdRandom-Generator
        stdlib.StdRandom.initRandomGen(1);
       
        MessreiheFFT.debug = false;
        
        Vector<Messreihe> vr = new Vector<Messreihe>(); 
        
        int N = (int)Math.pow(2.0,17.0);
        
        Messreihe m1 = Messreihe.getGaussianDistribution( N , 10.0, 1.0);
        Messreihe m10 = Messreihe.getExpDistribution(N, 5.0);
        Messreihe m100 = Messreihe.getParetoDistribution(N, 5.0);
        
        //Messreihe m2 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(m1.copy(), true, true);
        //Messreihe m20 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(m10.copy(), true, true);
//        
//        Messreihe m31 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(), -2.0, true, true);
//        Messreihe m32 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(), -1.0, true, true);
//        Messreihe m33 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  0.0, true, true);
//        Messreihe m34 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  1.0, true, true);
        //Messreihe m35 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  1.0, true, true);
        
        Messreihe m51 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m1.copy(), 2, false, false, 1);
        Messreihe m52 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m10.copy(), 2, false, false, 1);
        Messreihe m53 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m100.copy(), 2, false, false, 1);
        
        vr.add( m1 );
//        vr.add( m2 );
//        
//        vr.add( m31 );
//        vr.add( m32 );
//        vr.add( m33 );
//        vr.add( m34 );
//        vr.add( m35 );
//        
        vr.add( m51 );
        vr.add( m52 );
        vr.add( m53 );
 
 
        
        
        
        MultiChart.open(vr, "Surrogat Series Generator (Schreiber Schmitz) - VDF", 
                        "value", "# value", true, "?" );
        
        
        
         
     }    
    
}
