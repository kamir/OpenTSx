package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opentsx.core.TSBucket;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * Reads the String records of one topic into a {@link TSBucket} with one consumer, which {@link #runConsumer} closes.
 *
 * https://www.confluent.io/blog/tutorial-getting-started-with-the-new-apache-kafka-0-9-consumer-client/
 */
public class TSOConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TSOConsumer.class);

    static final int GIVE_UP_AFTER_EMPTY_POLLS = 10;

    private final Consumer<String, String> consumer;

    private final String topic;

    public TSOConsumer(String tn) {
        this(createConsumer(), tn == null ? ReferenceDataset.topicname : tn);
    }

    TSOConsumer(Consumer<String, String> consumer, String topic) {
        this.consumer = consumer;
        this.topic = topic;
    }

    private static Consumer<String, String> createConsumer() {

        final Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "TSOConsumer_" + System.currentTimeMillis() );
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");

        return new KafkaConsumer<>(props);
    }

    /**
     * Reads the records of the topic {@code tn} (the topic of the constructor when null) until at least {@code z} have
     * arrived, keeping every record of the last poll, and closes the consumer. It stops early after 10 empty polls of
     * one second in a row.
     */
    public void runConsumer(TSBucket tsb, int z, String tn) {

        final String name = tn == null ? topic : tn;

        int read = 0;
        int emptyPolls = 0;

        try {
            consumer.subscribe(Collections.singletonList(name));

            while (read < z && emptyPolls < GIVE_UP_AFTER_EMPTY_POLLS) {

                final ConsumerRecords<String, String> consumerRecords = consumer.poll(Duration.ofSeconds(1));

                if (consumerRecords.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;

                for (ConsumerRecord<String, String> record : consumerRecords) {

                    read++;

                    tsb.processKVToTSO( record.key() , record.value(), name );

                    LOG.debug("record read count={} of={} partition={} offset={}",
                            read, z, record.partition(), record.offset());

                }

                consumer.commitAsync();
            }
        }
        finally {
            consumer.close();
        }

        if (read < z) {
            LOG.warn("stopped reading after empty polls topic={} read={} expected={} emptyPolls={}",
                    name, read, z, GIVE_UP_AFTER_EMPTY_POLLS);
        }
    }

}
