package org.opentsx.lg;

import org.opentsx.connectors.kafka.EventFlowStateProducer;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.core.TSBucket;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.lg.metrics.TSGBeanImpl;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;
import java.util.function.Supplier;

import javax.management.*;
import java.lang.management.*;

/**
 * Generates sine-wave series in rounds.
 *
 * Each round creates two buckets of 8 series (4 machines with 2 sensors) with 100 values each and writes them to
 * sine-waves.csv in opentsx.demo.output.dir (OPENTSX_DEMO_OUTPUT_DIR), or to the SequenceFile sine-waves.tsb.vec.seq
 * when opentsx.demo.output.format (OPENTSX_DEMO_OUTPUT_FORMAT) is not csv; the second bucket of a round overwrites the
 * first. Each round prints one line on stdout: round done round=<n> series=16 generateSeconds=<s> sendSeconds=<s>.
 * opentsx.number.of.iterations (OPENTSX_NUMBER_OF_ITERATIONS) sets the number of rounds. opentsx.show.gui
 * (OPENTSX_SHOW_GUI) decides whether the first round opens a chart; without the key, the argument off turns it off.
 * opentsx-default.properties sets ./data/temp, 3 rounds, no chart and no Kafka.
 *
 * With opentsx.use.kafka=true (OPENTSX_USE_KAFKA) each round also sends the series as episodes to OpenTSx_Episodes_A and
 * OpenTSx_Episodes_B, as events to OpenTSx_Events, and one flow-state record to OpenTSx_Event_Flow_State. All rounds
 * share one EventFlowStateProducer, which is closed when the rounds end. A failed send stops the rounds.
 */
