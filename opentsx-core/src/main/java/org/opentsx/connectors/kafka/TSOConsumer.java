package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opentsx.core.TSBucket;
import org.opentsx.tsa.rng.ReferenceDataset;

import java.util.*;

/**
 *
 * https://www.confluent.io/blog/tutorial-getting-started-with-the-new-apache-kafka-0-9-consumer-client/
 *
 */
public class TSOConsumer {

    Consumer c = null;

    public TSOConsumer(String tn) {
        c = TSOConsumer.createConsumer( tn );
    }

    private final static String TOPIC = ReferenceDataset.topicname;

    private final static String BOOTSTRAP_SERVERS = "localhost:9092";

    private static Consumer<String, String> createConsumer(String tn) {

        final Properties props = new Properties();


        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                BOOTSTRAP_SERVERS);
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
        if ( tn != null )
            consumer.subscribe(Collections.singletonList(tn));
        else
            consumer.subscribe(Collections.singletonList(TOPIC));

        return consumer;
    }

    public void runConsumer(TSBucket tsb, int z, String tn) throws InterruptedException {

        final Consumer<String, String> consumer = createConsumer(tn);

        final int giveUp = z;

        int noRecordsCount = 0;

        while (noRecordsCount < giveUp) {

            final ConsumerRecords<String, String> consumerRecords = consumer.poll(1000);

            Iterator<ConsumerRecord<String, String>> it = consumerRecords.iterator();

            while( it.hasNext() ) {

                noRecordsCount++;

                ConsumerRecord record = it.next();

                tsb.processKVToTSO( (String)record.key() , (String)record.value(), TOPIC );

                System.out.printf( noRecordsCount + " -> " + giveUp + " # Consumer Record:(%s, %s, %s, %s)\n",
                        record.key(), record.value(),
                        record.partition(), record.offset());

            };

            consumer.commitAsync();
        }
        consumer.close();
        System.out.println("DONE");
    }

}
