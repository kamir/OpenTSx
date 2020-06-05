package org.opentsx.util;

import org.opentsx.connectors.kafka.TSOProducer;
import org.opentsx.util.topicmanager.TopicsUP;

public class OpenTSxClusterLink {

    public static void init() {

        /**
         * The ENV VARIABLE OPENTSX_TOPIC_MAP CONTAINS A FILENAME OF A FILE WITH ALL REQUIRED TOPICS.
         */
        String OPENTSX_TOPIC_MAP_FILE_NAME = System.getenv("OPENTSX_TOPIC_MAP_FILE_NAME");
        System.out.println(">>> OPENTSX_TOPIC_MAP_FILE_NAME=" + OPENTSX_TOPIC_MAP_FILE_NAME);
        if( OPENTSX_TOPIC_MAP_FILE_NAME != null ) {
            TopicsUP.init_TOPICS_DEF_FN( OPENTSX_TOPIC_MAP_FILE_NAME );
        }
        else {
            System.out.println("--- OPENTSX_TOPIC_MAP_FILE_NAME=" + TopicsUP.get_TOPICS_DEF_FN() );
        }



        /**
         * The ENV VARIABLE OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG CONTAINS A FILENAME OF A FILE WITH
         * ALL REQUIRED CFGs to access the primary cluster.
         */
        String OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = System.getenv("OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME");
        System.out.println(">>> OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=" + OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME);
        if( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME != null ) {
            TSOProducer.init_PROPS_FN( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME );
        }
        else {
            System.out.println("--- OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=" + TSOProducer.get_PROPS_FN() );
        }

    }
}
