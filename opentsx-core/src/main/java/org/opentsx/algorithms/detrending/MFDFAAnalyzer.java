package org.opentsx.algorithms.detrending;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.opentsx.analysistools.HurstSurface;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.generator.TestDataFactory;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.algorithms.detrending.methods.IDetrendingMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Vector;

public class MFDFAAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(MFDFAAnalyzer.class);
    
    public static void process(Vector<TimeSeriesObject> rows, String runid ) throws Exception {
        
        
        String folder = runid;
        
        OriginProject project = new OriginProject();
        project.folderName = "databuffer/MFDFA/"+folder;
        project.initBaseFolder( project.folderName );

        RNGWrapper.init();

        /**
         * prepare a MFDFA2 Instance
         */
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(
                    DetrendingMethodFactory.MFDFA2);
        
        
        int z = 0;
        for( TimeSeriesObject m : rows ) {

            z++;
                    
            int order = dfa.getPara().getGradeOfPolynom();
            dfa.getPara().setzSValues( 1000 );

            LOG.debug("processing series method={} series={}", dfa.getClass().getSimpleName(), m.getLabel());

            int N = (int)m.yValues.size();

            dfa.setNrOfValues(N);

            // choose the window sizes
            dfa.initIntervalSlog();
            dfa.showS();

            StringBuffer calcLog1 = new StringBuffer();
            StringBuffer calcLog2 = new StringBuffer();

            Vector<TimeSeriesObject> vNonShuffled = processMFDFA(m, dfa, calcLog1, "_raw", project, false, true);
            
            Vector<TimeSeriesObject> vShuffled = processMFDFA(m, dfa, calcLog2, "_shuffled", project, true, true);
            
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

        RNGWrapper.init();

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

        // choose the window sizes
        dfa.initIntervalSlog();
        dfa.showS();

        StringBuffer calcLog = new StringBuffer();
        
        /**
         * We have lots of them ...
         */
        String randNrType = "RandomWalk";
        TimeSeriesObject rBMM1 = getTestDataToWorkWith( randNrType, N );
 
        boolean showLines = false;
        Vector<TimeSeriesObject> vNonShuffled = processMFDFA(rBMM1, dfa, calcLog, randNrType, project, false, showLines);
        Vector<TimeSeriesObject> vShuffled = processMFDFA(rBMM1, dfa, calcLog, randNrType, project, true, showLines);
      
        HurstSurface hs1 = new HurstSurface(vNonShuffled , "original data", 5);
        HurstSurface hs2 = new HurstSurface(vShuffled, "shuffled data", 5 );

    }
    


    public static Vector<TimeSeriesObject> processMFDFA(
            TimeSeriesObject rBMM1,
            IDetrendingMethod dfa, 
            StringBuffer calcLog, 
            String randNrType, 
            OriginProject project, boolean doShuffle, boolean showPlot) throws Exception {
        
        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();
        
        MeasurementTable mwtH = new MeasurementTable();
        MeasurementTable mwt = new MeasurementTable();
               
        
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

            LOG.debug("mfdfa order q series={} q={}", rBMM1.getLabel(), i);
            
            double q = i * 1.0;

            if (i != 0) {

                dfa.getPara().setQ(q);
                dfa.calc();

                TimeSeriesObject mr5 = dfa.getResultsMRLogLog();

                TimeSeriesObject mr_q = new TimeSeriesObject();
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

                LOG.debug("fitted alpha series={} q={} alpha={}", rBMM1.getLabel(), q, sr.getSlope());
                calcLog.append(line + "\n");

                mr5.setLabel(rBMM1.getLabel() + randNrType + " (q=" + q + ")");
                v.add(mr5);

                mwt.addMessreihe(mr5);
                mwtH.addMessreihe(mr_q);


                double[][] results = dfa.getResults();

                LOG.debug("dfa status series={} windows={}", rBMM1.getLabel(), results[1].length);

            }

        }

        project.storeMeasurementTable(mwt);
        project.storeMeasurementTable(mwtH);

        if ( showPlot )
            MultiChart.open(v, rBMM1.getLabel() + " " + "F(s,q) " + randNrType, "log(s)", "log(F(s))", true, calcLog.toString(), null);
        

        // --- END od processing ---
        return v;
    }
    
        public static void _test2( Vector<TimeSeriesObject> mr ) {
        MultiDFATool dfaTool = new MultiDFATool();
        dfaTool.runDFA(mr, 2);
    };

        
    private static TimeSeriesObject getTestDataToWorkWith(String randNrType, int N) {
 
            // (a) - monofractal series of random numbers
            TimeSeriesObject rBMM1 = (TimeSeriesObject) TestDataFactory.getDataSeriesRandomValues_RW( N );   // RANDOM WALK

            
            return rBMM1;
            
    }
}
