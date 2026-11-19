package org.opentsx.lg.kping;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.topicmanager.*;

/**
 * Creates the kping topics when one is missing. Exit status: 0 when the topics exist, 1 when they cannot be created
 * or the check fails.
 */
public class EventFlowAnalysisPrecheck {

    public static void main(String[] args) throws Exception {

        /*
         * Define some basic configuration.
         */
        OpenTSxClusterLink.OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/opentsx-local.props";
        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        if (!TopicsCHECK.execute()) {

            System.out.println( "ANALYSIS TOPICS NOT AVAILABLE >>> Try to create them.");

            TopicsUP.execute();

            if (!TopicsCHECK.execute()) {
                System.out.println( "A LATENCY ANALYSIS IS NOT POSSIBLE !!! => Check permissions!");
                System.exit(1);
            }
        }

    }
}
