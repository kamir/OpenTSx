package org.opentsx.kstreams;

import static org.apache.kafka.streams.Topology.AutoOffsetReset.EARLIEST;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class StateStoreExample3 {

  private static final Logger LOG = LoggerFactory.getLogger(StateStoreExample3.class);

  public static void main(String[] args) throws Exception {

    /**
     *
     * Count data points per series ...
     *
     */

    StreamsConfig streamsConfig = new StreamsConfig(getProperties());

    StreamsBuilder builder = new StreamsBuilder();

    Serde<String> stringSerde1 = Serdes.String();
    Serde<String> stringSerde2 = Serdes.String();

    Serde<String> stringSerde3 = Serdes.String();
    Serde<String> stringSerde4 = Serdes.String();

    long twoMinutes = 1000 * 120;
    long fiveMinutes = 1000 * 60 * 5;

    KTable<Windowed<String>, Long> customerTransactionCounts =
            builder.stream("refds_events_topic3", Consumed.with(stringSerde1, stringSerde2).withOffsetResetPolicy(EARLIEST))
                    .groupBy((key, transaction) -> getTSOKey( key ),
                            Serialized.with(stringSerde3, stringSerde4))
                    // session window comment line below and uncomment another line below for a different window example
                    .windowedBy(SessionWindows.with(twoMinutes).until(fiveMinutes)).count();

    //The following are examples of different windows examples

    //Tumbling window with timeout 15 minutes
    //.windowedBy(TimeWindows.of(twentySeconds).until(fifteenMinutes)).count();

    //Tumbling window with default timeout 24 hours
    //.windowedBy(TimeWindows.of(twentySeconds)).count();

    //Hopping window
    //.windowedBy(TimeWindows.of(twentySeconds).advanceBy(fiveSeconds).until(fifteenMinutes)).count();

    customerTransactionCounts.toStream().print(Printed.<Windowed<String>, Long>toSysOut().withLabel("Customer Transactions Counts"));

    KafkaStreams kafkaStreams = new KafkaStreams(builder.build(), streamsConfig);
    kafkaStreams.cleanUp();

    kafkaStreams.setUncaughtExceptionHandler((t, e) -> {
      LOG.error("had exception ", e);
    });

    LOG.info("Starting CountingWindowing and KTableJoins Example");
    kafkaStreams.cleanUp();
    kafkaStreams.start();
    Thread.sleep(65000);
    LOG.info("Shutting down the CountingWindowing and KTableJoins Example Application now");
    kafkaStreams.close();

    
  }

  private static String getTSOKey(String key) {

    int end = key.lastIndexOf("_");
    String groupKey = key.substring(0,end);
    return groupKey;

  }

  public static Properties getProperties() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "StateStoreExample_03");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    return props;

  }




}
