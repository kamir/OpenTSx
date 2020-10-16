/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.chart.dynamic;

import org.jfree.data.time.DynamicTimeSeriesCollection;

/**
 *
 * @author kamir
 */
public class FloatLineProcessor implements LineProcessor {

    DynamicSingleSeriesPlot plot = null;
    
    @Override
    public void init(DynamicSingleSeriesPlot plot) {
        plot.setLineProcessor( this );
        this.plot = plot;
    }

    @Override
    public void processString(String serverResponse, Collector c) {
        
        String[] V = serverResponse.split(" ");
        float i = 0;
        float sum = 0;
        for( String s : V ) {
            sum = sum + Float.parseFloat(s);
            i = i + 1;
        }
        
        float f = sum / i;
        c.updateTS(f);
            
    }

    @Override
    public void setDataSet(DynamicTimeSeriesCollection dataset) {
        
    }
    
    
}
