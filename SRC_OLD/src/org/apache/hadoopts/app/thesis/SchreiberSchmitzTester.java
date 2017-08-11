/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.hadoopts.app.thesis;

import static org.apache.hadoopts.app.thesis.LongTermCorrelationSeriesGenerator.tests;
import static org.apache.hadoopts.app.thesis.SurrogatDataGeneration.doDFA;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.data.series.MessreiheFFT;
import java.util.Vector;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;

/**
 *
 * @author kamir
 */
public class SchreiberSchmitzTester {
    
     public static void main(String args[]) throws Exception {
      
        // initilize the stdlib.StdRandom-Generator
        stdlib.StdRandom.initRandomGen(1);
        
        
        MesswertTabelle mwt1 = new MesswertTabelle();
        MesswertTabelle mwt2 = new MesswertTabelle();
        MesswertTabelle mwt3 = new MesswertTabelle();
        
        mwt1.singleX = false;
        mwt2.singleX = false;
        mwt3.singleX = false;
        
        mwt1.setLabel("panel1.csv");
        mwt2.setLabel("panel2.csv");
        mwt3.setLabel("panel3.csv");
        
        /**
         * Export location for OriginPro integration 
         */
        OriginProject op = new OriginProject();
        op.initBaseFolder("/Users/kamir/Documents/THESIS/dissertationFINAL/main/FINAL/LATEX/semanpix/Figure5.8");
        op.initFolder( "data" );
       
        MessreiheFFT.debug = false;
        
        Vector<Messreihe> vr = new Vector<Messreihe>(); 
        
        int N = (int)Math.pow(2.0,16.0);
        
        Messreihe m1 = Messreihe.getGaussianDistribution( N , 10.0, 1.0);
        Messreihe m10 = Messreihe.getExpDistribution(N, 5.0);
        Messreihe m100 = Messreihe.getParetoDistribution(N, 2.5);
        
        //Messreihe m2 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(m1.copy(), true, true);
        //Messreihe m20 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(m10.copy(), true, true);
//        
//        Messreihe m31 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(), -2.0, true, true);
//        Messreihe m32 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(), -1.0, true, true);
//        Messreihe m33 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  0.0, true, true);
//        Messreihe m34 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  1.0, true, true);
        //Messreihe m35 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  1.0, true, true);
        
        Messreihe m51 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m1.copy(), 1.5, false, false, 1);
        Messreihe m52 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m10.copy(), 1.5, false, false, 1);
        Messreihe m53 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m100.copy(), 1.5, false, false, 1);
        
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
                        "value", "# value", true, "?", null );
        
        
        
         
     }    
    
}
