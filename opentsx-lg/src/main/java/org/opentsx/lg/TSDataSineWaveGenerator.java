/**
 * An HDFS client to create a SequenceFile with time series.
 *
 * KEY = LongWriteable VALUE = MessreiheData
 *
 * We do no load data, but we use a GENERATOR here ...
 *
 *
 *
 */
package org.opentsx.lg;

import org.opentsx.connectors.kafka.EventFlowStateProducer;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.core.TSBucket;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.lg.metrics.TSGBeanImpl;
import org.opentsx.lg.metrics.TSGMBean;
import org.opentsx.chart.simple.MultiChart;

import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.*;
import java.lang.management.*;

/**
 * This application generates a set of sine-waves.
 *
 * The set of time series episode is stored in a TSBucket on local disc.
 * The path is defined by:
 *
 *    baseOut = "./test-opentsx-lg/"
 *
 * A single TSBucket represents the data of one particular time slice.
 * This is related to a window in stream processing.
 *
 * The demo application writes the raw time series data to Kafka.
 *
 * We have two data models, one holds the data series as episodes,
 * and another is per event.
 *
 * Flow state data is sent to a third topic, named:
 *
 *     opentsx_event_flow_state
 *
 * using the class EventFlowStateProducer.
 *
 *
 *
 * @author kamir
 */
public class TSDataSineWaveGenerator {

    private static MBeanServer mbs = null;

    private static TSGMBean tsgBean = null;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        boolean showChart = true;

        try {
            registerMBean();
        }
        catch (NotCompliantMBeanException e) {
            e.printStackTrace();
            System.exit( -1 );
        }

        /**
         * the argument "off" turns off the GUI.
         */
        if ( args != null ) {
            if ( args.length > 0 ) {
                if( args[0].equals( "off") )
                    showChart = false;
            }
        }

        /**
         * the ENV VARIABLE OPENTSX_SHOW_GUI can turn off the GUI.
         */
        String gui_prop = System.getenv("OPENTSX_SHOW_GUI");

        /*
         *  by default we use 1000 iterations for episode generation.
         */
        int zIterations = 1000;

        String zIterations_tmp = System.getenv("OPENTSX_NUMBER_OF_ITERATIONS");
        System.out.println( "*** USE CUSTOM NR OF ITERATIONS *** (" + zIterations_tmp + ")");

        if( zIterations_tmp != null ) {
            zIterations = Integer.parseInt( zIterations_tmp );
            System.out.println( "["+zIterations+"]");
        }

        System.out.println(">>> OPENTSX_NUMBER_OF_ITERATIONS = " + zIterations );
        System.out.println(">>> OPENTSX_SHOW_GUI             = " + gui_prop);
        if( gui_prop != null ) {
            if (gui_prop.equals("false")) {
                System.out.println("*** turn the GUI off. ");
                showChart = false;
            }
            else
                showChart = true;
        }
        System.out.println(">>> showChart=" + showChart );

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        // which aspect should be demonstrated ?
        boolean persistEpisodes = true;
        boolean persistEvents = true;

        // RNG needs to be initialized ...
        RNGWrapper.init();

        // storage for our TSBucket.
        String baseOut = "./test-opentsx-lg/";
        String filename = "sine-waves";

        System.out.println(">>> TSDataSineWaveGenerator (start at : " + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">>> TSBucket is written to : " + baseOut);

        //boolean SKIP_WRITES = true;
        TSBucket tsb = new TSBucket();

        Vector<TSData> tsbd_a = null;
        Vector<TSData> tsbd_b = null;

        //
        // SIMULATE SOME MEASURED DATA from a set of machines  ...
        //
        // zMachines
        int zMachines = 4;
        int zSensorsPerMachine = 2;

        // NR OF TIME SERIES EPISODES TO GENERATE
        int ANZ = zMachines * zSensorsPerMachine;

        // Frequency range to select one from per series
        double fMIN = 1.0; 
        double fMAX = 10.0;

        // Amplitude range to select one from per series
        double aMIN = 1.0;
        double aMAX = 10.0;

        // Sampling Rate
        double SR = 10.0 * fMAX;    // data points per second

        // length of each episode
        double time = 1.0;           // episode length in seconds





        TSOProducer prod_A = new TSOProducer();

        TSOProducer prod_B = new TSOProducer();

        int i = 0;

        long tS = System.currentTimeMillis();

