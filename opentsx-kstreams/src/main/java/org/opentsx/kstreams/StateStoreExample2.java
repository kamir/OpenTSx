package org.opentsx.kstreams;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Printed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class StateStoreExample2 {

  private static final Logger LOG = LoggerFactory.getLogger(StateStoreExample2.class);

  public static void main(String[] args) throws Exception {

    /**
     *
     * Read and dump data points ...
     *
     */


    StreamsConfig streamsConfig = new StreamsConfig(getProperties());

    StreamsBuilder builder = new StreamsBuilder();


    KTable<String, String> stockTickerTable = builder.table("refds_events_topic3");

    stockTickerTable.toStream().print(Printed.<String, String>toSysOut().withLabel("Stocks-KTable"));

    KafkaStreams kafkaStreams = new KafkaStreams(builder.build(), streamsConfig);

    LOG.info("KTable processing started");

    kafkaStreams.cleanUp();
    kafkaStreams.start();

    Thread.sleep(15000);

    LOG.info("Shutting down KTable processing Application now");

    kafkaStreams.close();



  }

  public static Properties getProperties() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "StateStoreExample_02");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    return props;

  }




}
