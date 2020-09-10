package org.opentsx.connectors.kafka;

import com.google.gson.Gson;
import org.apache.commons.collections.KeyValue;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.core.TSData;
import org.opentsx.util.OpenTSxClusterLink;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
import java.util.Vector;

public class EventFlowStateProducer {

    public final static String TOPIC = "OpenTSx_Event_Flow_State";

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

        try {
            props.load(new FileReader( new File( OpenTSxClusterLink.get_PROPS_FN() ) ));
        }
        catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }


        if( props.get( ProducerConfig.CLIENT_ID_CONFIG ) == null )
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "EventFlowStateProducer");

        if( props.get( ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG ) == null )
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());


        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        return new KafkaProducer<>(props);

    }



    /**
     * Creates a Producer with default settings.
     *
     * @return
     */
    public static Producer<String, String> createProducer() {

        Properties props = OpenTSxClusterLink.getClientProperties();

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
