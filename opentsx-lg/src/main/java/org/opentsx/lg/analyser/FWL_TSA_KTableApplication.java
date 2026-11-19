package org.opentsx.lg.analyser;


import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Printed;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class FWL_TSA_KTableApplication {

    private static final Logger LOG = LoggerFactory.getLogger(FWL_TSA_KTableApplication.class);

    public static void main(String[] args) throws Exception {

      OpenTSxClusterLink.init();

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

      Runtime.getRuntime().addShutdownHook(new Thread(kafkaStreams::close));

  }

    public static Properties getProperties() {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "TSAExample1");

        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, GenericAvroSerde.class);

        return props;

    }




}
