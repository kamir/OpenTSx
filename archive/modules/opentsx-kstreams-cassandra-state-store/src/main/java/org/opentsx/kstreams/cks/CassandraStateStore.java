package org.opentsx.kstreams.cks;

import com.datastax.driver.core.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsx.kstreams.serdes.SerdesFactory;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.state.StateSerdes;

import org.opentsx.kstreams.cks.internals.CassandraChangeLogger;
import org.opentsx.kstreams.cks.internals.CassandraWritableStore;
import org.opentsx.kstreams.cks.internals.StateDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CassandraStateStore implements StateStore, CassandraWritableStore<String, StateDocument> {

  // the 1 node trial Cassandra test cluster Public IPs.
  static String hostAddr = "127.0.0.1";
  static String dcName = ""; // this is the DC name you used when created
  static String user = "";
  static String password = "";

  public static final String TABLE = "state_store";
  public static final String KEYSPACE = "ks1";

  public static String STORE_NAME = "CassandraStateStore";

  private CassandraChangeLogger<String, StateDocument> changeLogger = null;

  private Session session = null;

  private ProcessorContext context;
  private long updateTimestamp;
  private StateDocument value;
  private String key;
  private Serde<StateDocument> docSerdes;

  private final ObjectMapper mapper = new ObjectMapper();

  public CassandraStateStore(String hostAddr) {

    this.hostAddr = hostAddr;

    Cluster.Builder clusterBuilder = Cluster.builder()
            .addContactPoints(
                    hostAddr
            )
            // .withLoadBalancingPolicy(DCAwareRoundRobinPolicy.builder().withLocalDc(dcName).build()) // your local data centre
            // .withAuthProvider(new PlainTextAuthProvider(user, password))
            .withPort(9042);

    Cluster cluster = null;

    try {
      cluster = clusterBuilder.build();

      Metadata metadata = cluster.getMetadata();
      System.out.printf("Connected to cluster: %s\n", metadata.getClusterName());

      for (Host host : metadata.getAllHosts()) {
        System.out.printf("Datacenter: %s; Host: %s; Rack: %s\n", host.getDatacenter(), host.getAddress(), host.getRack());
      }

      session = cluster.connect();

      System.out.println("> StateStore connected ..." );

      createStateTables();

      System.out.println("> StateStore tables created ... {" + KEYSPACE + "." +TABLE+"}" );

    }
    catch (Exception ex) {
      ex.printStackTrace();
    }

  }

  /**
   * Read a row from Cassandra table ...
   */
  @Override
  public StateDocument read(String key) {

    if (key == null) {
      return new StateDocument();
    }

    return search("select * from " + KEYSPACE + "." + TABLE + " where key = " + key , null).get(0);

  }



  /**
   * Read rows from Cassandra table using a CQL query ...
   */
  public List<StateDocument> search(String query, String[] fields) {

    List<StateDocument> results = new ArrayList<>();
    ResultSet rs;

    System.out.println(" >>> search() : " );

    try {
      rs = session.execute(query);
      for (Row rowN : rs) {
        System.out.println(" : " + rowN.toString());
        StateDocument doc = mapper.readValue(rowN.getString("value"), StateDocument.class);
        results.add(doc);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }

    return results;
  }




  /**
   * Write rows to Cassandra table ...
   */
  @Override
  public void write(String key, StateDocument value) {
    this.key = key;
    this.value = value;

    ResultSet rs;
    String jsonContent;
    try {
      jsonContent = mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      jsonContent = "";
    }

    try {

      rs = session.execute("insert into " + KEYSPACE + "." + TABLE + " (key, value) values (" + "'" + key + "'" + ", '" + jsonContent + "');" );

    }
    catch (Exception e) {
      e.printStackTrace();
    }

    this.updateTimestamp = System.currentTimeMillis();

  }





  @Override
  public String name() {
    return STORE_NAME;
  }

  /**
   * Initialize the StateStore and the processor context ...
   */


  @Override
  public void init(ProcessorContext processorContext, StateStore stateStore) {

    context = processorContext;

    docSerdes = SerdesFactory.from(StateDocument.class);

    StateSerdes<String, StateDocument> serdes = new StateSerdes(
        name(),
        Serdes.String(),
        docSerdes);

    changeLogger = new CassandraChangeLogger<>(name(), context, serdes);

    context.register(this, (key, value) -> {
      // here the store restore should happen from the changelog topic.
      String sKey = new String(key);
      StateDocument docValue = docSerdes.deserializer().deserialize(sKey, value);
      write(sKey, docValue);
    });

  }

  private void createStateTables() {

    ResultSet rs;
    boolean createTable = true;
    if (createTable) {
      rs = session.execute("CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor' : 1}");
      rs = session.execute("DROP TABLE IF EXISTS " + KEYSPACE + "." + TABLE );
      rs = session.execute("CREATE TABLE " + KEYSPACE + "." + TABLE + "(key text, value text, PRIMARY KEY (key) )");
      System.out.println("Table " + KEYSPACE + "." + TABLE + " created!");
    }

  }


  @Override
  public void flush() {
    try {
      // http://abiasforaction.net/apache-cassandra-memtable-flush/

    }
    catch (Exception e) {
      e.printStackTrace();
    }

    System.out.println( "> NO FLUSH in Cassandra StateStore implementation. ");
    changeLogger.logChange(key, value, updateTimestamp);

  }



  public void close() {
    try {
      // client.close();
    }
    catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public boolean persistent() {
    return true;
  }

  @Override
  public boolean isOpen() {
    try {
      // return client.ping(RequestOptions.DEFAULT);
      return true;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

}
