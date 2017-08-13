package org.apache.hadoopts.app.dataseries;

import org.apache.hadoopts.data.io.MSExcelMessreihenLoader;
import org.apache.hadoopts.data.series.MessIntervall;
import org.apache.hadoopts.data.series.QualifiedTimeSeriesObject;
import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**  
 * 
 * Show the usage of the class "TimeSeriesObject" and its variants.
 *
 * @author Mirko Kämpf
 */
public class MessreiheTestApp {

    public static final String version =  "Version 1.0.0";

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        
        System.out.println( version );

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.addValuePair(1.0, 5.0);
        mr.addValuePair(2.0, 22.0);
        mr.addValuePair(3.0, 22.0);
        mr.addValuePair(4.0, 22.0);
        mr.addValuePair(5.0, 22.0);
        mr.addValuePair(11.0, 220.0);
        mr.addValuePair(121.0, 223.0);
        mr.addValuePair(141.0, 221.0);
        mr.addValuePair(1221.0, 222.0);

        
        
        mr.calcAverage();


        MessIntervall mi = new MessIntervall(mr, 2, 5);

        mr.show();
        mi.show();

        // teste die SHRINK Funktion
        TimeSeriesObject mr2 = new TimeSeriesObject();
        for( int i = 10; i < 50; i+=2 ) {
            mr2.addValuePair(i, i*i);
        }
        mr2.setLabel("PARABEL");

        TimeSeriesObject kleiner = mr2.shrinkX(21, 29);
        System.out.println( kleiner );

        String reihe = "1 2 x x 3 4 5 6 x x x 10"; //" 11 12 1 1 3 3 4 4 5 5 5 x x x x x x x x x x 7 7 9 1 1";
        QualifiedTimeSeriesObject qmr = new QualifiedTimeSeriesObject();
        qmr.setLabel("Testreihe");
        StringTokenizer st = new StringTokenizer(reihe);
        while( st.hasMoreTokens() ) {
            Object o = st.nextToken();
            qmr.addValue(o);
        };
        qmr.addValue(8);
        qmr.addValue(108);
        qmr.addValue(9);

        System.out.println( qmr );
        qmr.checkKonsistenz();

        QualifiedTimeSeriesObject qmr2 = qmr.setBinningX_average(2);
        System.out.println( qmr2 );
        qmr2.checkKonsistenz();


        QualifiedTimeSeriesObject qmr4 = qmr.setBinningX_average(4);
        System.out.println( qmr4 );
        qmr4.checkKonsistenz();



        File file = new File( "./data/in/SPEED_B.xls" );
        String tableName = "A";
        try {
            QualifiedTimeSeriesObject mr3 = MSExcelMessreihenLoader.loadQualifiedMessreihe(file, tableName, 1, 2, 11, 10090);
            
            //System.out.println(mr3);
            mr3.checkKonsistenz();

            TimeSeriesObject[] reihen = mr3.splitInto(1028);
            System.out.println( reihen.length );
            System.out.println( reihen[ reihen.length-1] );

        }
        catch (FileNotFoundException ex) {
            Logger.getLogger(MSExcelMessreihenLoader.class.getName()).log(Level.SEVERE, null, ex);
        }
        catch (IOException ex) {
            Logger.getLogger(MSExcelMessreihenLoader.class.getName()).log(Level.SEVERE, null, ex);
        }

        System.out.println( mr.getYValueForX2(121, 5) );
        
        
        
//        String fn = "D:\\WIKI\\count_links\\out_degree_hist.dat";
//        File f = new File( fn );
//        
//        TimeSeriesObject mr42 = MessreihenLoader.getLoader()._loadLogBinnedMessreihe_DIV_BY_BINWIDTH( f , 1, 2, 1.2, 1000000);
//        
//        TimeSeriesObject mr43 = mr42.calcLogLog();

    }

}
