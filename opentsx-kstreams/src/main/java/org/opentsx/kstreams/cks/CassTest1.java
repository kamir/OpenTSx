package org.opentsx.kstreams.cks;

import java.util.Date;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.google.gson.Gson;

/**
 * Simple Java client test to connect to a local 1 node cluster,
 * create a time series data table,
 * fill it,
 * query it,
 * and save it as csv for graphing.
 **/

public class CassTest1 {

    static String keyspace = "ks1";

    // the 1 node trial Cassandra test cluster Public IPs.
    static String n1PubIP = "127.0.0.1";
    static String dcName = ""; // this is the DC name you used when created
    static String user = "";
    static String password = "";

    public static void main(String[] args) {

        long t1 = 0; // time each CQL operation, t1 is start time t2 is end time, time is t2-t1
        long t2 = 0;
        long time = 0;

        Cluster.Builder clusterBuilder = Cluster.builder()
                .addContactPoints(
                        n1PubIP
                )
                // .withLoadBalancingPolicy(DCAwareRoundRobinPolicy.builder().withLocalDc(dcName).build()) // your local data centre
                // .withAuthProvider(new PlainTextAuthProvider(user, password))
                .withPort(9042);

        Cluster cluster = null;
        try {
            cluster = clusterBuilder.build();

            Metadata metadata = cluster.getMetadata();
            System.out.printf("Connected to cluster: %s\n", metadata.getClusterName());

            for (Host host: metadata.getAllHosts()) {
                System.out.printf("Datacenter: %s; Host: %s; Rack: %s\n", host.getDatacenter(), host.getAddress(), host.getRack());
            }

            Session session = cluster.connect();

            ResultSet rs;
            boolean createTable = true;
            if (createTable) {
                rs = session.execute("CREATE KEYSPACE IF NOT EXISTS " + keyspace + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor' : 1}");
                rs = session.execute("DROP TABLE IF EXISTS " + keyspace + ".sensordata");
                rs = session.execute("CREATE TABLE " + keyspace + ".sensordata(host text, metric text, time timestamp, value double, PRIMARY KEY ((host, metric), time) ) WITH CLUSTERING ORDER BY (time ASC)");
                System.out.println("Table " + keyspace + ".sensordata created!");
            }

            // Fill the table with some realistic sensor data. if createTable=false we just ADD data to the table

            double startValue = 100;	// start value for random walk
            double nextValue = startValue; // next value in random walk, initially startValue
            int numHosts = 100;	// how many host names to generate
            int toCreate = 1000;	// how many times to pick a host name and create all metrics for it

            boolean usePrepared = false;
            PreparedStatement prepared = null;
            // prepare a prepared statement
            if (usePrepared)
            {
                System.out.println("Using PREPARED statements for INSERT");
                prepared = session.prepare("insert into hals.sensordata (host, metric, time, value) values (?, ?, ?, ?)");
            }

            t1 = System.currentTimeMillis();
            System.out.println("Creating data... iterations = " + toCreate);
            for (int r=1; r <= toCreate; r++) {
                long now = System.currentTimeMillis();
                Date date = new Date(now);
                // generate a random host name
                String hostname = "host" + (long)Math.round((Math.random() * numHosts));
                // do a random walk to produce realistic data
                double rand = Math.random();
                if (rand < 0.5)
                // 50% chance that value doesn't change
                ;
		    		else if (rand < 0.75)
                // 25% chance that value increases by 1
                nextValue++;
		    		else
                // 25% chance that value decreases by 1
                nextValue--;
                // never go negative
                if (nextValue < 0)
                nextValue = 0;

                // comparison of prepared vs. non-prepared statements
                if (usePrepared)  {
                    session.execute(prepared.bind("'" + hostname + "'", "'m1'", date, nextValue));
                    session.execute(prepared.bind("'" + hostname + "'", "'m2'", date, nextValue * 10));
                    session.execute(prepared.bind("'" + hostname + "'", "'m3'", date, nextValue * 100));
                }
                else {
                    // fake three metrics (m1, m2, m3) which are somehow related.
                    rs = session.execute("insert into "+keyspace + ".sensordata (host, metric, time, value) values (" + "'" + hostname + "'" + ", " + "'m1'" + ", " + now + "," + (nextValue) + ");" );
                    rs = session.execute("insert into "+keyspace + ".sensordata (host, metric, time, value) values (" + "'" + hostname + "'" + ", " + "'m2'" + ", " + now + "," + (nextValue * 10) + ");" );
                    rs = session.execute("insert into "+keyspace + ".sensordata (host, metric, time, value) values (" + "'" + hostname + "'" + ", " + "'m3'" + ", " + now + "," + (nextValue * 100) + ");" );
                }
            }

            t2 = System.currentTimeMillis();
            System.out.println("Created rows = " + toCreate*3 + " in time = " + (t2-t1));

            // find the max value for a sample
            System.out.println("Getting max value for sample...");
            t1 = System.currentTimeMillis();
            rs = session.execute("select max(value) from "+keyspace + ".sensordata where host='host1' and metric='m1'");
            t2 = System.currentTimeMillis();
            time = t2-t1;

            Row row = rs.one();
            System.out.println("Max value = " + row.toString() + " in time = " + time);

            // get all the values for a sample
            System.out.println("Getting all rows for sample...");
            t1 = System.currentTimeMillis();
            rs = session.execute("select * from "+keyspace + ".sensordata where host='host1' and metric='m1'");
            for (Row rowN : rs) {
                System.out.println(rowN.toString());
            }
            t2 = System.currentTimeMillis();
            time = t2-t1;
            System.out.println("time = " + time);

            // get all host/metric permutations
            System.out.println("Getting all host/metric permutations");
            t1 = System.currentTimeMillis();
            rs = session.execute("select distinct host, metric from "+keyspace + ".sensordata");
            for (Row rowN : rs) {
                System.out.println(rowN.toString());
            }
            t2 = System.currentTimeMillis();
            time = t2-t1;
            System.out.println("time = " + time);

            // Note that SELECT * will return all results without limit (even though the driver might use multiple queries in the background).
            // To handle large result sets, you use a LIMIT clause in your CQL query, or use one of the techniques described in the paging documentation.
            System.out.println("Select ALL...");
            t1 = System.currentTimeMillis();
            rs = session.execute("select * from "+keyspace + ".sensordata");
            System.out.println("Got rows (without fetching) = " + rs.getAvailableWithoutFetching());
            int i = 0;
            long numBytes = 0;
            // example use of the data: count rows and total bytes returned.
            for (Row rowN : rs)
            {
                i++;
                numBytes += rowN.toString().length();
            }
            t2 = System.currentTimeMillis();
            time = t2-t1;
            System.out.println("Returned rows = " + i + ", total bytes = " + numBytes + ", in time = " + time);



        } finally {
            if (cluster != null) cluster.close();
        }
    }
}
