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

    public void close() {
    }

}
