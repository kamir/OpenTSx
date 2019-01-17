/*
 * JCuda - Java bindings for NVIDIA CUDA
 *
 * Copyright 2008-2016 Marco Hutter - http://www.jcuda.org
 */
package tsa.rng;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.chart.simple.SigmaFilter;
import org.apache.hadoopts.data.export.OriginProject;
import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.util.Locale;
import java.util.Vector;

/**
 *
 * This application compares multiple RNGs.<br>
 *
 *
 *
 * The CUDA example is based on a port of the NVIDIA CURAND documentation example.
 *
 */
public class RNGExperiments
{

    static int zRuns = 3;
    static boolean showPlotFrame = false;

    static boolean useGPU = true;
    static boolean useACM = true;
    static boolean useSECURE = true;
    static boolean useJUR = true;

//    static int[] lengths = {(int) 8E7};

    static int[] lengths = { 100, 500, 1000, 2500, 5000, 7500, 10000, 20000,30000, 40000, 50000, 60000,70000,80000,90000, 100000, 200000,300000,400000, 500000,600000,700000, 800000,900000, 1000000, 2000000, 3000000, 4000000, 5000000, 6000000, 7000000, 8000000, 9000000 };

    public static void main(String args[])
    {

        Locale.setDefault(new Locale("en", "USA"));

        if ( args == null || args.length < 3) {
            zRuns = 10;
            showPlotFrame = true;
            useGPU = true;
        }
        else {
            zRuns = Integer.parseInt( args[0] );
            showPlotFrame = Boolean.parseBoolean( args[1] );
            useGPU = Boolean.parseBoolean( args[2] );
        }


        Vector<String> labels = new Vector<String>();

        Vector<RNGModule> modules = new Vector();

        Vector<TimeSeriesObject> results = new Vector();

        Vector<Vector<TimeSeriesObject>> tmp = new Vector();





        if( useACM ) {
            RNGModuleACMR rng2 = new RNGModuleACMR();
            modules.add(rng2);
            TimeSeriesObject mr2 = new TimeSeriesObject();
            labels.add(RNGModuleACMR.class.getName());
            results.add(mr2);
        }


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

        if( useGPU ) {

            //
            // RNGModuleCUDAImpl
            //
            // we need a classloader to load the class lazy, since it is implemented in
            // a different package.

            RNGModuleCUDA rng1 = null;

/*

            modules.add( rng1 );
            TimeSeriesObject mr1 = new TimeSeriesObject();
            labels.add( RNGModuleCUDA.class.getName() );
            results.add(mr1);

 */

        }



        for (RNGModule rngm : modules) {

            Vector<TimeSeriesObject> rTemp = new Vector<TimeSeriesObject>();
            for( int r=0; r < zRuns; r++ ) {
                TimeSeriesObject mrRun = new TimeSeriesObject();
                mrRun.setLabel( rngm.toString() + "_" + r );

                rTemp.add( mrRun );

            }

            tmp.add( rTemp );

        }

        int r = 0;

        while ( r < zRuns ){

        System.out.println( ">> RUN " + r );

        for (int n : lengths) {

            int i = 0;
            for (RNGModule rngm : modules) {

                System.out.println(rngm.toString() + " => " + n);

                rngm.init(n);

                double t0 = System.currentTimeMillis();

                float[] hostData = rngm.createRandomSeries(n, n);

                double t1 = System.currentTimeMillis();

                double dt = t1 - t0;

                // System.out.println(rngm.toString() + " => " + dt + " ms ");

                results.elementAt(i).addValuePair(n, dt);

                tmp.elementAt(i).elementAt(r).addValuePair(n, dt);

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

        if ( showPlotFrame ) {

            String title = "Creation Time vs. Length of  Random Number Series on Multiple RNGs (zRuns=" + zRuns + ")";
            String tx = "# of random numbers";
            String ty = "creation time [ms]";

            MultiChart.setSmallFont();

            MultiChart.open(show, title, tx, ty, true);


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
             dsp.DSProjectHelper.setSysClipboardText(zRuns + "_" + time);
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
