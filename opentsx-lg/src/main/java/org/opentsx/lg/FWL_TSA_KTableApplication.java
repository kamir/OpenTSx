package org.opentsx.lg;


import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Printed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class FWL_TSA_KTableApplication {

    private static final Logger LOG = LoggerFactory.getLogger(FWL_TSA_KTableApplication.class);

    public static void main(String[] args) throws Exception {

      StreamsConfig streamsConfig = new StreamsConfig(getProperties());

      StreamsBuilder builder = new StreamsBuilder();

      KTable<String, GenericRecord> episodesTable = builder.table("OpenTSx_Episodes");

      episodesTable.toStream().print(Printed.<String, GenericRecord>toSysOut().withLabel("Episodes-KTable"));


      KafkaStreams kafkaStreams = new KafkaStreams(builder.build(), streamsConfig);

      LOG.info("KTable processing started");

      kafkaStreams.cleanUp();
      kafkaStreams.start();

      Thread.sleep(15000);

      LOG.info("Shutting down KTable processing Application now");

      // kafkaStreams.close();

      Runtime.getRuntime().addShutdownHook(new Thread(kafkaStreams::close));

  }

    public static Properties getProperties() {

        Properties props = new Properties();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "TSAExample1");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.3.104:9092");

//        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
//        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, GenericAvroSerde.class);

        props.put("schema.registry.url", "http://192.168.3.104:8081");

        return props;

    }




}
