package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opentsx.core.TSBucket;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 *
 * https://www.confluent.io/blog/tutorial-getting-started-with-the-new-apache-kafka-0-9-consumer-client/
 *
 */
public class TSOConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TSOConsumer.class);

    Consumer c = null;

    public TSOConsumer(String tn) {
        c = TSOConsumer.createConsumer( tn );
    }

    private static String TOPIC = ReferenceDataset.topicname;

    /*

           We create a simple consumer which can consume String messages.

     */
    private static Consumer<String, String> createConsumer(String TOPIC_NAME) {

        final Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "TSOConsumer_" + System.currentTimeMillis() );
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");

        // Create the consumer using props.
        final Consumer<String, String> consumer = new KafkaConsumer<>(props);

        // Subscribe to the topic.
        if ( TOPIC_NAME != null )
            TOPIC = TOPIC_NAME;

        consumer.subscribe(Collections.singletonList(TOPIC));

        return consumer;
    }

    public void runConsumer(TSBucket tsb, int z, String tn) {

        final Consumer<String, String> consumer = createConsumer(tn);

        final int giveUp = z;

        int noRecordsCount = 0;

        try {
            while (noRecordsCount < giveUp) {

                final ConsumerRecords<String, String> consumerRecords = consumer.poll(1000);

                Iterator<ConsumerRecord<String, String>> it = consumerRecords.iterator();

                while( it.hasNext() ) {

                    noRecordsCount++;

                    ConsumerRecord record = it.next();

                    tsb.processKVToTSO( (String)record.key() , (String)record.value(), TOPIC );

                    LOG.debug("record read count={} of={} partition={} offset={}",
                            noRecordsCount, giveUp, record.partition(), record.offset());

                };

                consumer.commitAsync();
            }
        }
        finally {
            consumer.close();
        }
    }

}
