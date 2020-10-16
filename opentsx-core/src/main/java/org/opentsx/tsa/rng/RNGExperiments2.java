/*
 * JCuda - Java bindings for NVIDIA CUDA
 *
 * Copyright 2008-2016 Marco Hutter - http://www.jcuda.org
 */
package org.opentsx.tsa.rng;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.chart.simple.SigmaFilter;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.Locale;
import java.util.Vector;

/**
 * This application compares multiple RNGs.<br>
 *
 * Each RNG generator extends the "RNGModule" base class.
 * Currently, three different RNGs are implemented this way:
 *
 * RNGModule
 * #########
 * This RNGModule provides random numbers created by the built in RNG.
 *
 *
 *
 *
 *
 *
 * The CUDA example is based on a port of the NVIDIA CURAND documentation example.
 *
 */
public class RNGExperiments2
{
    static int seed = 0;
    static int zRuns = 10;

    static boolean showPlotFrame = true;
    static boolean export = true;

    static boolean useSECURE = true;
    static boolean useJUR = true;

    static int[] lengths = { 100, 500, 1000, 2500, 5000, 7500, 10000, 25000, 50000, 75000 }; // , 2500000, 5000000, 7500000, 10000000 };

    public static void main(String args[])
    {

        Locale.setDefault(new Locale("en", "USA"));

        Vector<String> labels = new Vector<String>();

        Vector<RNGModule> modules = new Vector();

        Vector<TimeSeriesObject> results = new Vector();

        Vector<Vector<TimeSeriesObject>> tmp = new Vector();

        Vector<TimeSeriesObject> tsos = new Vector();


        /**
         * Prepare the RNGModules ...
         */
        if( useSECURE) {
            RNGModuleACMS rng3 = new RNGModuleACMS();
            modules.add(rng3);
            TimeSeriesObject mr3 = new TimeSeriesObject();
            labels.add(RNGModuleACMS.class.getName());
            results.add(mr3);
        }

        if( useJUR ) {
            RNGModuleJUR rng4 = new RNGModuleJUR();
            modules.add(rng4);
            TimeSeriesObject mr4 = new TimeSeriesObject();
            labels.add(RNGModuleJUR.class.getName());
            results.add(mr4);
        }

        /**
         * Iterate on all RNGModules ... and prepare the labels ...
         *
         * Each row will get a unique identifier.
         */
        int row_id = 0;

        for (RNGModule rngm : modules) {

            Vector<TimeSeriesObject> rTemp = new Vector<TimeSeriesObject>();

            for( int r=1; r <= zRuns; r++ ) {

                row_id++;

                TimeSeriesObject mrRun = new TimeSeriesObject();

                String label = rngm.toString() + "_" + r + "_" + row_id;

                System.out.println( label );

                mrRun.setLabel( label );

                rTemp.add( mrRun );

            }

            tmp.add( rTemp );

        }

        for (RNGModule rngm : modules) {
            System.out.println("Init RNG: " + rngm.toString() + " => " + seed);
            rngm.init(seed);
        }


        int r = 1;

        while ( r <= zRuns ) {

        System.out.println( ">> RUN " + r );

        for (int n : lengths) {

            int i = 0;

            for (RNGModule rngm : modules) {

                System.out.println(rngm.toString() + " => " + n);

                rngm.init(n);

                double t0 = System.currentTimeMillis();

                float[] hostData = rngm.createRandomSeries(n, seed);

                double t1 = System.currentTimeMillis();

                double dt = t1 - t0;

                // System.out.println(rngm.toString() + " => " + dt + " ms ");

                results.elementAt(i).addValuePair(n, dt);

                TimeSeriesObject tso = new TimeSeriesObject();


                 ///
                tso.label = rngm.getClass() + "_" + n + "_" + i + "___" + r;
                for( float fv : hostData ) {
                    tso.addValue( fv );
                }

                tmp.elementAt(i).elementAt(r-1).addValuePair(n, dt);

                tsos.add( tso );

                i++;
            }

        }

        r++;

    }
        Vector<TimeSeriesObject> show = new Vector<TimeSeriesObject>();

        int l = 0;
        for( Vector<TimeSeriesObject> runsResults : tmp ) {


            SigmaFilter sf = new SigmaFilter();

            for( TimeSeriesObject m : runsResults ) {
                sf.addCollect( m, false );
            }

            sf.aggregate();

            TimeSeriesObject mr =  TimeSeriesObject.averageForAll( runsResults );
            mr.xValues = labelVector();

            mr.setLabel( labels.elementAt( l ) );
            show.add( mr );

            l++;
        }

        if ( export ) {

                String title = "RNG DATA SET";
                String ty = "RANDOM NUMBER";
                String tx = "time [ms]";

                String fn = "ts_bucket";
                String tablename = "ts_bucket_tab";
                String topicname = "ts_bucket_topic";

                //TSBucketStore.getWriter().persistBucket_CSV(tsos, title, tx,ty, fn, "csv");

                try {
                    //TSBucketStore.getWriter().persistBucket_SequenceFile(tsos, title, fn, "seq");
                }
                catch (Exception e) {
                    //e.printStackTrace();
                }

                //TSBucketStore.getWriter().persistBucket_Cassandra(tsos, title, tablename );

                //TSBucketStore.getWriter().persistBucket_Kafka(tsos, title, topicname );

        }

        if ( showPlotFrame ) {

            String title = "Creation Time vs. Length of  Random Number Series on Multiple RNGs (zRuns=" + zRuns + ")";
            String tx = "# of RANDOM NUMBER";
            String ty = "time [ms]";

            MultiChart.setSmallFont();

            MultiChart.open(show, title, tx, ty, true);

            String fn = "example_bucket";
            String tablename = "example_bucket_tab";
            String topicname = "example_bucket_topic";

            //TSBucketStore.getWriter().persistBucket_CSV(show, title, tx,ty, fn, "csv");

            try {
                //TSBucketStore.getWriter().persistBucket_SequenceFile(show, title, fn, "seq");
            }
            catch (Exception e) {
                e.printStackTrace();
            }

            //TSBucketStore.getWriter().persistBucket_Cassandra(show, title, tablename );

            //TSBucketStore.getWriter().persistBucket_Kafka(show, title, topicname );

        }

        OriginProject op = new OriginProject();

        long time = System.currentTimeMillis();

            try {

                op.initBaseFolder( "out/rng_report_" + zRuns + "_" + time );
                op.addMessreihen( show , "rng" , true );

            }
            catch (Exception e) {
                e.printStackTrace();
            }


         try {
             org.opentsx.dsp.DSProjectHelper.setSysClipboardText(zRuns + "_" + time);
         }
         catch( Exception ex ) {
             System.out.println("> Copy & Paste feature not available. " );

         }

        System.out.println("#Please add the time stamp into the script: scripts/gnuplot/tsbucket_report.plot");
        System.out.println("> zRuns : " + zRuns);
        System.out.println("> time  : " + time);



    }

    private static Vector labelVector() {
        Vector<Double> l = new Vector<Double>();
        for(int le : lengths ) {
            l.add((double)le);
        }
        return l;
    }

}
