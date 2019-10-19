/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.semanpix.chart.dynamic;

import org.jfree.data.time.DynamicTimeSeriesCollection;

/**
 *
 * @author kamir
 */
interface LineProcessor {
    
    public void init( DynamicSingleSeriesPlot plot );

    public void processString(String serverResponse, Collector c);

    public void setDataSet(DynamicTimeSeriesCollection dataset);

    
}
