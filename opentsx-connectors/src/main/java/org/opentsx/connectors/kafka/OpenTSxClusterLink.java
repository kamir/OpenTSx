package org.opentsx.connectors.kafka;

import org.opentsx.connectors.kafka.topicmanager.TopicsUP;

import java.io.*;
import java.util.Properties;
import java.util.Vector;

public class OpenTSxClusterLink {

    public static final String OPENTSX_TOPIC_MAP_FILE_NAME = "./config/topiclist.def";;
    public static final String OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = "./config/cpl.props";;

    public static Properties getClientProperties() {

        File f = new File( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME  );

        System.out.println( ">>> Client properties from file: " + f.getAbsolutePath() + " ("+f.canRead()+")");

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));

            System.out.println();
            properties.list( System.out );
            System.out.println();
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

    public static String PROPS_FN = "./config/cpl.props";

    public static String get_PROPS_FN() {
        return  PROPS_FN;
    };

    public static void init() {

        File f = new File( "." );
        System.out.println( "WP: " + f.getAbsolutePath() );

        /**
         * The ENV VARIABLE OPENTSX_TOPIC_MAP CONTAINS A FILENAME OF A FILE WITH ALL REQUIRED TOPICS.
         */
        OPENTSX_TOPIC_MAP_FILE_NAME = System.getenv("OPENTSX_TOPIC_MAP_FILE_NAME");

        System.out.println("--- ***** => " + OPENTSX_TOPIC_MAP_FILE_NAME );

        System.out.println(">>> OPENTSX_TOPIC_MAP_FILE_NAME=" + OPENTSX_TOPIC_MAP_FILE_NAME);

        if( OPENTSX_TOPIC_MAP_FILE_NAME != null ) {

            TopicsUP.init_TOPICS_DEF_FN( OPENTSX_TOPIC_MAP_FILE_NAME );

            System.out.println("--- OPENTSX_TOPIC_MAP_FILE_NAME => " + OPENTSX_TOPIC_MAP_FILE_NAME );

        }
        else {

            System.out.println("--- OPENTSX_TOPIC_MAP_FILE_NAME => [NULL] " + TopicsUP.get_TOPICS_DEF_FN() );

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

    public static String getPropsAsString() {

        StringWriter writer = new StringWriter();
        OpenTSxClusterLink.getClientProperties().list(new PrintWriter(writer));

        return writer.getBuffer().toString();

    }

    public static String getPropsAsString_NO_SECURITY_PROPS() {
        Vector<String> keysToSkip = new Vector<>();
        keysToSkip.add("ssl.endpoint.identification.algorithm");
        keysToSkip.add("sasl.mechanism");
        keysToSkip.add("sasl.jaas.config");
        keysToSkip.add("security.protocol");
        keysToSkip.add("key.serializer");
        keysToSkip.add("value.serializer");
        keysToSkip.add("key.deserializer");
        keysToSkip.add("value.deserializer");
        keysToSkip.add("schema.registry.url");
        keysToSkip.add("basic.auth.credentials.source");
        keysToSkip.add("schema.registry.basic.auth.user.info");

        Properties propsToShow = new Properties();
        for( String k : OpenTSxClusterLink.getClientProperties().stringPropertyNames() ) {
            if ( !keysToSkip.contains( k ) ) {
                propsToShow.put( k , OpenTSxClusterLink.getClientProperties().getProperty( k ) );
            }
        }

        StringWriter writer = new StringWriter();
        propsToShow.list(new PrintWriter(writer));

        return writer.getBuffer().toString();

    }
}
