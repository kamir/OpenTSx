package org.opentsx.connectors.kafka;

import com.google.gson.Gson;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.TSData;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.VectorWritable;

import java.util.Properties;
import java.util.Vector;

public class TSOProducer {

    private final static String TOPIC = "source-topic";
    private final static String BOOTSTRAP_SERVERS = "localhost:9092";

    public static void pushTSOItemsToKafka( TimeSeriesObject[] items ) throws Exception {

        final Producer<String, String> producer = createProducer();

        long time = System.currentTimeMillis();

        int sendMessageCount = items.length;

        try {

            // the key will be based on current time and than one item per ___ms___.
            for (long index = time; index < time + sendMessageCount; index++) {

                /**
                 *  Create RECORD ...
                 */
                final ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC, index+"",
                                "TSOtem-" + index);

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
                        new ProducerRecord<>(TOPIC, index+"",
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



    /**
     * Creates a Producer with default settings.
     *
     * @return
     */
    private static Producer<String, String> createProducer() {

        Properties props = new Properties();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
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

    public void pushTSOItemsToKafka(Vector<TimeSeriesObject> show) {


        final Producer<String, String> producer = createProducer();

        long time = System.currentTimeMillis();

        int sendMessageCount = show.size();

        try {

            // the key will be based on current time and than one item per ___ms___.
            for (int index = 0; index < sendMessageCount; index++) {

                TimeSeriesObject mr = show.get(index);

                TSData data = TSData.convertMessreihe(mr);



                TSData data2 = TSData.convertMessreihe(mr);

                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                Gson gson = new Gson();
                String dataJSON = gson.toJson(nv);

                /**
                 *  Create RECORD ...
                 */
                final ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC, data.label +"",
                                "TSOItem: " + dataJSON);

                RecordMetadata metadata = producer.send(record).get();

                long elapsedTime = System.currentTimeMillis() - time;

                System.out.printf("sent record(key=%s value=%s) " +
                                "meta(partition=%d, offset=%d) time=%d\n",
                        record.key(), record.value(), metadata.partition(),
                        metadata.offset(), elapsedTime);

            }
        }
        catch (Exception ex) {

        }
        finally {
            producer.flush();
            producer.close();
        }
    }
}
