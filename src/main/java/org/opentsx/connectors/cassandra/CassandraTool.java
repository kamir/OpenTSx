package org.opentsx.connectors.cassandra;

import org.opentsx.core.TSData;

import java.util.List;

public class CassandraTool {

    public static void main(String[] ARGS) {

        CassandraConnector cc = new CassandraConnector();
        cc.connect();
        cc.createKeyspace( CassandraConnector.KS_NAME, "SimpleStrategy", 1 );
        cc.createTable();

        //        cc.close();

        List<TSData> data = cc.selectAll();
        System.out.println( data.size() );

    }

}
