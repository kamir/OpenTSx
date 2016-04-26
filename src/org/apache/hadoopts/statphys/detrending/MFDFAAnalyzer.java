package org.apache.hadoopts.statphys.detrending;

import org.apache.hadoopts.analysistools.HurstSurface;
import org.apache.hadoopts.data.TestDataFactory;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.data.series.MessreiheFFT;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;
import java.util.Vector;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.hadoopts.statphys.detrending.methods.DFACore;
import org.apache.hadoopts.statphys.detrending.methods.IDetrendingMethod;
import org.apache.hadoopts.statphys.detrending.methods.MFDFA;
import stdlib.StdDraw;
import stdlib.StdStats;

public class MFDFAAnalyzer {
    
    public static void process(Vector<Messreihe> rows, String runid ) throws Exception {
        
        // MultiChart.open(rows, true );
        
        String folder = runid;
        
        OriginProject project = new OriginProject();
        project.folderName = "databuffer/MFDFA/"+folder;
        project.initBaseFolder( project.folderName );
        
        stdlib.StdRandom.initRandomGen(1);

       /**
        * prepare a MFDFA2 Instance
        */
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(
                    DetrendingMethodFactory.MFDFA2);
        
        
        int z = 0;
        for( Messreihe m : rows ) {

            z++;
//            
//            // faster for tests;
//            if ( z > 1 ) 
//                return;
                    
            int order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues( 1000 );

            System.out.print( dfa.getClass() );

            int N = (int)m.yValues.size();

            dfa.setNrOfValues(N);

            // die Werte für die Fensterbreiten sind zu wählen ...
            dfa.initIntervalSlog();
            dfa.showS();

            StringBuffer calcLog1 = new StringBuffer();
            StringBuffer calcLog2 = new StringBuffer();

            Vector<Messreihe> vNonShuffled = processMFDFA(m, dfa, calcLog1, "_raw", project, false, true);  
            
            Vector<Messreihe> vShuffled = processMFDFA(m, dfa, calcLog2, "_shuffled", project, true, true);   
            
            /**
             * Here I can plot a Hurst-Surface.
             * 
             */
            boolean showLines = false;
      
            String label = "(" + m.getLabel() + ")";
            
            HurstSurface hs1 = new HurstSurface(vNonShuffled , "original data " + label, 5);
            
            HurstSurface hs2 = new HurstSurface(vShuffled, "shuffled data " + label, 5 );
            
            /**
             * Here we can do advanced Hurst Analysis.
             */

        };
    }

    public static void main(String args[]) throws Exception {
        
        String folder = "RunID";
        
        OriginProject project = new OriginProject();
        project.folderName = "databuffer/MFDFA/"+folder;
        project.initBaseFolder( project.folderName );
        
        stdlib.StdRandom.initRandomGen(1);

        /**
         * prepare a MFDFA2 Instance
         */
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(
                DetrendingMethodFactory.MFDFA2);
        
        int order = dfa.getPara().getGradeOfPolynom();
        dfa.getPara().setzSValues( 10000 );
        
        System.out.print( dfa.getClass() );

        int N = (int)Math.pow(2.0, 16);
        dfa.setNrOfValues(N);

        // die Werte für die Fensterbreiten sind zu wählen ...
        dfa.initIntervalSlog();
        dfa.showS();

        StringBuffer calcLog = new StringBuffer();
        
        /**
         * We have lots of them ...
         */
        // String randNrType = "StdCauchy";
        String randNrType = "RandomWalk";
//        String randNrType = "Uniform";
//        
        Messreihe rBMM1 = getTestDataToWorkWith( randNrType, N );
 
        boolean showLines = false;
        Vector<Messreihe> vNonShuffled = processMFDFA(rBMM1, dfa, calcLog, randNrType, project, false, showLines);            
        Vector<Messreihe> vShuffled = processMFDFA(rBMM1, dfa, calcLog, randNrType, project, true, showLines);            
      
        HurstSurface hs1 = new HurstSurface(vNonShuffled , "original data", 5);
        HurstSurface hs2 = new HurstSurface(vShuffled, "shuffled data", 5 );

    }
    


