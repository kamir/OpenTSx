import cassandra.CassandraConnector;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.gson.Gson;
import jdk.nashorn.internal.runtime.ListAdapter;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.opentsx.core.TSBucket;
import org.opentsx.core.TSData;
import org.opentsx.core.TSOperation;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TSBucketLoader4Cassandra {

    boolean inMEM = true;

    public static Vector<TimeSeriesObject> loadFromCassandra(CassandraConnector cc, String keyspace, String tn, TSOperation tst) {

        Session session = cc.getSession();

        System.out.println("Select ALL TSOs ...");
        long t1 = System.currentTimeMillis();
        ResultSet rs = session.execute("select id, tsdata from "+ keyspace + "." + tn);
        System.out.println("Got rows (without fetching) = " + rs.getAvailableWithoutFetching());
        int i2 = 0;
        long numBytes2 = 0;
        // example use of the data: count rows and total bytes returned.
        Gson gson = new Gson();

        int i = 0;

        Vector<TimeSeriesObject> bucketData = new Vector<TimeSeriesObject>();

        for (Row rowN : rs)
        {
            i++;
            numBytes2 += rowN.toString().length();
            // System.out.println( rowN.get(0, String.class ) + " -> " + rowN.get(1, String.class ) );
            String s = rowN.get(1, String.class );
            String key = rowN.get(0, String.class );


            // **** SERIALIZATION TO JSON fails !!!

            String vecS = rowN.get(1,String.class);

            TSData data = gson.fromJson( vecS, TSData.class );
            // System.out.println(data);

            String descr = "(" + i + "){" + keyspace + "." + tn + "}\n[" + key.toString() + "]";

            System.out.println( descr + "\n" );

            TimeSeriesObject mr = new TimeSeriesObject();
            mr.setDescription( descr );
            mr.setLabel(key);

            NamedVector vector = new NamedVector(new DenseVector(data.getData()), data.label);

            int c = 0;
            while (c < vector.size()) {

                double value = vector.get(c);
                //System.out.println( c + "\t" + value  );

                mr.addValue(value);

                c++;
            }
            try {

                /*

                TimeSeriesObject m = null;
                if (tst != null) {
                    m = tst.processReihe(fw, mr, fwe);
                }

                 */

            }
            catch (Exception ex) {
                Logger.getLogger(TSBucket.class.getName()).log(Level.SEVERE, null, ex);
            }


                bucketData.add(mr);

        }

        long t2 = System.currentTimeMillis();
        long time = t2-t1;

        System.out.println( "Returned rows = " + i + ", total bytes = " + numBytes2 + ", in time = " + time );

        System.out.println( "--> nr of TSOs     : " + i + " # " + bucketData.size() );

        System.out.println( "TSBucket load time (from Cassandra) =>  " + time + " ms");

        return bucketData;

    }


}
