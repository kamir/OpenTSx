package org.opentsx.connectors.kafka.klatency;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.util.Collections;
import java.util.Properties;

public class LatencyTestEventConsumer {

    public final static String TOPIC_for_response_events_string = "latency_benchmark_response";

    /**
     * Creates the consumer of the String records of {@code TOPIC}, in the group {@code LatencyTestEventConsumer} from
     * the latest offset. The deserializers and the other settings come from the Kafka client profile.
     */
    public static Consumer<String, String> createConsumer( String TOPIC ) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG, "LatencyTestEventConsumer");

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        // Create the consumer using props.
        final Consumer<String, String> consumer =
                new KafkaConsumer<>(props);

        // Subscribe to the topic.
        consumer.subscribe(Collections.singletonList(TOPIC));
        return consumer;

    }

}
