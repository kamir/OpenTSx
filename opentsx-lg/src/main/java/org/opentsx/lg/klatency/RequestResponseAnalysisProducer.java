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
package org.opentsx.lg.klatency;

import org.opentsx.connectors.kafka.klatency.LatencyTestEventProducer;
import org.opentsx.data.model.*;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.kping.TSOEventAnalysisProducer;
import org.opentsx.connectors.kafka.topicmanager.TopicsCHECK;
import org.opentsx.connectors.kafka.topicmanager.TopicsUP;
import org.opentsx.core.TSData;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.lg.metrics.TSGBeanImpl;
import org.opentsx.lg.metrics.TSGMBean;

import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Date;
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
public class RequestResponseAnalysisProducer {

    static Logger log = Logger.getLogger(RequestResponseAnalysisProducer.class.getName());

    private static MBeanServer mbs = null;

    private static TSGMBean tsgBean = null;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        log.info( "Start RequestResponseAnalysisProducer ..." );

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/cpl_iMac.props";
        OpenTSxClusterLink.init();

        try {

            if ( !TopicsCHECK.execute() ) {

                TopicsUP.execute();

                if (!TopicsCHECK.execute()) {
                    System.exit(-1);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

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
         *  by default we use 100 iterations for event generation.
         */
        int zIterations = 100;

        String zs = OpenTSxClusterLink.getClientProperties().getProperty( "klatency.producer.ziterations" );
        if ( zs != null ) {
            zIterations = Integer.parseInt( zs );
        }

        String zIterations_tmp = System.getenv("OPENTSX_NUMBER_OF_ITERATIONS");
        System.out.println( "*** USE CUSTOM NR OF ITERATIONS *** (" + zIterations_tmp + ")");

        System.out.println(">>> OPENTSX_NUMBER_OF_ITERATIONS = " + zIterations );

        int i = 0;

        long tS = System.currentTimeMillis();

        LatencyTestEventProducer prod = new LatencyTestEventProducer();



        while( i < zIterations ) {

            i++;

            /**
             * Step 1: creation of the series ...
             */
            try {

                LatencyTesterEvent e = LatencyTesterEvent.getTestEvent( "DC2", "agent1", 900  );

                prod.pushTestEventToCluster( e );

            }
            catch (Exception ex) {
                Logger.getLogger(RequestResponseAnalysisProducer.class.getName()).log(Level.SEVERE, null, ex);
            }

            System.out.println(">>> Round : " + i + " DONE." );

        }

        prod.close();

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
