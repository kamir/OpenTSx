package org.opentsx.connectors.kafka;

import com.google.gson.Gson;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class EventFlowStateProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EventFlowStateProducer.class);

    public final static String TOPIC = "OpenTSx_Event_Flow_State";

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

    final Producer<String, String> producer;

    public EventFlowStateProducer() {
        this(createProducer());
    }

    public EventFlowStateProducer(Producer<String, String> producer) {
        this.producer = producer;
    }

    public void pushFlowState(Properties stateProps, String flowSource) {

        LOG.debug("sending the flow state source={} properties={}", flowSource, stateProps.size());

        OpenTSxClusterLink.send(producer, new ProducerRecord<>(TOPIC, flowSource, gson.toJson(stateProps)));

    }

    public void close() {
        producer.flush();
        producer.close();
    }

}
