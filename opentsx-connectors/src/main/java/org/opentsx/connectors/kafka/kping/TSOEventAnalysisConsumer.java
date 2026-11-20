package org.opentsx.connectors.kafka.kping;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.util.Collections;
import java.util.Properties;

public class TSOEventAnalysisConsumer {

    public final static String TOPIC = ReferenceDataset.event_topicname;

    /**
     * Creates the consumer of the given topic for the events that
     * {@code TSOProducer.pushTSDataAsEventsToKafka_String_Avro} writes to {@code OpenTSx_Events}
     * ({@code TSOProducer.TOPIC_for_events}): the series label as a String key and the Avro {@code Event} as a
     * {@link GenericRecord}, in the group {@code KPING-Consumer} from the latest offset. It sets the deserializers
     * itself; the other settings, {@code schema.registry.url} included, come from the Kafka client profile.
     */
    public static Consumer<String, GenericRecord> createConsumer( String TOPIC ) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG, "KPING-Consumer");

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());

        // Create the consumer using props.
        final Consumer<String, GenericRecord> consumer =
                new KafkaConsumer<>(props);

        // Subscribe to the topic.
        consumer.subscribe(Collections.singletonList(TOPIC));
        return consumer;

    }

}
