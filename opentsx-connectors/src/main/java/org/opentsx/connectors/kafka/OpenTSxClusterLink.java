package org.opentsx.connectors.kafka;

import org.opentsx.connectors.kafka.topicmanager.TopicsUP;

import java.io.*;
import java.util.Properties;
import java.util.Vector;

public class OpenTSxClusterLink {

    public static String OPENTSX_TOPIC_MAP_FILE_NAME = "./config/topiclist.def";;
    /** Local client profile (git-ignored); template: config/opentsx-local.props.example. */
    public static final String DEFAULT_CLIENT_CFG_FILE_NAME = "./config/opentsx-local.props";
    public static final String TEMPLATE_SUFFIX = ".example";

    public static String OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = DEFAULT_CLIENT_CFG_FILE_NAME;

    public static Properties getClientProperties() {

        File f = new File( OpenTSxClusterLink.get_PROPS_FN() );

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

        if ( f.canRead() || new File(fn + TEMPLATE_SUFFIX).canRead() )
            PROPS_FN = fn;
        else {
            System.out.println("> Attempt to overwrite PROPS_FN in org.opentsx.connectors.kafka.TSOProducer failed.");
        }
        System.out.println("> PROPS_FN = " + PROPS_FN);
    };

    public static String PROPS_FN = DEFAULT_CLIENT_CFG_FILE_NAME;

    /**
     * The client profile file. Falls back to the checked-in template ({@code <file>.example}) when the
     * profile itself does not exist, so a fresh clone runs against localhost without extra setup.
     */
    public static String get_PROPS_FN() {
        if (!new File(PROPS_FN).canRead() && new File(PROPS_FN + TEMPLATE_SUFFIX).canRead()) {
            return PROPS_FN + TEMPLATE_SUFFIX;
        }
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
