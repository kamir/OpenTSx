package org.opentsx.util;

import org.opentsx.utils.topicmanager.TopicsUP;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class OpenTSxClusterLink {

    public static String OPENTSX_TOPIC_MAP_FILE_NAME = null;
    public static String OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = null;

    public static Properties getClientProperties() {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        return properties;

    };

    private static void init_PROPS_FN(String fn){
        File f = new File(fn);
        System.out.println( "> PROPS_FN Path: " + f.getAbsolutePath() + " -> (r:" + f.canRead()+ ")" );

        if ( f.canRead() )
            PROPS_FN = fn;
        else {
            System.out.println("> Attempt to overwrite PROPS_FN in org.opentsx.connectors.kafka.TSOProducer failed.");
        }
        System.out.println("> PROPS_FN = " + PROPS_FN);
    };

    private static String PROPS_FN = "config/cpl.props";

    public static String get_PROPS_FN() {
        return  PROPS_FN;
    };

    public static void init() {

        /**
         * The ENV VARIABLE OPENTSX_TOPIC_MAP CONTAINS A FILENAME OF A FILE WITH ALL REQUIRED TOPICS.
         */
        OPENTSX_TOPIC_MAP_FILE_NAME = System.getenv("OPENTSX_TOPIC_MAP_FILE_NAME");
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
        OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = System.getenv("OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME");
        System.out.println(">>> OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=" + OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME);
        if( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME != null ) {
            init_PROPS_FN( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME );
        }
        else {
            System.out.println("--- OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=" + get_PROPS_FN() );
        }

    }
}