public class TSDataSineWaveGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(TSDataSineWaveGenerator.class);

    private static MBeanServer mbs = null;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        boolean showChart = true;

        TSGBeanImpl tsgBean = registerMBean();

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
        ConfigManager cfg = ConfigManager.getInstance();
        String gui_prop = cfg.getString("opentsx.show.gui", System.getenv("OPENTSX_SHOW_GUI"));
        String kafka_prop = cfg.getString("opentsx.use.kafka", System.getenv("OPENTSX_USE_KAFKA"));

        boolean useKafka = Boolean.parseBoolean( kafka_prop );
        /*
         *  by default we use 1000 iterations for episode generation.
         */
        int zIterations = 1000;

        String zIterations_tmp = cfg.getString("opentsx.number.of.iterations", System.getenv("OPENTSX_NUMBER_OF_ITERATIONS"));

        if( zIterations_tmp != null ) {
            zIterations = Integer.parseInt( zIterations_tmp );
        }

        if( gui_prop != null ) {
            showChart = !gui_prop.equals("false");
        }
        System.out.println(">>> OPENTSX_USE_KAFKA            = " + useKafka);
        System.out.println(">>> OPENTSX_NUMBER_OF_ITERATIONS = " + zIterations );
        System.out.println(">>> showChart=" + showChart );

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/opentsx-local.props";
        OpenTSxClusterLink.init();

        // RNG needs to be initialized ...
        RNGWrapper.init();

        // storage for our TSBucket.
        String baseOut = cfg.getString("opentsx.demo.output.dir", System.getenv("OPENTSX_DEMO_OUTPUT_DIR"));
        if (baseOut == null || baseOut.trim().isEmpty()) {
            baseOut = "./data/temp/";
        }
        if (!baseOut.endsWith("/")) {
            baseOut = baseOut + "/";
        }
        new File(baseOut).mkdirs();

        System.out.println(">>> TSDataSineWaveGenerator (start at : " + new Date(System.currentTimeMillis()) + ")");
        System.out.println(">>> TSBucket is written to : " + baseOut);

        generate(zIterations, showChart, baseOut, useKafka ? new TSOProducer() : null, EventFlowStateProducer::new, tsgBean);

    }

    /**
     * Runs the rounds. Without a producer for the series ({@code series} is null) nothing is sent and
     * {@code flowStates} is not called.
     */
    static void generate(int zIterations, boolean showChart, String baseOut, TSOProducer series,
            Supplier<EventFlowStateProducer> flowStates, TSGBeanImpl tsgBean) throws Exception {

        String filename = "sine-waves";

        TSBucket tsb = new TSBucket(false);

        // which aspect should be demonstrated ?
        boolean persistEpisodes = true;
        boolean persistEvents = true;

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

        String URI_A = "http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#0815a";
        String URI_B = "http://www.w3.org/TR/2004/metrics-owl-20200210/deviceID#17u4";

        EventFlowStateProducer efsp = series == null ? null : flowStates.get();

        try {
            for (int i = 1; i <= zIterations; i++) {

                long t0 = System.currentTimeMillis();

                /**
                 * Step 1: creation of the series ...
                 */
                Vector<TSData> tsbd_a = tsb.createBucketWithRandomTS_sinus(baseOut + filename, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, "°C" );
                Vector<TSData> tsbd_b = tsb.createBucketWithRandomTS_sinus(baseOut + filename, ANZ, fMIN, fMAX, aMIN, aMAX, SR, time, null, "°C" );

                long t1 = System.currentTimeMillis();

                double generateDuration = ((t1 - t0)/1000.0);

                tsgBean.trackRoundDuration( generateDuration );

                /**
                 * Step 2: persist data in Kafka ... as pre-aggregated full episodes.
                 */
                if( persistEpisodes && series != null) {
                    series.pushTSDataAsEpisodesToKafka_String_Avro(tsbd_a, URI_A, "A");
                    series.pushTSDataAsEpisodesToKafka_String_Avro(tsbd_b, URI_B, "B");

                    tsgBean.trackNumberOfTS( tsbd_a.size() + tsbd_b.size() );
                }

                /**
                 * Step 3: persist data in Kafka ... as full single events.
                 */
                if( persistEvents && series != null) {
                    series.pushTSDataAsEventsToKafka_String_Avro(tsbd_a, URI_A );
                    series.pushTSDataAsEventsToKafka_String_Avro(tsbd_b, URI_B );
                }

                /**
                 * Step 4: persist flow metadata in Kafka ... as events.
                 */
                long t2 = System.currentTimeMillis();
                double sendDuration = ((t2 - t1)/1000.0);

                if ( efsp != null ) {
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

                    efsp.pushFlowState(stateProps, "TSDataSineWaveGenerator_" + t0);
                }

                System.out.println("round done round=" + i + " series=" + (tsbd_a.size() + tsbd_b.size())
                        + " generateSeconds=" + generateDuration + " sendSeconds=" + sendDuration);

                /**
                 * Step 5: Convert raw data into "TimeSeriesObject" and show a chart (only in 1-st iteration if not turned off).
                 */
                if ( showChart && i == 1 ) {

                    Vector<TimeSeriesObject> tsos = new Vector<TimeSeriesObject>();
                    for (TSData tsd : tsbd_a) {
                        TimeSeriesObject tso = tsd.getMessreihe();
                        tso.setLabel(tsd.label);
                        tsos.add(tso);
                    }

                    MultiChart.open(tsos, "DEMO1", "time [s]", "temperature [°C]", true, "no comment", null);

                }

            }
        }
        finally {
            if ( efsp != null ) {
                efsp.close();
            }
        }

    }

    private static TSGBeanImpl registerMBean() throws JMException {

        // Get the platform MBeanServer
        mbs = ManagementFactory.getPlatformMBeanServer();

        TSGBeanImpl tsgBean = new TSGBeanImpl();

        // Uniquely identify the MBeans and register them with the platform MBeanServer
        ObjectName beanName = new ObjectName("org.opentsx.lg.TSGMBean:name=TSGMBean");
        mbs.registerMBean(tsgBean, beanName);
        LOG.debug("MBean registered name={}", beanName);

        return tsgBean;
    }

}
