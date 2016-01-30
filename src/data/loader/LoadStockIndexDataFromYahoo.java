/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.loader;

import app.bucketanalyser.MacroTrackerFrame;
import app.bucketanalyser.TSBucketSource;
import app.bucketanalyser.TSBucketTransformation;
import static app.bucketanalyser.MacroRecorder.loadOp;
import chart.simple.MultiChart;
import data.series.Messreihe;
import hadoopts.loader.StockDataLoader2;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LoadStockIndexDataFromYahoo {

    
    static int[] YEARS_done = {2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010, 2011, 2012, 2013, 2014};
    
    static int[] YEARS1 = {2003, 2004, 2005, 2006};
    static int[] YEARS2 = {2007, 2008, 2009, 2010};
    static int[] YEARS3 = {2011, 2012, 2013, 2014};

    static String[] INDEX = { "DAX2"};
    //static String[] INDEX = { "DAX2", "MDAX", "SDAX", "TECDAX", "IPC"};
    
    static String[] INDEX_toLOAD = { "UK", "STI", "DAX"};

    static String[] LABELS = { "Close", "Volume" };
    static public String label = "Close";

    static boolean load = false;
    static boolean indexCheck = true;
    static boolean openAllAvailable = true;
        
    public static void main(String[] ARGS) {
        
        
        
        int i = 4; 
        int[] y = YEARS3;

        MacroTrackerFrame.init("Global Financial Indices (2003 ... 2014");

        /**
         * New STOCK MARKET DATA is loaded into a local cache.
         */
        if ( load )
            StockDataLoader2.loadAndCacheStockTradingDataForYears(y, label, INDEX_toLOAD[i]);


        
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("Collection"));



        /**
         * Cached STOCK MARKET DATA is loadef from cache for local processing
         * or for shipping to Spark-Cluster.
         */

        
        // loadStockDataForYears("Close", 2011,2012, INDEX[i] );
        
        if ( load ) 
            loadStockDataForYearsALL(label,y , i );

        

        if ( !load && !openAllAvailable )
            loadStockDataForYearsALL(label,YEARS_done , i );

        
        i = 0;
        if( openAllAvailable ){
            
            while( i < INDEX.length ) {
                loadStockDataForYearsALL(label,YEARS_done , i );
                i++;
            }
        }

    }

    private static void loadStockDataForYears(String col, int y1, int y2, String label) {

        Vector<Messreihe> r0 = StockDataLoader2.getRowsForYearFromCache(y1, y2, col, label);

        String windowName = "Components_" + label + "_" + col + "_" + y1 + " to " + y2;
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));

        MultiChart.open(r0, false, windowName);

    }

    private static void loadStockDataForYearsALL(String LABEL, int[] y, int i) {

        Vector<Messreihe> r0 = StockDataLoader2.concatRowsForYearsFromCache_ALL(y, LABEL, INDEX[i]);
        String windowName = "Components_" + INDEX[i] + "_" + LABEL + "_" + getYearLabel(y);
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));
        MultiChart.open(r0, false, windowName);

        // are the indices really orderd correct order.
        int ii = 0;
        Vector<Messreihe> r1 = new Vector<Messreihe>();
        for (Messreihe r : r0) {
            if (ii == 0) {
                r1.add(r.getXValuesAsMessreihe());
            }
            ii++;
        }
        
        if ( indexCheck )
            MultiChart.open(r1, false, "CHECK indices for " + windowName);

    }

    private static void loadStockDataForYears(String LABEL, int[] y, int i) {

        Vector<Messreihe> r0 = StockDataLoader2.concatRowsForYearsFromCache_4(y, LABEL, INDEX[i]);
        String windowName = "Components_" + INDEX_toLOAD[i] + "_" + LABEL + "_" + getYearLabel(y);
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));
        MultiChart.open(r0, false, windowName);

    }

    private static String getYearLabel(int[] ys) {
        StringBuffer sb = new StringBuffer();
        for (int y : ys) {
            sb.append("_" + y);
        }
        return sb.toString();
    }

}
