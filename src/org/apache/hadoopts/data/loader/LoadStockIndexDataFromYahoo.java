package org.apache.hadoopts.data.loader;

import org.apache.hadoopts.app.bucketanalyser.MacroTrackerFrame;
import org.apache.hadoopts.app.bucketanalyser.TSBucketSource;
import org.apache.hadoopts.app.bucketanalyser.TSBucketTransformation;
import static org.apache.hadoopts.app.bucketanalyser.MacroRecorder.loadOp;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.hadoopts.loader.StockDataLoader2;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LoadStockIndexDataFromYahoo {
    
    // if data is in cache we hold the names here
    static int[] YEARS_done = {2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010, 2011, 2012, 2013, 2014};
    
    // if data has to be loaded from Yahoo we use this arrays to have multiple
    // smaller rounds
    static int[] YEARS1 = {2003, 2004, 2005, 2006};
    static int[] YEARS2 = {2007, 2008, 2009, 2010};
    static int[] YEARS3 = {2011, 2012, 2013, 2014};

    /**
     * Control the behaviour
     * 
     * (A) Loading or 
     * (B) Analyzing the data
     */
    static boolean load = true;

    //
    // Used in (A)
    //
    // Which index is relevant for the study?
    static String[] INDEX = { "STI"};
    //static String[] INDEX = { "DAX2", "MDAX", "SDAX", "TECDAX", "IPC"};
    
    //
    // needed in (B)
    //
    static String[] INDEX_toLOAD = { "STI", "UK", "DAX2"};

    static String[] LABELS = { "Close", "Volume" };
    
    static public String column = "Close";

        
    public static void main(String[] ARGS) {
        
        MacroTrackerFrame.init("Global Financial Indices" );

        /**
         * New STOCK MARKET DATA is loaded into a local cache.
         */
        if ( load )
            StockDataLoader2.loadAndCacheStockTradingDataForYears(YEARS2, column, INDEX_toLOAD[1]);
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("Collection"));

        /**
         * STOCK MARKET DATA is loaded from a local cache. From here we
         * do pre processing for shipping to a Spark cluster.
         */

        if ( load ) 
            loadStockDataForYearsALL(column,YEARS1,0);
        

        if ( !load )
            loadStockDataForYearsALL(column,YEARS_done, 0);

        
//        i = 0;
//        if( openAllAvailable ){
//            
//            while( i < INDEX.length ) {
//                loadStockDataForYearsALL(column,YEARS_done , i );
//                i++;
//            }
//            
//        }

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
