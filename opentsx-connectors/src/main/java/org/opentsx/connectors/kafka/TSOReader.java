package org.opentsx.connectors.kafka;


import org.opentsx.connectors.kafka.topicmanager.TSBucket4Kafka;
import org.opentsx.core.TSBucket;

public class TSOReader {


    public TSBucket loadBucket_Kafka(String topicname, int z) {

        System.out.println("--> read time series bucket from Kafka topic : {TSBucket topic -> " + topicname + "}");

        TSBucket4Kafka tsb4k = TSBucket4Kafka.createEmptyBucket();

        tsb4k.inMEM = true;

        KafkaConnector kc = new KafkaConnector( topicname );
        kc.connect();

        tsb4k.loadFromKafka( kc, topicname , z);

        kc.close();

        System.out.println( tsb4k.getBucketData().size() + " time series in MEM.");
        System.out.println("### DONE ###");

        return tsb4k;

    }
}