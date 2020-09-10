package org.opentsx.tsa;

import com.google.gson.Gson;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.commons.math3.transform.TransformType;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Printed;
import org.apache.kafka.streams.kstream.Produced;
import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;

public class TSAExample11 {

  private static long   experiment_duration = 30000; // ms (-1 no limit)
  private static String EXPERIMENT_TAG = ReferenceDataset.EXPERIMENT__TAG;
  static MessageDigest md = null;

  private static final Logger LOG = LoggerFactory.getLogger(TSAExample11.class);

  public static Properties getFlowSpecificProperties() {

    Properties props = TSxStreamingAppHelper.getKafkaClientProperties();

    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "TSAExample_11_" + System.currentTimeMillis() );

    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    //props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    //props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);

    return props;

  }

  public static void main(String[] args) throws Exception {

    Properties props = getFlowSpecificProperties();

    StreamsConfig streamsConfig = new StreamsConfig( props );

    StreamsBuilder builder = new StreamsBuilder();

    Serde<String> stringSerde1 = Serdes.String();
    Serde<String> stringSerde2 = Serdes.String();
    Serde<String> stringSerde3 = Serdes.String();


    final Map<String, String> serdeConfig = Collections.singletonMap("schema.registry.url",
            props.getProperty( "schema.registry.url" ));

    final Serde<EpisodesRecord> valueSpecificAvroSerde = new SpecificAvroSerde<>();
    valueSpecificAvroSerde.configure(serdeConfig, false); // `false` for record values

    KStream<String, EpisodesRecord> my_episodes = builder.stream( "OpenTSx_Episodes_B", Consumed.with( stringSerde1, valueSpecificAvroSerde));

    KStream<String, String>my_episodes_fft = my_episodes.mapValues( (k,v) -> getFFT_top_5( k,v ) );

    my_episodes_fft.to("EPISODES_DATA_B_FFT_" + EXPERIMENT_TAG,  Produced.with( stringSerde2, stringSerde3) );

    my_episodes_fft.print(Printed.<String, String>toSysOut().withLabel("[EPISODES_DATA]"));


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
    kafkaStreams.start();

    if ( experiment_duration > 0) {
      Thread.sleep(experiment_duration);
    }

    kafkaStreams.close();

    LOG.info("Shutting down the event processing example application now.");

  }

  static Gson gson = new Gson();

  private static String getFFT_top_5(String k, EpisodesRecord v) {

    TimeSeriesObject tso = TimeSeriesObject.getFromEpisode( v );

    tso = TimeSeriesObjectFFT.calcFFT( tso, 1, TransformType.FORWARD );

    Hashtable topF = tso.getTopFrequencies( 1 );

    topF.put( "label", tso.getLabel() );
    topF.put( "max", tso.getMaxY() );
    topF.put( "min", tso.getMinY() );

    return gson.toJson( topF );

  }






}