        while( i < zIterations ) {

            long t0 = System.currentTimeMillis();

            i++;

            String URI_A = "http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#0815a";
            String URI_B = "http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#17u4";

            /**
             * Step 1: creation of the series ...
             */
            try {

                tsbd_a = tsb.createBucketWithRandomTS_sinus(baseOut + filename, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, "°C" );
                tsbd_b = tsb.createBucketWithRandomTS_sinus(baseOut + filename, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, "°C" );

            }
            catch (Exception ex) {
                Logger.getLogger(TSDataSineWaveGenerator.class.getName()).log(Level.SEVERE, null, ex);
            }

            long t1 = System.currentTimeMillis();

            Logger.getLogger(TSDataSineWaveGenerator.class.getName()).log(Level.SEVERE, "DURATION 1 : " + ((t1 - t0)/1000.0));
            System.out.println( "DURATION 1 : " + ((t1 - t0)/1000.0) );
            double generateDuration = ((t1 - t0)/1000.0);

            ((TSGBeanImpl)tsgBean).trackRoundDuration( generateDuration );

            /**
             * Step 2: persist data in Kafka ... as pre-aggregated full episodes.
             */
            if( persistEpisodes ) {
                prod_A.pushTSDataAsEpisodesToKafka_String_Avro(tsbd_a, URI_A, "A");
                System.out.println("[pushTSDataAsEpisodesToKafka_String_Avro] {A}");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd_a.size());

                prod_B.pushTSDataAsEpisodesToKafka_String_Avro(tsbd_b, URI_B, "B");
                System.out.println("[pushTSDataAsEpisodesToKafka_String_Avro] {B}");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd_b.size());


                ((TSGBeanImpl)tsgBean).trackNumberOfTS( tsbd_a.size() + tsbd_b.size() );

            }

            /**
             * Step 3: persist data in Kafka ... as full single events.
             */
            if( persistEvents ) {
                prod_A.pushTSDataAsEventsToKafka_String_Avro(tsbd_a, URI_A );
                System.out.println("[pushTSDataAsEventsToKafka_String_Avro] {A}");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd_a.size());

                prod_B.pushTSDataAsEventsToKafka_String_Avro(tsbd_b, URI_B );
                System.out.println("[pushTSDataAsEventsToKafka_String_Avro] {B}");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd_b.size());
            }

            /**
             * Step 4: persist flow metadata in Kafka ... as events.
             */
            long t2 = System.currentTimeMillis();
            double sendDuration = ((t2 - t1)/1000.0);
            Properties stateProps = new Properties();
            stateProps.put( "generateDuration" , generateDuration );
            stateProps.put( "sendDuration" , sendDuration );
            stateProps.put( "generator" , "TSDataSineWaveGenerator" );
            stateProps.put( "persistEpisodes" , persistEpisodes );
            stateProps.put( "persistEvents" , persistEvents );

            stateProps.put( "l" , time * SR );
            stateProps.put( "z" , ANZ );
            stateProps.put( "t0" , t0 );
            stateProps.put( "t1" , t1 );
            stateProps.put( "t2" , t2 );

            EventFlowStateProducer efsp = new EventFlowStateProducer();
            efsp.pushFlowState( stateProps, "TSDataSineWaveGenerator_" + t0);

            Logger.getLogger(TSDataSineWaveGenerator.class.getName()).log(Level.SEVERE, "DURATION 2 : " + ((t2 - t1)/1000.0));



            /**
             * Step 5: Convert raw data into "TimeSeriesObject" and show a chart (only in 1-st iteration if not turned off).
             */
            if ( showChart ) {

                if (i == 1) {

                    int j = 0;
                    Vector<TimeSeriesObject> tsos = new Vector<TimeSeriesObject>();
                    for (TSData tsd : tsbd_a) {
                        TimeSeriesObject tso = tsd.getMessreihe();
                        tso.setLabel(tsd.label);
                        tsos.add(tso);
                    }

                    MultiChart.open(tsos, "DEMO1", "time [s]", "temperature [°C]", true, "no comment", null);

                    System.out.println(">>> READY." );

                }

            }

            System.out.println(">>> Round : " + i + " DONE." );

            tsbd_a = null;
            tsbd_b = null;

        }

    }

    private static void registerMBean() throws NotCompliantMBeanException {

        // Get the platform MBeanServer
        mbs = ManagementFactory.getPlatformMBeanServer();

        // Unique identification of MBeans
        tsgBean = (TSGMBean)new TSGBeanImpl();

        ObjectName beanName = null;

        try {
            // Uniquely identify the MBeans and register them with the platform MBeanServer
            beanName = new ObjectName("org.opentsx.lg.TSGMBean:name=TSGMBean");
            mbs.registerMBean(tsgBean, beanName);
            System.out.println( "### Register MBean: " + beanName );
        }
        catch(Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

}
