package org.opentsx.lg.klatency;

import org.opentsx.connectors.kafka.klatency.LatencyTestEventProducer;
import org.opentsx.data.model.*;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.topicmanager.TopicsCHECK;
import org.opentsx.connectors.kafka.topicmanager.TopicsUP;
import org.opentsx.lg.metrics.TSGBeanImpl;
import org.opentsx.lg.metrics.TSGMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Sends latency test events to the topic latency_benchmark_request, one per round. The rounds come from
 * klatency.producer.ziterations in the client profile (default 100). A failed send stops the rounds.
 */
public class RequestResponseAnalysisProducer {

    private static final Logger LOG = LoggerFactory.getLogger(RequestResponseAnalysisProducer.class);

    private static MBeanServer mbs = null;

    private static TSGMBean tsgBean = null;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        LOG.info("starting RequestResponseAnalysisProducer");

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

        registerMBean();

        /*
         *  by default we use 100 iterations for event generation.
         */
        int zIterations = 100;

        String zs = OpenTSxClusterLink.getClientProperties().getProperty( "klatency.producer.ziterations" );
        if ( zs != null ) {
            zIterations = Integer.parseInt( zs );
        }

        System.out.println(">>> klatency.producer.ziterations = " + zIterations );

        sendTestEvents(new LatencyTestEventProducer(), zIterations);

    }

    static void sendTestEvents(LatencyTestEventProducer prod, int zIterations) {

        try {
            for (int i = 1; i <= zIterations; i++) {

                LatencyTesterEvent e = LatencyTesterEvent.getTestEvent( "DC2", "agent1", 900  );

                prod.pushTestEventToCluster( e );

                System.out.println(">>> Round : " + i + " DONE." );

            }
        }
        finally {
            prod.close();
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
