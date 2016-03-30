/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.hadoopts.hadoopts.loader;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class StockDataCollector {
    
    public static void main( String[] args ){
    
        Vector<Messreihe> ROWS = loadStockData();
        
        MultiChart.open(ROWS, true, "UK.csv");


    }
    
    private static Vector<Messreihe> loadStockData() {
        
        int [] YEARS = {2011,2012,2013,2014};
        
        return StockDataLoader2.concatRowsForYearsFromCache( YEARS );
        
        
    }
    
}
