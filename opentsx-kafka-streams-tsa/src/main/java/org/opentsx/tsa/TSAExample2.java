package org.opentsx.tsa;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Printed;
import org.opentsx.data.model.Event;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

public class TSAExample2 {

  private static long   experiment_duration = 30000; // ms (-1 no limit)
  private static String EXPERIMENT_TAG = ReferenceDataset.EXPERIMENT__TAG;
  static MessageDigest md = null;

  private static final Logger LOG = LoggerFactory.getLogger(TSAExample2.class);

  public static Properties getProperties() {

    Properties props = new Properties();

    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "TSAExample_02_" + System.currentTimeMillis() );

    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "PC192-168-3-5:9092");

    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    //props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    //props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
    props.put("schema.registry.url", "http://PC192-168-3-5:8081");

    return props;

  }

  public static void main(String[] args) throws Exception {

    Properties props = getProperties();

    StreamsConfig streamsConfig = new StreamsConfig( props );

    StreamsBuilder builder = new StreamsBuilder();

    Serde<String> stringSerde1 = Serdes.String();

    final Map<String, String> serdeConfig = Collections.singletonMap("schema.registry.url",
            props.getProperty( "schema.registry.url" ));

    final Serde<Event> valueSpecificAvroSerde = new SpecificAvroSerde<>();
    valueSpecificAvroSerde.configure(serdeConfig, false); // `false` for record values

    KStream<String, Event> my_events = builder.stream("OpenTSx_Events", Consumed.with( stringSerde1, valueSpecificAvroSerde));

    my_events = my_events.filter( (k,v) -> getCriticalEvents( k,v ) );

    my_events.to("EVENT_DATA_ALERTS_" + EXPERIMENT_TAG);

    my_events.print(Printed.<String, Event>toSysOut().withLabel("[EVENT_DATA]"));

    Topology topology = builder.build();

    FileWriter fw = new FileWriter( props.getProperty(StreamsConfig.APPLICATION_ID_CONFIG) + "_topology.dat");
    fw.write( topology.describe().toString() );
    fw.flush();
    fw.close();

    KafkaStreams kafkaStreams = new KafkaStreams(topology, streamsConfig);
    kafkaStreams.cleanUp();

    kafkaStreams.setUncaughtExceptionHandler((t, e) -> {
      LOG.error("had exception ", e);
    });

    LOG.info("Starting event processing example { experiment_duration=" + experiment_duration + " ms}.");
    kafkaStreams.cleanUp();
    kafkaStreams.start();

    if ( experiment_duration > 0 ) {
      Thread.sleep(experiment_duration);
    }

    kafkaStreams.close();

    LOG.info("Shutting down the event processing example application now.");

  }

  /**
   * Simple threshold filter
   *
   * @param k - message key (is ignored in this example)
   * @param e - message value (our event)
   *
   * @return boolean value for filtering
   */
  private static boolean getCriticalEvents(String k, Event e) {

    if ( e.getValue() > 3.0 ) return true;
    else return false;

  }

}


