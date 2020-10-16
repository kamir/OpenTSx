package org.opentsx.connectors.kafka.topicmanager;

import org.opentsx.connectors.kafka.KafkaConnector;
import org.opentsx.core.TSBucket;

public class TSBucket4Kafka extends TSBucket {

    public static TSBucket4Kafka createEmptyBucket() {
        TSBucket4Kafka bu = new TSBucket4Kafka();
        bu.LIMIT = TSBucket.default_LIMIT;
        return bu;
    }

    public void loadFromKafka(KafkaConnector kc, String topicname, int z) {

        System.out.println("Read ALL TSOs ...");

        long t1 = System.currentTimeMillis();


        // example use of the data: count rows and total bytes returned.

        int i = 0;
        int numBytes2 = 0;

        // Consume the data ...
        kc.processTopic( this, z );

        long t2 = System.currentTimeMillis();
        long time = t2-t1;

        System.out.println( "Returned rows = " + i + ", total bytes = " + numBytes2 + ", in time = " + time );

        System.out.println( "--> nr of TSOs     : " + i + " # " + bucketData.size() );

        System.out.println( "TSBucket load time (from Kafka) =>  " + time + " ms");


    }

}
