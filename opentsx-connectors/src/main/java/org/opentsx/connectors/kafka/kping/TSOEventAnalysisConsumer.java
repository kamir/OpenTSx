package org.opentsx.connectors.kafka.kping;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.util.Collections;
import java.util.Properties;

public class TSOEventAnalysisConsumer {

    public final static String TOPIC = ReferenceDataset.event_topicname;

    /**
     * We create the Analysis Consumer which can read the TSOEventSeries.
     *
     * @param TOPIC
     * @return
     */
    public static Consumer<Long, GenericRecord> createConsumer( String TOPIC ) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG, "KPING-Consumer");

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        // Create the consumer using props.
        final Consumer<Long, GenericRecord> consumer =
                new KafkaConsumer<>(props);

        // Subscribe to the topic.
        consumer.subscribe(Collections.singletonList(TOPIC));
        return consumer;

    }

}
