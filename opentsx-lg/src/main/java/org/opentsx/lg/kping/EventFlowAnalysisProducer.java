package org.opentsx.lg.kping;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.connectors.kafka.topicmanager.TopicsCHECK;
import org.opentsx.connectors.kafka.topicmanager.TopicsUP;
import org.opentsx.core.TSBucket;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.lg.metrics.TSGBeanImpl;
import org.opentsx.lg.metrics.TSGMBean;

import org.opentsx.chart.simple.MultiChart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Vector;

/**
 * Generates one sine-wave series per round and sends its values as events to the topic OpenTSx_Events. The rounds come
 * from kping.producer.ziterations in the client profile (default 10). A failed send stops the rounds.
 */
public class EventFlowAnalysisProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EventFlowAnalysisProducer.class);

    private static MBeanServer mbs = null;

    private static TSGMBean tsgBean = null;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        LOG.info("starting EventFlowAnalysisProducer");


        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/opentsx-local.props";
        OpenTSxClusterLink.init();

        if ( !TopicsCHECK.execute() ) {

            TopicsUP.execute();

            if (!TopicsCHECK.execute()) {
                System.exit(1);
            }
        }

        boolean showChart = true;

        registerMBean();

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
        int zIterations = 10;

        String zs = OpenTSxClusterLink.getClientProperties().getProperty( "kping.producer.ziterations" );
        if ( zs != null ) {
            zIterations = Integer.parseInt( zs );
        }

        String zIterations_tmp = System.getenv("OPENTSX_NUMBER_OF_ITERATIONS");
        System.out.println( "*** USE CUSTOM NR OF ITERATIONS *** (" + zIterations_tmp + ")");

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

        // which aspect should be demonstrated ?
        boolean persistEpisodes = false;
        boolean persistEvents = true;

        // RNG needs to be initialized ...
        RNGWrapper.init();

        // storage for our TSBucket.
        String baseOut = "./test-opentsx-lg/";
        String filename = "sine-waves-event-by-event";

        System.out.println(">>> TSDataSineWaveGenerator (start at : " + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">>> TSBucket is written to : " + baseOut);

        TSBucket tsb = new TSBucket();

        Vector<TSData> tsbd_a = null;

        //
        // SIMULATE SOME MEASURED DATA from a set of machines  ...
        //
        // zMachines
        int zMachines = 1;
        int zSensorsPerMachine = 1;

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
        double time = 10.0;           // episode length in seconds



        TSOProducer prod_A = new TSOProducer();

        int i = 0;

        long tS = System.currentTimeMillis();

        while( i < zIterations ) {

            long t0 = System.currentTimeMillis();

            i++;

            String URI_A = "http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#0815a";

            /**
             * Step 1: creation of the series ...
             */
            tsbd_a = tsb.createBucketWithRandomTS_sinus(baseOut + filename, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, "°C" );

            long t1 = System.currentTimeMillis();

            double generateDuration = ((t1 - t0)/1000.0);

            ((TSGBeanImpl)tsgBean).trackRoundDuration( generateDuration );


            /**
             * Step 2: persist data in Kafka ... as pre-aggregated full episodes.
             */
            if( persistEpisodes ) {

                prod_A.pushTSDataAsEpisodesToKafka_String_Avro(tsbd_a, URI_A, "A");

                System.out.println("[pushTSDataAsEpisodesToKafka_String_Avro] {A}");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd_a.size());

                ((TSGBeanImpl)tsgBean).trackNumberOfTS( tsbd_a.size() );

            }

            long t2 = System.currentTimeMillis();

            /**
             * Step 3: persist data in Kafka ... as full single events.
             */
            if( persistEvents ) {

                prod_A.pushTSDataAsEventsToKafka_String_Avro( tsbd_a, URI_A );

                System.out.println("[pushTSDataAsEventsToKafka_String_Avro] {A}");
                System.out.println(">>> [t: " + i + "] -> size:" + tsbd_a.size());

            }

            LOG.info("round done round={} generateSeconds={} episodeSeconds={}", i, generateDuration, (t2 - t1) / 1000.0);

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

        }

    }

    private static void registerMBean() throws JMException {

        // Get the platform MBeanServer
        mbs = ManagementFactory.getPlatformMBeanServer();

        // Unique identification of MBeans
        tsgBean = new TSGBeanImpl();

        // Uniquely identify the MBeans and register them with the platform MBeanServer
        ObjectName beanName = new ObjectName("org.opentsx.lg.TSGMBean:name=TSGMBean");
        mbs.registerMBean(tsgBean, beanName);
        LOG.debug("MBean registered name={}", beanName);
    }

}
