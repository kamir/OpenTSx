package cassandra;


import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.google.gson.Gson;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.TSData;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.VectorWritable;
import org.opentsx.tsa.rng.ReferenceDataset;

import java.util.ArrayList;
import java.util.List;

public class CassandraConnector {

    private Cluster cluster;

    private Session session;

    public void connect(String node, Integer port) {
        Cluster.Builder b = Cluster.builder().addContactPoint(node);
        if (port != null) {
            b.withPort(port);
        }
        cluster = b.build();

        session = cluster.connect();
    }

    public Session getSession() {
        return this.session;
    }

    public void close() {

        try {
            session.close();

            cluster.close();
        }
        catch(Exception ex) {
            // ex.printStackTrace();
        }
    }


    public void createKeyspace(
            String keyspaceName, String replicationStrategy, int replicationFactor) {
        StringBuilder sb =
                new StringBuilder("CREATE KEYSPACE IF NOT EXISTS ")
                        .append(keyspaceName).append(" WITH replication = {")
                        .append("'class':'").append(replicationStrategy)
                        .append("','replication_factor':").append(replicationFactor)
                        .append("};");

        String query = sb.toString();
        session.execute(query);
    }

    public void connect() {

        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").withPort(9042).build();

        this.session = cluster.connect(KS_NAME);

    }


    public static final String TABLE_NAME = ReferenceDataset.CASSANDRA_TN;
    public static final String KS_NAME = ReferenceDataset.CASSANDRA_KS;


    public void createTable() {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append( TABLE_NAME ).append(" (")
                .append("id text PRIMARY KEY, ")
                .append("tsdata text);");

        String query = sb.toString();
        session.execute(query);
    }

    public void insertTSOByLabel(TimeSeriesObject mr) {

        TSData data = TSData.convertMessreihe(mr);

        NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
        VectorWritable vec = new VectorWritable();
        vec.set(nv);

        Gson gson = new Gson();
        String dataJSON = gson.toJson( data );

        StringBuilder sb = new StringBuilder("INSERT INTO ")
                .append(TABLE_NAME).append("(id, tsdata) ")
                .append("VALUES (").append( "'" + data.label + "'" )
                .append(", '").append( dataJSON ).append("');");

        String query = sb.toString();

        session.execute(query);

    }



    public List<TSData> selectAll() {
        StringBuilder sb =
                new StringBuilder("SELECT * FROM ").append(TABLE_NAME);

        String query = sb.toString();
        ResultSet rs = session.execute(query);

        List<TSData> books = new ArrayList<TSData>();

        rs.forEach(r -> {
            books.add(new TSData(
                    r.getString("id").toString(),
                    r.getString("tsdata")));
        });
        return books;
    }

}