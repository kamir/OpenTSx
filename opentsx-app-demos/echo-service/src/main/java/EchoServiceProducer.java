import model.LatencyTesterEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Properties;
import java.util.function.Function;

public class EchoServiceProducer  implements Function<ConsumerRecord<String,String>, Boolean> {
    private final Properties config;
    private final String topic;
    private Producer<String, String> producer;
    public EchoServiceProducer(Properties config, String topic){
       this.config = config;
       this.topic = topic;
    }

    @Override
    public Boolean apply(ConsumerRecord<String, String> latencyTesterEvent) {
        producer = new KafkaProducer<String, String>(config);

        String key = latencyTesterEvent.key();
        LatencyTesterEvent value = LatencyTesterEvent.fromJson(latencyTesterEvent.value());
        value.trackReceivedTS();
        value.trackResultShippedTS();

        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value.asJson());

            producer.send(record,
                    (metadata, exception) -> {
                        if(exception != null) {
                            exception.printStackTrace();
                        } else {
                            System.out.printf("Record produced to topic [%s] partition [%d] and offset [%d]%n",
                                    metadata.topic(),
                                    metadata.partition(),
                                    metadata.offset());
                        }
                    });
        }finally {
            producer.flush();
        }
        return true;
    }

   public void close() {
        producer.flush();
        producer.close();
   }
}
