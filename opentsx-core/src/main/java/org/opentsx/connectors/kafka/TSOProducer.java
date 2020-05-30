package org.opentsx.connectors.kafka;

import com.google.gson.Gson;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.model.Event;
import org.opentsx.core.TSData;
import org.apache.kafka.clients.producer.*;

import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.data.model.Observation;


import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

public class TSOProducer {

    public static void init_PROPS_FN(String fn){
        File f = new File(fn);
        System.out.println( "> PROPS_FN Path: " + f.getAbsolutePath() + " -> (r:" + f.canRead()+ ")" );

        if ( f.canRead() )
            PROPS_FN = fn;
        else {
            System.out.println("> Attempt to overwrite PROPS_FN in org.opentsx.connectors.kafka.TSOProducer failed.");
        }
        System.out.println("> PROPS_FN=" + PROPS_FN);
    };

    private static String PROPS_FN = "config/cpl.props";
    public static String get_PROPS_FN() {
        return  PROPS_FN;
    };






    public final static String TOPIC_for_events_string = "TSOData_Events_string";

    public final static String TOPIC_for_events = "OpenTSx_Events";
    public final static String TOPIC_for_episodes = "OpenTSx_Episodes";


    // private final static String BOOTSTRAP_SERVERS = "localhost:9092";
    // private final static String BOOTSTRAP_SERVERS = "pkc-43n10.us-central1.gcp.confluent.cloud:9092";

