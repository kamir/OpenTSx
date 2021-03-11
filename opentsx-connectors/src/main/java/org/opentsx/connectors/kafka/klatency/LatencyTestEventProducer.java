package org.opentsx.connectors.kafka.klatency;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.data.model.LatencyTesterEvent;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import java.util.Properties;


public class LatencyTestEventProducer {

    public final static String TOPIC_for_request_events_string = "latency_benchmark_request";

    /**
     * Creates a Producer with default settings.
     *
     * @return
     */
    public static Producer<String, String> createProducer(Properties props) {

        props.put(ProducerConfig.CLIENT_ID_CONFIG, "LatencyTestEventProducer");

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        return new KafkaProducer<>(props);

    }

    /**
     * We take a time series and create a set of events which are shipped in sequence.
     *
     * @param data
     */
    Producer<String, String> producer = null;
    public void pushTestEventToCluster( LatencyTesterEvent data ) {

        if ( producer == null ) {
            Properties props = new Properties();

            try {
                props.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN())));
                props.list(System.out);
            } catch (IOException e) {
                e.printStackTrace();
            }
            producer = createProducer(props);
        };

        long time0 = System.currentTimeMillis();

        try {

            String key = data.targetAgentDC;

            data.trackSendTS();

            String dataJSON = LatencyTesterEvent.asJson( data );

            final ProducerRecord<String, String> record =
               new ProducerRecord<>(TOPIC_for_request_events_string, key, dataJSON );
                    RecordMetadata metadata = producer.send(record).get();


        }
        catch (Exception ex) {
            ex.printStackTrace();

        }
        finally {
            producer.flush();

        }

    }

    public void close() {
        producer.flush();
        producer.close();
    }

}
