package org.opentsx.lg.kping;

import ch.qos.logback.core.db.dialect.SybaseSqlAnywhereDialect;
import org.opentsx.util.OpenTSxClusterLink;
import org.opentsx.utils.topicmanager.TopicsCHECK;
import org.opentsx.utils.topicmanager.TopicsUP;

import java.io.IOException;

public class EventFlowAnalysisPrecheck {

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
