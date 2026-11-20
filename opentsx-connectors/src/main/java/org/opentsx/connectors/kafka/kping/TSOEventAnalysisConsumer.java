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

    /**
     * @deprecated Names the topic of {@code TSOEventProducer}, which holds String JSON records that this consumer
     * cannot read; pass {@code TSOProducer.TOPIC_for_events} ({@code OpenTSx_Events}) to {@link #createConsumer}.
     */
    @Deprecated
    public final static String TOPIC = ReferenceDataset.event_topicname;

    /**
     * Creates the consumer of the given topic for the events that
     * {@code TSOProducer.pushTSDataAsEventsToKafka_String_Avro} writes to {@code OpenTSx_Events}
     * ({@code TSOProducer.TOPIC_for_events}): the series label as a String key and the Avro {@code Event} as a
     * {@link GenericRecord}, in the fixed group {@code KPING-Consumer}. The consumer continues at the offsets the
     * group committed and starts at the latest offset only while the group has no committed offset or its committed
     * offset no longer exists. It sets the deserializers itself; the other settings, {@code schema.registry.url}
     * included, come from the Kafka client profile.
     */
    public static Consumer<String, GenericRecord> createConsumer( String TOPIC ) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG, "KPING-Consumer");

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());

        final Consumer<String, GenericRecord> consumer =
                new KafkaConsumer<>(props);

        consumer.subscribe(Collections.singletonList(TOPIC));
        return consumer;

    }

}
