/*
 * Berechnet das logarithmische Binning einer Messreihe bezogen auf die X-Achse.
 */
package org.apache.hadoopts.statistics.logbinning;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.io.LineFilter;
import org.apache.hadoopts.data.io.MessreihenLoader;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.data.export.OriginProject;

import java.io.File;
import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LogBinningToolv2 {
    
    public static TimeSeriesObject getLogBinnedMessreihe(TimeSeriesObject mrOrig , int nrOfBins ) {
        
        TimeSeriesObject mr = new TimeSeriesObject();
        double data[][] = new double[2][nrOfBins];
        mr.setLabel( mrOrig.getLabel() + "_logBin" );
        return mr;
    
    };
    
    public static void main( String[] args ) throws IOException { 
           
        Vector<TimeSeriesObject> rows = new Vector<TimeSeriesObject>();   // log
        Vector<TimeSeriesObject> rows2 = new Vector<TimeSeriesObject>();  // normal
                
        
        String locationOfData = "/Volumes/MyExternalDrive/CALCULATIONS/WIKIPEDIA.network/degreedistribution/";
                
        MessreihenLoader.debug = true;
        
        String fn1 = locationOfData+"in/out_degree_hist2.dat";
        String fn2 = locationOfData+"in/in_degree_hist2.dat"; 
        
        String fn0 = locationOfData+"in/in_degree_hist2_REDIRECT_IN_ONLY.dat";
        String fn3 = locationOfData+"in/out_degree_hist2_REDIRECT_IN_ONLY.dat";
        
        String fn4 = locationOfData+"in/in_degree_hist2_REDIRECT_OUT_ONLY.dat";
        String fn5 = locationOfData+"in/out_degree_hist2_REDIRECT_OUT_ONLY.dat";
        
        File f0 = new File( fn0 );
        File f1 = new File( fn1 );
        File f2 = new File( fn2 );
        File f3 = new File( fn3 );        
        File f4 = new File( fn4 );
        File f5 = new File( fn5 );
        
//        DataFileTool.head( f0 , 10 );
//        DataFileTool.head( f1 , 10 );
//        DataFileTool.head( f2 , 10 );
//        DataFileTool.head( f3 , 10 );
//        DataFileTool.head( f4 , 10 );
//        DataFileTool.head( f5 , 10 );
        
//         System.exit( 0 );
//           
        int maxValue = 1000000;
        double f = 1.2;
        MessreihenLoader.debug = false;
        //MessreihenLoader.limit = 10000;
        
               
        TimeSeriesObject mr1 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f1, 1, 2, f, maxValue, " ", new LineFilter());
        TimeSeriesObject mr2 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f2, 1, 2, f, maxValue, " ", new LineFilter());
        TimeSeriesObject mr3 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f0, 1, 2, f, maxValue, " ", new LineFilter());
        TimeSeriesObject mr4 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f3, 1, 2, f, maxValue, " ", new LineFilter() );
        TimeSeriesObject mr5 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f4, 1, 2, f, maxValue, " ", new LineFilter());
        TimeSeriesObject mr6 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f5, 1, 2, f, maxValue, " ", new LineFilter() );

        TimeSeriesObject mr6L = mr6.calcLogLog();
        TimeSeriesObject mr5L = mr5.calcLogLog();
        TimeSeriesObject mr4L = mr4.calcLogLog();
        TimeSeriesObject mr3L = mr3.calcLogLog();
        TimeSeriesObject mr2L = mr2.calcLogLog();
        TimeSeriesObject mr1L = mr1.calcLogLog();
                
        mr1L.setFileName( "mr1L" );
        mr2L.setFileName( "mr2L" );
        mr3L.setFileName( "mr3L" );
        mr4L.setFileName( "mr4L" );
        mr5L.setFileName( "mr5L" );
        mr6L.setFileName( "mr6L" );
        
        rows.add( mr1L );
        rows.add( mr2L );
        rows.add( mr3L );
        rows.add( mr4L );
        rows.add( mr5L );
        rows.add( mr6L );
        
        rows2.add( mr1 );
        rows2.add( mr2 );
        rows2.add( mr3 );
        rows2.add( mr4 );
        rows2.add( mr5 );
        rows2.add( mr6 );
        
        OriginProject pro = new OriginProject();
        pro.folderName = locationOfData+"out/LogBinningToolv2/";
        pro.createProjectFile();
        
        pro.addMessreihen(rows, "log_log_binned", true );
//        pro.addMessreihen(rows2, "normal_log_binned", true );
        
        pro.closeAllWriter();
        
        MultiChart.open( rows , "degree distribution (Master Join) rows", "log(# of links per page)", "log(# of pages)", true);
//        MultiChart.open( rows2 , "degree distribution (Master Join) rows2", "# of links per page", "log(# of pages)", true);
        
        System.out.println("Done ...");
    }
        
}
 