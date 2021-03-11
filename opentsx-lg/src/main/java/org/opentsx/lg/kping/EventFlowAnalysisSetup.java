package org.opentsx.lg.kping;

import java.io.IOException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.topicmanager.TopicsUP;

public class EventFlowAnalysisSetup {

    static final Logger logger = LogManager.getLogger(EventFlowAnalysisSetup.class.getName());

    public static void main(String[] args) throws IOException, InstantiationException, IllegalAccessException {

        /*
         * Define some basic configuration.
         */
        //OpenTSxClusterLink.OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/cpl.props";
        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        try {

            logger.info( "Start TopicSetup ..." );

            TopicsUP.execute();

            logger.info( "TopicSetup done." );

        }
        catch (Exception e) {
            e.printStackTrace();

            logger.error( "EXCEPTION: " + e.getMessage() );

        }

        System.exit( 0 );

    }

}
