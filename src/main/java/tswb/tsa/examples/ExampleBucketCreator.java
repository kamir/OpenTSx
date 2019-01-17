package tswb.tsa.examples;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.hadoopts.hadoopts.buckets.BucketLoader;
import org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_Sinus;
import org.apache.hadoopts.hadoopts.core.TSBucket;

import java.io.File;
import java.util.Date;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by kamir on 26.07.17.
 */
public class ExampleBucketCreator {

    public static void main(String args[]) {

        try {

            String label = "LABEL1";

//            TSBucketCreator_Sinus.main(args);
            File f = generateSinusSeries( 10, label );

            // ----------
            // introduces a PolySolve dependency which needs to be replaced by Apache Commons Math.
            // ----------
            //LongTermCorrelationSeriesGenerator.main(args);

            BucketLoader loader = new BucketLoader();
            TSBucket tsBucket = loader.loadBucket( f.getAbsolutePath() );
            Vector<TimeSeriesObject> rows = tsBucket.getBucketData();

            MultiChart.open(rows,true,"Sample Time Series");

        }
        catch (Exception e) {
            e.printStackTrace();
        }

    }


    /**
     * TODO: return the File object for the new bucket.
     *
     * @param z
     * @param filename
     * @return
     */
    private static File generateSinusSeries( int z, String filename ) {

        System.out.println(">>> Create a dummy time series bucket ... " );

        stdlib.StdRandom.initRandomGen(1);

            String baseOut = "./tsbucket/ex1/";

            File f = new File( baseOut );
                if ( !f.exists() ) {
                    f.mkdirs();
                }


            System.out.println(">>> SinusTSBucketCreator (" + new Date(System.currentTimeMillis()) + ")");
            System.out.println(">   OUT : " + baseOut);

            String s = filename;

            // We do no load data, but we use a GENERATOR here ...

            int ANZ = z;

            TSBucket tsb = new TSBucket();

            double fMIN = 1.0;
            double fMAX = 2000.0;
            double aMIN = 1.0;
            double aMAX = 10.0;

            double SR = 4.0 * fMAX;         // pro Sekunde

            double time = 10;           // Sekunden

            try {

                tsb.createBucketWithRandomTS_sinus(baseOut + s, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time );

            }
            catch (Exception ex) {
                Logger.getLogger(TSBucketCreator_Sinus.class.getName()).log(Level.SEVERE, null, ex);
            }

            return new File("tsbucket/ex1/LABEL1_sinus_.tsb.vec.seq");

        }

    }
