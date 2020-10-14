package org.opentsx.lg.kping;

import org.opentsx.util.OpenTSxClusterLink;
import org.opentsx.utils.topicmanager.TopicsUP;

import java.io.IOException;

public class EventFlowAnalysisSetup {

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

            TopicsUP.execute();

        }
        catch (Exception e) {
            e.printStackTrace();
        }

    }

}
