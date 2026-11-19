package org.opentsx.connectors.kafka.topicmanager;

import org.opentsx.connectors.kafka.KafkaConnector;
import org.opentsx.core.TSBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TSBucket4Kafka extends TSBucket {

    private static final Logger LOG = LoggerFactory.getLogger(TSBucket4Kafka.class);

    public static TSBucket4Kafka createEmptyBucket() {
        TSBucket4Kafka bu = new TSBucket4Kafka();
        bu.LIMIT = TSBucket.default_LIMIT;
        return bu;
    }

    public void loadFromKafka(KafkaConnector kc, String topicname, int z) {

        long t1 = System.currentTimeMillis();

        kc.processTopic( this, z );

        LOG.debug("bucket loaded topic={} series={} ms={}", topicname, bucketData.size(), System.currentTimeMillis() - t1);

    }

}
