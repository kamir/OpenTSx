package connectors.flume;

import connectors.opentsdb.OpenTSDBConnector;
import connectors.opentsdb.OpenTSDBEvent;
import org.apache.flume.*;
import org.apache.flume.sink.AbstractSink;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Map;
import java.util.Properties;


public class OpenTSDBSink extends AbstractSink implements Configurable {

    private static final Logger logger = LoggerFactory.getLogger(OpenTSDBSink.class);
    private Properties connectorProps;
    private OpenTSDBConnector connector;

    private MessagePreprocessor messagePreProcessor;

    private Context context;

    @Override
    public Status process() throws EventDeliveryException {
        Status result = Status.READY;
        Channel channel = getChannel();
        Transaction transaction = channel.getTransaction();

        Event event = null;

        String eventKey = null;

        try {
            transaction.begin();
            event = channel.take();

            if (event != null) {
                // get the message body.
                String eventBody = new String(event.getBody());

                // if the metadata extractor is set, extract the topic and the key.
                if (messagePreProcessor != null) {
                    eventBody = messagePreProcessor.transformMessage(event, context);
                }

                // log the event for debugging
                if (logger.isDebugEnabled()) {
                    logger.debug("{Event} " + eventBody);
                }

                OpenTSDBEvent ev = new OpenTSDBEvent( eventBody );

                // publish
                connector.sendEventViaSocket( ev );

            }
            else {
                // No event found, request back-off semantics from the sink runner
                result = Status.BACKOFF;
            }
            // publishing is successful. Commit.
            transaction.commit();

        } catch (Exception ex) {
            transaction.rollback();
            String errorMsg = "Failed to publish event: " + event;
            logger.error(errorMsg);
            throw new EventDeliveryException(errorMsg, ex);

        } finally {
            transaction.close();
        }

        return result;
    }

    @Override
    public synchronized void start() {

        // Connector instantiation ...
        try {

            connector = new OpenTSDBConnector(connectorProps);

            // OPEN SOCKET FOR STREAMING TO OPENTSDB ....
            connector.openSocket();

            super.start();

        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

    }

    @Override
    public synchronized void stop() {
        try {
            connector.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        super.stop();
    }


    public void configure(Context context) {
        this.context = context;
        // read the properties for Kafka Producer
        // any property that has the prefix "kafka" in the key will be considered as a property that is passed when
        // instantiating the producer.
        // For example, kafka.metadata.broker.list = localhost:9092 is a property that is processed here, but not
        // sinks.k1.type = com.thilinamb.flume.sink.KafkaSink.
        Map<String, String> params = context.getParameters();
        connectorProps = new Properties();
        for (String key : params.keySet()) {
            String value = params.get(key).trim();
            key = key.trim();
            if (key.startsWith(Constants.PROPERTY_PREFIX)) {
                // remove the prefix
                key = key.substring(Constants.PROPERTY_PREFIX.length() + 1, key.length());
                connectorProps.put(key.trim(), value);
                if (logger.isDebugEnabled()) {
                    logger.debug("Reading an OpenTSDB Connector Property: key: " + key + ", value: " + value);
                }
            }
        }

        // get the message Preprocessor if set
        String preprocessorClassName = context.getString(Constants.PREPROCESSOR);
        // if it's set create an instance using Java Reflection.
        if (preprocessorClassName != null) {
            try {
                Class preprocessorClazz = Class.forName(preprocessorClassName.trim());
                Object preprocessorObj = preprocessorClazz.newInstance();
                if (preprocessorObj instanceof MessagePreprocessor) {
                    messagePreProcessor = (MessagePreprocessor) preprocessorObj;
                } else {
                    String errorMsg = "Provided class for MessagePreprocessor does not implement " +
                            "' ..... .MessagePreprocessor'";
                    logger.error(errorMsg);
                    throw new IllegalArgumentException(errorMsg);
                }
            } catch (ClassNotFoundException e) {
                String errorMsg = "Error instantiating the MessagePreprocessor implementation.";
                logger.error(errorMsg, e);
                throw new IllegalArgumentException(errorMsg, e);
            } catch (InstantiationException e) {
                String errorMsg = "Error instantiating the MessagePreprocessor implementation.";
                logger.error(errorMsg, e);
                throw new IllegalArgumentException(errorMsg, e);
            } catch (IllegalAccessException e) {
                String errorMsg = "Error instantiating the MessagePreprocessor implementation.";
                logger.error(errorMsg, e);
                throw new IllegalArgumentException(errorMsg, e);
            }
        }

        if (messagePreProcessor == null) {

            // Nothing todo ...

        }
    }

    @Override
    public void setConf(Configuration configuration) {

    }

    @Override
    public Configuration getConf() {
        return null;
    }
}