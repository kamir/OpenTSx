package org.opentsx.lg.kping;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.topicmanager.*;

import java.io.IOException;
import java.util.logging.Logger;

public class EventFlowAnalysisPrecheck {

    static Logger log = Logger.getLogger(EventFlowAnalysisPrecheck.class.getName());

    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        /*
         * Define some basic configuration.
         */
        OpenTSxClusterLink.OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/cpl.props";
        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        try {

            if (!TopicsCHECK.execute()) {

                System.out.println( "ANALYSIS TOPICS NOT AVAILABLE >>> Try to create them.");

                TopicsUP.execute();

                if (!TopicsCHECK.execute()) {
                    System.out.println( "A LATENCY ANALYSIS IS NOT POSSIBLE !!! => Check permissions!");
                    System.exit(-1);
                }
            }

        }
        catch (Exception e) {
            e.printStackTrace();
        }

    }
}
