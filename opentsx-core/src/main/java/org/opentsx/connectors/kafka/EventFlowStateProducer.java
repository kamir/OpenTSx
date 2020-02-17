package org.opentsx.connectors.kafka;

import com.google.gson.Gson;
import org.apache.commons.collections.KeyValue;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.core.TSData;

import java.util.Properties;
import java.util.Vector;

public class EventFlowStateProducer {

    public final static String TOPIC = "opentsx_event_flow_state";
    private final static String BOOTSTRAP_SERVERS = "localhost:9092";


    /**
     * Creates a Producer with custom settings, and in case some cutsom settings are missing,
     * we use the default settings.
     *
     * Minimal required settings are:
     *
     *         props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
     *         props.put(ProducerConfig.CLIENT_ID_CONFIG, "TXItemProducer");
     *         props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
     *         props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
     *
     * @return
     */
    private static Producer<String, String> createProducer(Properties props) {

        if( props.get( ProducerConfig.BOOTSTRAP_SERVERS_CONFIG ) == null )
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        if( props.get( ProducerConfig.CLIENT_ID_CONFIG ) == null )
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "EventFlowStateProducer");

        if( props.get( ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG ) == null )
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        if( props.get( ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG ) == null )
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        return new KafkaProducer<>(props);

    }



    /**
     * Creates a Producer with default settings.
     *
     * @return
     */
    public static Producer<String, String> createProducer() {

        Properties props = new Properties();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "EventFlowStateProducer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        return new KafkaProducer<>(props);

    }

    static Gson gson = new Gson();

    final Producer<String, String> producer = createProducer();

    public void pushFlowState(Properties stateProps, String flowSource) {

        System.out.println( ">>> flow state props : " + stateProps.size() );

        long time = System.currentTimeMillis();

        try {

                String dataJSON = gson.toJson(stateProps);

                /**
                 *  Create RECORD ...
                 */
                final ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC, flowSource, dataJSON);

                RecordMetadata metadata = producer.send(record).get();

                long elapsedTime = System.currentTimeMillis() - time;

                /*System.out.printf("sent record(key=%s value=%s) " +
                                "meta(partition=%d, offset=%d) time=%d\n",
                        record.key(), record.value(), metadata.partition(),
                        metadata.offset(), elapsedTime);*/



        }
        catch (Exception ex) {
            ex.printStackTrace();

        }
        finally {
            producer.flush();
        }

    }

    public void close() {
        producer.flush();
        producer.close();
    }

}
