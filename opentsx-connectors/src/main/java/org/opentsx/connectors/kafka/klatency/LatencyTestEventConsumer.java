package org.opentsx.connectors.kafka.klatency;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.util.Collections;
import java.util.Properties;

public class LatencyTestEventConsumer {

    public final static String TOPIC_for_response_events_string = "latency_benchmark_response";

    /**
     * Creates the consumer of the String records of {@code TOPIC}, in the fixed group {@code LatencyTestEventConsumer}.
     * The consumer continues at the offsets the group committed and starts at the latest offset only while the group
     * has no committed offset or its committed offset no longer exists. It sets both deserializers to
     * {@code StringDeserializer} itself; the other settings come from the Kafka client profile.
     */
    public static Consumer<String, String> createConsumer( String TOPIC ) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG, "LatencyTestEventConsumer");

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        final Consumer<String, String> consumer =
                new KafkaConsumer<>(props);

        consumer.subscribe(Collections.singletonList(TOPIC));
        return consumer;

    }

}
