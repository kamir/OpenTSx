/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.thesis;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;
import java.util.Vector;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.exporter.OriginProject;

/**
 *
 * @author kamir
 */
public class SchreiberSchmitzTester {
    
     public static void main(String args[]) throws Exception {
      
        // initilize the stdlib.StdRandom-Generator
        stdlib.StdRandom.initRandomGen(1);
        
        
        MeasurementTable mwt1 = new MeasurementTable();
        MeasurementTable mwt2 = new MeasurementTable();
        MeasurementTable mwt3 = new MeasurementTable();
        
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
        op.initSubFolder( "data" );
       
        TimeSeriesObjectFFT.debug = false;
        
        Vector<TimeSeriesObject> vr = new Vector<TimeSeriesObject>();
        
        int N = (int)Math.pow(2.0,16.0);
        
        TimeSeriesObject m1 = TimeSeriesObject.getGaussianDistribution( N , 10.0, 1.0);
        TimeSeriesObject m10 = TimeSeriesObject.getExpDistribution(N, 5.0);
        TimeSeriesObject m100 = TimeSeriesObject.getParetoDistribution(N, 2.5);
        
        //TimeSeriesObject m2 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(m1.copy(), true, true);
        //TimeSeriesObject m20 = LongTermCorrelationSeriesGenerator.getPhaseRandomizedRow(m10.copy(), true, true);
//        
//        TimeSeriesObject m31 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(), -2.0, true, true);
//        TimeSeriesObject m32 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(), -1.0, true, true);
//        TimeSeriesObject m33 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  0.0, true, true);
//        TimeSeriesObject m34 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  1.0, true, true);
        //TimeSeriesObject m35 = LongTermCorrelationSeriesGenerator.getRandomRow(m1.copy(),  1.0, true, true);
        
        TimeSeriesObject m51 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m1.copy(), 1.5, false, false, 1);
        TimeSeriesObject m52 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m10.copy(), 1.5, false, false, 1);
        TimeSeriesObject m53 = LongTermCorrelationSeriesGenerator.getRandomRowSchreiberSchmitz(m100.copy(), 1.5, false, false, 1);
        
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