    public static Vector<Messreihe> processMFDFA(
            Messreihe rBMM1, 
            IDetrendingMethod dfa, 
            StringBuffer calcLog, 
            String randNrType, 
            OriginProject project, boolean doShuffle, boolean showPlot) throws Exception {
        
        Vector<Messreihe> v = new Vector<Messreihe>();
        
        MesswertTabelle mwtH = new MesswertTabelle();
        MesswertTabelle mwt = new MesswertTabelle();
               
        
        // --- Begin of PROZESSING ---
        int di = 1;
        
        if( doShuffle ) {
            for( int ii = 0 ; ii < 10; ii++) {
                rBMM1.shuffleYValues();
            }
            randNrType = randNrType.concat("_1");
        }
        else { 
            randNrType = randNrType.concat("_0");
        }
        
        mwt.setLabel(rBMM1.getLabel() + "_" + randNrType+"_MFDFA2");
        mwt.singleX=false;
        
        mwtH.setLabel(rBMM1.getLabel() + "_" + randNrType+"_HurstSurface");
        mwtH.singleX=false;
        
        dfa.setZR(rBMM1.getData()[1]);
        
        for (int i = -5; i <= 5; i = i + di) {

            System.out.println(i + " : " + rBMM1.getLabel()); 
            
            double q = i * 1.0;

            if (i != 0) {

                dfa.getPara().setQ(q);
                dfa.calc();

                Messreihe mr5 = dfa.getResultsMRLogLog();

                Messreihe mr_q = new Messreihe();
                mr_q.setLabel("q=" + q);
                for (int j = 0; j < 20; j++) {
                    double s = 1.0 + (double) j * 0.1;
                    double w = 0.75;
                    SimpleRegression sr = mr5.linFit(s, s + w);
                    double hqs = sr.getSlope();
                    mr_q.addValuePair(s + (0.5 * w), hqs);
                }

                SimpleRegression sr = mr5.linFit(1.0, 1.7);
                String line = "{alpha} range[1.0 ... 1.7] q=" + q + " : " + sr.getSlope();

                System.out.println(line);
                calcLog.append(line + "\n");

                mr5.setLabel(rBMM1.getLabel() + randNrType + " (q=" + q + ")");
                v.add(mr5);

                mwt.addMessreihe(mr5);
                mwtH.addMessreihe(mr_q);

                //        k.add(dfa.getZeitreiheMR());
                //        k.add(dfa.getProfilMR());

                String status = dfa.getStatus();
                double[][] results = dfa.getResults();

                System.out.println("> DFA-Status: " + "\n" + status + "\n#s=" + results[1].length);

            }

        }

        project.storeMesswertTabelle(mwt);
        project.storeMesswertTabelle(mwtH);

        if ( showPlot )
            MultiChart.open(v, rBMM1.getLabel() + " " + "F(s,q) " + randNrType, "log(s)", "log(F(s))", true, calcLog.toString(), null);
        
        //MultiChart.open(k, "Kontrolldaten", "t", "y(t)", false, "?");

        // --- END od processing ---
        return v;
    }
    
        public static void _test2( Vector<Messreihe> mr ) { 
        MultiDFATool dfaTool = new MultiDFATool();
        dfaTool.runDFA(mr, 2);
        System.out.println( "done... ");
    };

        
    private static Messreihe getTestDataToWorkWith(String randNrType, int N) {
 
            // (a) - monofractal series of random numbers
            Messreihe rBMM1 = (Messreihe) TestDataFactory.getDataSeriesRandomValues_RW( N );   // RANDOM WALK
//            Messreihe rBMM1 = (Messreihe) TestDataFactory.getDataSeriesRandomValues2( N );     // Gleichverteil

            
            // (b) - multifractal series of random numbers
//            Messreihe rBMM1 = (Messreihe) TestDataFactory.getDataSeriesBinomialMultifractalValues( N , 0.75 );
//            Messreihe rBMM1 = (Messreihe) TestDataFactory.getDataSeriesRandomValues_Cauchy( N ); // Cacuhy Verteilung
            
            return rBMM1;
            
    }
}
