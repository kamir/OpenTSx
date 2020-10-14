package org.opentsx.connectors.kafka;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.core.TSData;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsa.rng.ReferenceDataset;

import java.util.Properties;
import java.util.Vector;

public class TSOEventProducer {

    public final static String TOPIC = ReferenceDataset.event_topicname;
    private final static String BOOTSTRAP_SERVERS = "localhost:9092";

    /**
     * Creates a Producer with custom settings.
     * The settings are provided in a Properties object.
     *
     * In case some cutsom settings are missing,
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
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOEventItemProducer");

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
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "TSOEventItemProducer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        return new KafkaProducer<>(props);

    }

    /**
     * This is our standard way for pushing TSOOEvent to a Kafka cluster.
     *
     * @param show
     */
    public void pushTSOItemsToKafka(Vector<TimeSeriesObject> show) {

        Gson gson = new Gson();

        final Producer<String, String> producer = createProducer();

        long time = System.currentTimeMillis();

        int sendMessageCount = show.size();

        try {

            // the key will be based on current time and than one item per ___ms___.
            for (int index = 0; index < sendMessageCount; index++) {

                TimeSeriesObject mr = show.get(index);

                TSData data = TSData.convertMessreihe(mr);

                RecordMetadata metadata = null;

                int i = 0;

                for ( i=0; i < mr.xValues.size(); i++ ) {

                    JsonObject jsonObject = new JsonObject();
                    jsonObject.addProperty("ts", mr.xValues.get(i)+"" );
                    jsonObject.addProperty("value", mr.yValues.get(i)+"" );

                    String dataJSON = jsonObject.toString();

                    /**
                     *  Create RECORD ...
                     */
                    final ProducerRecord<String, String> record =
                            new ProducerRecord<>(TOPIC, data.label + "_" + mr.xValues.get(i),
                                    dataJSON);

                    metadata = producer.send(record).get();

                }

                long elapsedTime = System.currentTimeMillis() - time;

           /*     System.out.printf("sent record(z=%s ) " +
                                "meta(partition=%d, offset=%d) time=%d\n",
                        i, metadata.partition(),
                        metadata.offset(), elapsedTime);*/

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
}
