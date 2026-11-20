package org.opentsx.connectors.kafka;

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

    /**
     * Creates a Producer with the settings of the client profile.
     *
     * @return
     */
    private static Producer<String, String> createProducer() {

        Properties props = OpenTSxClusterLink.getClientProperties();

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
        pushTSOItemsToKafka(show, TOPIC);
    }

    /** Sends one JSON record per value of each series to {@code topic}. */
    public void pushTSOItemsToKafka(Vector<TimeSeriesObject> show, String topic) {

        final Producer<String, String> producer = createProducer();

        int sendMessageCount = show.size();

        try {

            for (int index = 0; index < sendMessageCount; index++) {

                TimeSeriesObject mr = show.get(index);

                TSData data = TSData.convertMessreihe(mr);

                for (int i = 0; i < mr.xValues.size(); i++ ) {

                    JsonObject jsonObject = new JsonObject();
                    jsonObject.addProperty("ts", mr.xValues.get(i)+"" );
                    jsonObject.addProperty("value", mr.yValues.get(i)+"" );

                    String dataJSON = jsonObject.toString();

                    final ProducerRecord<String, String> record =
                            new ProducerRecord<>(topic, data.label + "_" + mr.xValues.get(i),
                                    dataJSON);

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
