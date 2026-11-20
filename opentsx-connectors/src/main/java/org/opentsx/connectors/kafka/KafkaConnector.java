package org.opentsx.connectors.kafka;

import org.opentsx.connectors.kafka.TSOConsumer;
import org.opentsx.core.TSBucket;

public class KafkaConnector {

    String topicName = null;

    public KafkaConnector( String tn ) {
        topicName = tn;
    }

    TSOConsumer consumer = null;

    public void connect() {

        consumer = new TSOConsumer(topicName);

    }

    public void processTopic(TSBucket tsb, int z) {

        consumer.runConsumer( tsb, z, topicName );

    }

    /**
     * Closes the consumer that {@link #connect} opened, once; {@link #processTopic} closes it as well.
     */
    public void close() {
        if (consumer != null) {
            consumer.close();
        }
    }

}
