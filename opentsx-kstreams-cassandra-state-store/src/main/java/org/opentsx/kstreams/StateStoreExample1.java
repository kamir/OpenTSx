package org.opentsx.kstreams;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.opentsx.kstreams.cks.CassandraStoreBuilder;
import org.opentsx.kstreams.processors.CountingProcessor;

import java.util.Properties;

public class StateStoreExample1 {

  public static void main(String[] args) throws Exception {

    /**
     *
     *   Count data points and persist the counter in a state store ...
     *
     */

    // Here using the processor API


      Topology topology = new Topology();

      CassandraStoreBuilder storeSupplier = new CassandraStoreBuilder();

      topology.addSource("Source", "refds_events_topic")
          .addProcessor("Process", () -> new CountingProcessor(), "Source")
          .addStateStore(storeSupplier, "Process")
          .addSink("Sink", "target-topic-3", "Process");

      Properties props = configure();

      KafkaStreams streams = new KafkaStreams(topology, props);

      streams.start();

    Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

  }

  public static Properties configure() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "StateStoreExample_01");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    return props;

  }




}
