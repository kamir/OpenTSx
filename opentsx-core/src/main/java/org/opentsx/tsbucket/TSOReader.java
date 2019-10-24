package org.opentsx.tsbucket;

import org.opentsx.connectors.cassandra.CassandraConnector;
import org.opentsx.core.TSBucket;

public class TSOReader {

    public static TSBucket loadBucket_Cassandra(String keyspace, String tablename) {

        System.out.println("--> read time series bucket from Cassandra table : {TSBucket table -> " + tablename + "}");

        TSBucket tsb = TSBucket.createEmptyBucket();
        tsb.inMEM = true;

        CassandraConnector cc = new CassandraConnector();
        cc.connect();

        tsb.loadFromCassandra( cc, keyspace, tablename , null);

        cc.close();

        System.out.println( tsb.getBucketData().size() + " time series in MEM.");
        System.out.println("### DONE ###");

        return tsb;
    }

    public TSBucket loadBucket_Kafka(String topicname, int z) {

        System.out.println("--> read time series bucket from Kafka topic : {TSBucket topic -> " + topicname + "}");

        TSBucket tsb = TSBucket.createEmptyBucket();
        tsb.inMEM = true;

        KafkaConnector kc = new KafkaConnector( topicname );
        kc.connect();

        tsb.loadFromKafka( kc, topicname , z);

        kc.close();

        System.out.println( tsb.getBucketData().size() + " time series in MEM.");
        System.out.println("### DONE ###");

        return tsb;

    }
}