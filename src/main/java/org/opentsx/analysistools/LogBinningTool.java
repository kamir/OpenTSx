/*
 * Berechnet das logarithmische Binning einer Messreihe bezogen auf die X-Achse.
 */
package org.opentsx.analysistools;

import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.loader.MessreihenLoader;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.exporter.MeasurementTable;

import java.io.File;
import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LogBinningTool {
    
    public static TimeSeriesObject getLogBinnedMessreihe(TimeSeriesObject mrOrig , int nrOfBins ) {
        TimeSeriesObject mr = new TimeSeriesObject();
         
        double data[][] = new double[2][nrOfBins];
                
        mr.setLabel( mrOrig.getLabel() + "_logBin" );
         
        return mr;
    };
    
    public static void main( String[] args ) throws IOException { 
                
        Vector<TimeSeriesObject> rows = new Vector<TimeSeriesObject>();
        
        // String fn = "D:\\WIKI\\outdegree_all_1.dat";
        
        String fn1 = "D:\\WIKI\\count_links\\out_degree_hist.dat";
        String fn2 = "D:\\WIKI\\count_links\\in_degree_hist.dat";
        
        String fn3 = "D:\\WIKI\\count_links\\tab_log_binned_degree_distr.dat";
        
        File f1 = new File( fn1 );
        File f2 = new File( fn2 );
        File f3 = new File( fn3 );
        
        TimeSeriesObject mr1 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f1, 1, 2, 1.2, 1000000);
        mr1 = mr1.calcLogLog();

        TimeSeriesObject mr2 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f2, 1, 2, 1.2, 1000000);
        mr2 = mr2.calcLogLog();

        rows.add( mr1 );
        rows.add( mr2 );
        
        MeasurementTable tab = new MeasurementTable();
        tab.addMessreihe(mr1);
        tab.addMessreihe(mr2);
        tab.writeToFile( new File( fn3 ) );
        
//        OriginProject pro = new OriginProject();
//        pro.addMessreihen(rows, "log_binned" );
//        
//        pro.closeAllWriter();
        
        MultiChart.open( rows , "", "log(# of links per page)", "log(# of pages)", true);
    }
    
}