    public static void runProducer( int sendMessageCount ) throws Exception {

        final Producer<String, String> producer = createProducer();

        long time = System.currentTimeMillis();

        try {

            // the key will be based on current time and than one item per ___ms___.
            for (long index = time; index < time + sendMessageCount; index++) {

                /**
                 *  Create RECORD ...
                 */
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
     *
    private static Producer<String, String> createProducer(Properties props) {

        if( props.get( ProducerConfig.BOOTSTRAP_SERVERS_CONFIG ) == null )
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        if( props.get( ProducerConfig.CLIENT_ID_CONFIG ) == null )
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");

        if( props.get( ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG ) == null )
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        if( props.get( ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG ) == null )
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        return new KafkaProducer<>(props);

    }
    */


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

        props.list(System.out);

        return new KafkaProducer<>(props);

    }




    /**
     * Creates a Producer with default settings.
     *
     * @return
     */
    public static Producer<String, String> createProducer() {

        Properties props = new Properties();

        try {
            props.load(new FileReader( new File( "/Users/mkampf/GITHUB.public/OpenTSx/config.props" ) ));
        }
        catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        //props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
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

        Properties props = new Properties();

        try {
            props.load(new FileReader( new File( PROPS_FN ) ));
        }
        catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        // props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");


        final Producer<String, EpisodesRecord> producer = createProducer_Avro_Episodes( props );

        long time = System.currentTimeMillis();

        // NR OF TIME SERIES
        int sendMessageCount = data.size();

        // Gson gson = new Gson();

        try {

            EpisodesRecord er = new EpisodesRecord();

            // the key will be based on current time and than one item per ___ms___.
            for ( TSData mrd : data ) {

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

//                double[] yData = new double[mrd.getData().length];
//                List<Double> yData = new ArrayList<Double>();
                //               for( double v : mrd.getData() ) {
//                    yData[i] = v;
//                    yData.add(v);
//                    i++;
//                }

                long t1 = t;

                // er.setURI( URI );

                CharSequence cs = "123";
                er.setLabel( cs );
                er.setTStart( t0 );
                er.setTEnd( t1 );
                er.setIncrement( dt );
                er.setZObservations( z );
                er.setUri("URI");

                er.setObservationArray( oarray );

                String tn = TOPIC_for_episodes;
                if ( topic_suffix != null )
                    tn = tn.concat( "_" + topic_suffix );
                /**
                 *  Create RECORD ...
                 */
                final ProducerRecord<String, EpisodesRecord> record =
                        new ProducerRecord<>(tn, mrd.label,
                                er);

                RecordMetadata metadata = producer.send(record).get();

                long elapsedTime = System.currentTimeMillis() - time;

                /*System.out.printf("sent record(key=%s value=%s) " +
                                "meta(partition=%d, offset=%d) time=%d\n",
                        record.key(), record.value(), metadata.partition(),
                        metadata.offset(), elapsedTime);*/

                producer.flush();

            }
        }
        catch (Exception ex) {
            ex.printStackTrace();

        }
        finally {
            producer.flush();
            producer.close();
        }

    }

    Gson gson = new Gson();

    public void pushTSDataAsEventsToKafka_String_Avro(Vector<TSData> data, String URI) {

        Properties props = new Properties();

        try {
            props.load(new FileReader( new File( PROPS_FN ) ));
            props.list(System.out);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        //props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");

        final Producer<String, Event> producer = createProducer_Avro( props );

        long time = System.currentTimeMillis();

        try {

            // the key will be based on current time and than one item per ___ms___.
            for ( TSData mrd : data ) {

                Event e = new Event();

                String dataJSON = gson.toJson(mrd);

                double[] values = mrd.getData();

                String key = mrd.label;

                long t0 = System.currentTimeMillis();
                long t = t0;

                long i = 0;
                for( double v : values ) {

                    t = t + ( i * 200 );

                    i++;

                    String theKey = key.concat( mrd.label );

                    e.setTimestamp( t );
                    e.setUri(URI);
                    e.setValue( v );

                    /**
                     *  Create RECORD ...
                     *
                     *  TODO: Use the AVRO FORMAT from OpenTSX-DATA
                     *
                     */
                    final ProducerRecord<String, Event> record =
                            new ProducerRecord<>(TOPIC_for_events, key, e );

                    RecordMetadata metadata = producer.send(record).get();

                    long elapsedTime = System.currentTimeMillis() - time;

                }

            }
        }
        catch (Exception ex) {
            ex.printStackTrace();

        }
        finally {
            producer.flush();
            producer.close();
        }

        producer.flush();

    }

    /*
    public void pushTSDataAsEventsToKafka_String_String(Vector<TSData> data) {

        Properties props = new Properties();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOItemProducer");

        final Producer<String, String> producer = createProducer( props );

        long time = System.currentTimeMillis();

        try {

            // the key will be based on current time and than one item per ___ms___.
            for ( TSData mrd : data ) {

                String dataJSON = gson.toJson(mrd);

                double[] values = mrd.getData();

                String key = mrd.label;

                int i = 0;
                for( double v : values ) {
                    i++;

                    String theKey = key.concat( i+"" );

                    String value = ""+v;

                    final ProducerRecord<String, String> record =
                            new ProducerRecord<>(TOPIC_for_events_string, key, value);

                    RecordMetadata metadata = producer.send(record).get();

                    long elapsedTime = System.currentTimeMillis() - time;

                }

            }
        }
        catch (Exception ex) {
            ex.printStackTrace();

        }
        finally {
            producer.flush();
            producer.close();
        }

        producer.flush();

    }
    */

/*
    public void pushTSOItemsToKafka(Vector<TimeSeriesObject> show) {


        final Producer<String, String> producer = createProducer();

        long time = System.currentTimeMillis();

        int sendMessageCount = show.size();

        Gson gson = new Gson();

        try {

            // the key will be based on current time and than one item per ___ms___.
            for (int index = 0; index < sendMessageCount; index++) {

                TimeSeriesObject mr = show.get(index);

                // TODO: Separate TSData and TSDataWritable ...

                TSData data = TSData.convertMessreihe(mr);

                String dataJSON = gson.toJson(data);


                final ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC_for_episodes, data.label +"",
                                dataJSON);

                RecordMetadata metadata = producer.send(record).get();

                long elapsedTime = System.currentTimeMillis() - time;

//                System.out.printf("sent record(key=%s value=%s) " +
//                                "meta(partition=%d, offset=%d) time=%d\n",
//                        record.key(), record.value(), metadata.partition(),
//                        metadata.offset(), elapsedTime);

            }
        }
        catch (Exception ex) {

        }
        finally {
            producer.flush();
            producer.close();
        }
    }
    */


}
