import cassandra.CassandraConnector;
import org.opentsx.core.TSBucket;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.util.Vector;

public class TSOReader4Cassandra {

    public static TSBucket loadBucket_Cassandra(String keyspace, String tablename) {

        System.out.println("--> read time series bucket from Cassandra table : {TSBucket table -> " + tablename + "}");

        TSBucket tsb = TSBucket.createEmptyBucket();
        tsb.inMEM = true;

        CassandraConnector cc = new CassandraConnector();
        cc.connect();

        Vector<TimeSeriesObject> rows = TSBucketLoader4Cassandra.loadFromCassandra( cc, keyspace, tablename , null);

        try {
            tsb.createBucketFromVectorOfSeries( keyspace + "." + tablename, "label???", rows );
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        cc.close();

        System.out.println( tsb.getBucketData().size() + " time series in MEM.");
        System.out.println("### DONE ###");

        return tsb;
    }

}