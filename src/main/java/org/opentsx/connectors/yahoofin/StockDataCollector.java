/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.connectors.yahoofin;

import org.opentsx.connectors.yahoofin.StockDataLoader2;
import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class StockDataCollector {
    
    public static void main( String[] args ){
    
        Vector<TimeSeriesObject> ROWS = loadStockData();
        
        MultiChart.open(ROWS, true, "UK.csv");


    }
    
    private static Vector<TimeSeriesObject> loadStockData() {
        
        int [] YEARS = {2011,2012,2013,2014};
        
        return StockDataLoader2.concatRowsForYearsFromCache( YEARS );
        
        
    }
    
}
