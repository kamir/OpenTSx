package org.opentsx.lg.kping;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.topicmanager.TopicsUP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventFlowAnalysisSetup {

    private static final Logger LOG = LoggerFactory.getLogger(EventFlowAnalysisSetup.class);

    public static void main(String[] args) throws Exception {

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        LOG.info("creating the topics");

        TopicsUP.execute();

        LOG.info("topics created");

    }

}
