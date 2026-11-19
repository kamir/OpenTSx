package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.opentsx.connectors.kafka.topicmanager.TopicsUP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

public class OpenTSxClusterLink {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTSxClusterLink.class);

    public static String OPENTSX_TOPIC_MAP_FILE_NAME = "./config/topiclist.def";;
    /** Local client profile (git-ignored); template: config/opentsx-local.props.example. */
    public static final String DEFAULT_CLIENT_CFG_FILE_NAME = "./config/opentsx-local.props";
    public static final String TEMPLATE_SUFFIX = ".example";

    public static String OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = DEFAULT_CLIENT_CFG_FILE_NAME;

    public static Properties getClientProperties() {

        File f = new File( OpenTSxClusterLink.get_PROPS_FN() );

        LOG.debug("reading the client profile path={}", f.getAbsolutePath());

        Properties properties = new Properties();
        try (FileReader reader = new FileReader(f)) {
            properties.load(reader);
        }
        catch (IOException e) {
            throw new UncheckedIOException(f.getAbsolutePath() + ": cannot read the Kafka client profile ("
                    + e.getMessage() + "); set OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME to a readable file", e);
        }

        return properties;

    };

    private static void init_PROPS_FN(String fn){

        File f = new File(fn);

        if ( !f.canRead() && !new File(fn + TEMPLATE_SUFFIX).canRead() ) {
            throw new IllegalArgumentException("OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME: \"" + fn
                    + "\" cannot be read (" + f.getAbsolutePath() + "); give a readable Kafka client profile or its "
                    + TEMPLATE_SUFFIX + " template");
        }
        PROPS_FN = fn;
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

        OPENTSX_TOPIC_MAP_FILE_NAME = System.getenv("OPENTSX_TOPIC_MAP_FILE_NAME");
        if( OPENTSX_TOPIC_MAP_FILE_NAME != null ) {
            TopicsUP.init_TOPICS_DEF_FN( OPENTSX_TOPIC_MAP_FILE_NAME );
        }
        LOG.debug("topic list path={}", TopicsUP.get_TOPICS_DEF_FN());

        OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME = System.getenv("OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME");
        if( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME != null ) {
            init_PROPS_FN( OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME );
        }
        LOG.debug("client profile path={}", get_PROPS_FN());

    }

    public static <K, V> RecordMetadata send(Producer<K, V> producer, ProducerRecord<K, V> record) {
        try {
            return producer.send(record).get();
        }
        catch (ExecutionException e) {
            throw new IllegalStateException(record.topic() + ": could not send the record; " + e.getCause().getMessage(),
                    e.getCause());
        }
        catch (KafkaException e) {
            throw new IllegalStateException(record.topic() + ": could not send the record; " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(record.topic() + ": sending the record was interrupted; the record may not be stored", e);
        }
    }

    public static String getPropsAsString_NO_SECURITY_PROPS() {
        Vector<String> keysToSkip = new Vector<>();
        keysToSkip.add("security.protocol");
        keysToSkip.add("key.serializer");
        keysToSkip.add("value.serializer");
        keysToSkip.add("key.deserializer");
        keysToSkip.add("value.deserializer");
        keysToSkip.add("schema.registry.url");
        keysToSkip.add("basic.auth.credentials.source");

        Properties properties = OpenTSxClusterLink.getClientProperties();
        Properties propsToShow = new Properties();
        for( String k : properties.stringPropertyNames() ) {
            if ( !keysToSkip.contains( k ) && !isSecurityKey( k ) ) {
                propsToShow.put( k , properties.getProperty( k ) );
            }
        }

        StringWriter writer = new StringWriter();
        propsToShow.list(new PrintWriter(writer));

        return writer.getBuffer().toString();

    }

    private static boolean isSecurityKey(String k) {
        return k.contains("sasl.") || k.contains("ssl.") || k.contains("password") || k.contains("secret")
                || k.contains("token") || k.contains("user.info");
    }
}
