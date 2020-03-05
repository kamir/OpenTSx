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
import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.core.TSBucket;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.semanpix.chart.simple.MultiChart;

import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        boolean showChart = true;

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
        System.out.println(">>> OPENTSX_SHOW_GUI=" + gui_prop);
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

        Vector<TSData> tsbd = null;

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





        TSOProducer prod = new TSOProducer();

        int i = 0;

        // while( i < 1) {

            long t0 = System.currentTimeMillis();

            i++;

            String URI = "http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#0815a";

            /**
             * Step 1: creation of the series ...
             */
            try {

                tsbd = tsb.createBucketWithRandomTS_sinus(baseOut + filename, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, "°C" );

            }
            catch (Exception ex) {
                Logger.getLogger(TSDataSineWaveGenerator.class.getName()).log(Level.SEVERE, null, ex);
            }

            long t1 = System.currentTimeMillis();

            Logger.getLogger(TSDataSineWaveGenerator.class.getName()).log(Level.SEVERE, "DURATION 1 : " + ((t1 - t0)/1000.0));
            System.out.println( "DURATION 1 : " + ((t1 - t0)/1000.0) );
            double generateDuration = ((t1 - t0)/1000.0);

            /**
             * Step 2: persist data in Kafka ... as full episodes.
             */
            if( persistEpisodes ) {
                prod.pushTSDataAsEpisodesToKafka_String_Avro(tsbd, URI);
                System.out.println("[pushTSDataAsEpisodesToKafka_String_Avro]");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd.size());
            }

            /**
             * Step 3: persist data in Kafka ... as full single events.
             */
            if( persistEvents ) {
                prod.pushTSDataAsEventsToKafka_String_Avro(tsbd, URI );
                System.out.println("[pushTSDataAsEventsToKafka_String_Avro]");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd.size());
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
             * Step 5: Convert raw data into "TimeSeriesObject" and show a chart.
             */
            if ( showChart ) {

                if (i == 1) {

                    int j = 0;
                    Vector<TimeSeriesObject> tsos = new Vector<TimeSeriesObject>();
                    for (TSData tsd : tsbd) {
                        TimeSeriesObject tso = tsd.getMessreihe();
                        tso.setLabel(tsd.label);
                        tsos.add(tso);
                    }

                    MultiChart.open(tsos, "DEMO1", "time [s]", "temperature [°C]", true, "no comment", null);

                    System.out.println(">>> READY." );

                }

            }
            else {

                System.out.println(">>> DONE." );

                System.exit(0);

            }

            tsbd = null;

        // }


    }

}
