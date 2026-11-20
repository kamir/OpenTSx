package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.model.Event;
import org.opentsx.core.TSData;
import org.apache.kafka.clients.producer.*;

import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.data.model.Observation;


import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

public class TSOProducer {








    public final static String TOPIC_for_events_string = "TSOData_Events_string";

    public final static String TOPIC_for_events = "OpenTSx_Events";
    public final static String TOPIC_for_episodes = "OpenTSx_Episodes";


    public static void runProducer( int sendMessageCount ) throws Exception {

        final Producer<String, String> producer = createProducer();

        long time = System.currentTimeMillis();

        try {

            for (long index = time; index < time + sendMessageCount; index++) {

                final ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC_for_events_string, index+"",
                                "TSOItem-" + index);

                RecordMetadata metadata = producer.send(record).get();

                long elapsedTime = System.currentTimeMillis() - time;

                System.out.printf("sent record(key=%s value=%s) " +
                                "meta(partition=%d, offset=%d) time=%d\n",
                        record.key(), record.value(), metadata.partition(),
                        metadata.offset(), elapsedTime);

            }
        } finally {
            producer.flush();
            producer.close();
        }
    }


    /**
     * Creates a Producer with custom settings, and in case some cutsom settings are missing,
     * we use the default settings.
     *
     * Minimal required settings are:
     *
     *         props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
     *         props.put(ProducerConfig.CLIENT_ID_CONFIG, "TXItemProducer");
     *         props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
     *         props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
     *
     * @return
     */
    private static Producer<String, Event> createProducer_Avro(Properties props) {


        if( props.get( ProducerConfig.CLIENT_ID_CONFIG ) == null )
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOEventProducer_AVRO");


        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroSerializer");

        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaProducer<>(props);

    }

    private static Producer<String, EpisodesRecord> createProducer_Avro_Episodes(Properties props) {

        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOEpisodeProducer_AVRO");


        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroSerializer");


        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaProducer<>(props);

    }




    /**
     * Creates a Producer with default settings.
     *
     * @return
     */
    public static Producer<String, String> createProducer() {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());






        return new KafkaProducer<>(props);

    }


    public static void main(String... args) throws Exception {
        if (args.length == 0) {
            runProducer(5);
        } else {
            runProducer(Integer.parseInt(args[0]));
        }
    }

    public void pushTSDataAsEpisodesToKafka_String_Avro(Vector<TSData> data, String URI, String topic_suffix ) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");


        final Producer<String, EpisodesRecord> producer = createProducer_Avro_Episodes( props );

        try {

            for ( TSData mrd : data ) {

                EpisodesRecord er = new EpisodesRecord();

                // this MD is needed and not available in TSData object ...
                int dt = 200;
                long t0 = System.currentTimeMillis();
                long t = t0;

                long z = mrd.getData().length;

                // Create an observation array
                int i = 0;


                List<Observation> oarray = new ArrayList<Observation>();

                for( double v : mrd.getData() ) {
                    t = t0 + ( i * dt );
                    Observation obs = new Observation();
                    obs.setValue( v );
                    obs.setUri( URI );
                    obs.setTimestamp( t );
                    oarray.add(obs);
                    i++;
                }


                long t1 = t;



                er.setLabel( mrd.label );
                er.setTStart( t0 );
                er.setTEnd( t1 );
                er.setIncrement( dt );
                er.setZObservations( z );
                er.setUri( URI );

                er.setObservationArray( oarray );

                String tn = TOPIC_for_episodes;
                if ( topic_suffix != null )
                    tn = tn.concat( "_" + topic_suffix );
                final ProducerRecord<String, EpisodesRecord> record =
                        new ProducerRecord<>(tn, mrd.label,
                                er);

                OpenTSxClusterLink.send(producer, record);

            }
        }
        finally {
            producer.flush();
            producer.close();
        }

    }

    /**
     * We take a time series and create a set of events which are shipped in sequence.
     *
     * @param data
     * @param URI
     */
    public void pushTSDataAsEventsToKafka_String_Avro(Vector<TSData> data, String URI) {

        Properties props = OpenTSxClusterLink.getClientProperties();

        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");

        final Producer<String, Event> producer = createProducer_Avro( props );

        try {

            for ( TSData mrd : data ) {

                Event e = new Event();

                double[] values = mrd.getData();

                String key = mrd.label;

                long t0 = System.currentTimeMillis();

                long i = 0;
                for( double v : values ) {

                    long tP = System.currentTimeMillis();

                    long t = t0 + ( i * 200 );

                    i++;

                    e.setTimestamp( t );
                    e.setUri(URI);
                    e.setValue( v );
                    e.setProducerTimestamp( tP );
                    // required by the schema (no default); set by the consumer when measuring latency
                    e.setConsumerTimestamp( -1L );

                    final ProducerRecord<String, Event> record =
                            new ProducerRecord<>(TOPIC_for_events, key, e );

                    OpenTSxClusterLink.send(producer, record);

                }

            }
        }
        finally {
            producer.flush();
            producer.close();
        }

    }


}
