package org.opentsx.tsa;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.Serdes.StringSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;

import org.opentsx.data.model.Event;

import org.opentsx.tsa.rng.ReferenceDataset;
import org.opentsx.kafkautil.OpenTSxClusterLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;

public class TSAExample1 {

  private static long   experiment_duration = 30000; // ms (-1 no limit)
  private static String EXPERIMENT_TAG = ReferenceDataset.EXPERIMENT__TAG;

  static MessageDigest md = null;

  static Properties props = null;

  private static final Logger LOG = LoggerFactory.getLogger(TSAExample1.class);

  static Map<String, String> serdeConfig = null;

  /**
   *
   * We have to overwrite some properties in the app, but basic cluster client settings come from
   * a file which name is configured in environment variable OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME.
   */
  public static Properties getFlowSpecificProperties() {

    Properties props = TSxStreamingAppHelper.getKafkaClientProperties();

    // Comma-separated list of the the Confluent Cloud broker endpoints. For example:
    // r0.great-app.confluent.aws.prod.cloud:9092,r1.great-app.confluent.aws.prod.cloud:9093,r2.great-app.confluent.aws.prod.cloud:9094

    props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
    props.put(StreamsConfig.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
    props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");

    // Recommended performance/resilience settings
    props.put(StreamsConfig.producerPrefix(ProducerConfig.RETRIES_CONFIG), 2147483647);
    props.put("producer.confluent.batch.expiry.ms", "9223372036854775807");
    props.put(StreamsConfig.producerPrefix(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG), 300000);
    //props.put(StreamsConfig.producerPrefix(ProducerConfig.MAX_BLOCK_MS_CONFIG), "9223372036854775807");

    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "TSAExample_01_" + System.currentTimeMillis() );

    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName() );
    //props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    //props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class.getName() );

    serdeConfig = new HashMap<String, String>();

    System.out.println( "================================================" );

    Enumeration en = props.propertyNames();
    while( en.hasMoreElements() ) {
      String key = (String)en.nextElement();
      System.out.println( key + " = {" + props.getProperty(key) + "}" );
      serdeConfig.put( key, props.getProperty(key)  );
    }

    System.out.println( "================================================" );

    return props;

  }


  public static void main(String[] args) throws Exception {

    OpenTSxClusterLink.init();

    props = getFlowSpecificProperties();

    md = MessageDigest.getInstance("MD5");

    StreamsConfig streamsConfig = new StreamsConfig( props );
    StreamsBuilder builder = new StreamsBuilder();

    Serde<String> stringSerde1 = Serdes.String();
    Serde<String> stringSerde2 = Serdes.String();

    final Serde<Event> valueSpecificAvroSerde = new SpecificAvroSerde<>();
    valueSpecificAvroSerde.configure(serdeConfig, false); // `false` for record values

    KStream<String, Event> my_events = builder.stream("OpenTSx_Events", Consumed.with( stringSerde1, valueSpecificAvroSerde));

    // this is a simple masking transformation
    KStream<String, Event> my_events2 = my_events.mapValues( (v) -> getCompactedEvent(v) );

    my_events.to("EVENT_DATA_TRAFO_" + EXPERIMENT_TAG, Produced.with( stringSerde2, valueSpecificAvroSerde) ) ;

    my_events.print(Printed.<String, Event>toSysOut().withLabel("[EVENT_DATA]"));

    Topology topology = builder.build();
    dumpTopology(topology);

    KafkaStreams kafkaStreams = new KafkaStreams(topology, streamsConfig);
    kafkaStreams.setUncaughtExceptionHandler((t, e) -> { LOG.error("had exception ", e); });

    LOG.info("Starting event processing example { experiment_duration=" + experiment_duration + " ms}.");
    kafkaStreams.cleanUp();
    kafkaStreams.start();

    if ( experiment_duration > 0 ) { Thread.sleep(experiment_duration); }

    LOG.info("Shutting down the event processing example application now.");
    kafkaStreams.close();

  }

  private static void dumpTopology(Topology topology) throws IOException {
    FileWriter fw = new FileWriter( props.getProperty(StreamsConfig.APPLICATION_ID_CONFIG) + "_topology.dat");
    fw.write( topology.describe().toString() );
    fw.flush();
    fw.close();
  }


  // the URI is replaced by a HASH
  private static Event getCompactedEvent(Event e) {

    String plaintext = e.getUri().toString();

    md.reset();
    md.update(plaintext.getBytes());

    byte[] digest = md.digest();
    BigInteger bigInt = new BigInteger(1,digest);
    String hashtext = bigInt.toString(16);

    // Now we need to zero pad it if you actually want the full 32 chars.
    while(hashtext.length() < 32 ){
      hashtext = "0"+hashtext;
    }

    e.setUri( hashtext );

    return e;

  }

}


