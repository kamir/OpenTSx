package org.opentsx.connectors.kafka;


import org.opentsx.connectors.kafka.topicmanager.TSBucket4Kafka;
import org.opentsx.core.TSBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TSOReader {

    private static final Logger LOG = LoggerFactory.getLogger(TSOReader.class);

    public TSBucket loadBucket_Kafka(String topicname, int z) {

        TSBucket4Kafka tsb4k = TSBucket4Kafka.createEmptyBucket();

        tsb4k.inMEM = true;

        KafkaConnector kc = new KafkaConnector( topicname );
        kc.connect();

        tsb4k.loadFromKafka( kc, topicname , z);

        kc.close();

        LOG.debug("bucket read topic={} series={}", topicname, tsb4k.getBucketData().size());

        return tsb4k;

    }
}