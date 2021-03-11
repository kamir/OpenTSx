import org.apache.kafka.clients.consumer.*;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.function.Function;

public class EchoServiceConsumer {
    private final Properties config;
    private Consumer<String, String> kafkaConsumer;
    private boolean keepConsuming = true;

    public EchoServiceConsumer(Properties config){
       this.config = config;
    }

    public void create(String topic){
        //Adding specific consumer configs
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "EventServiceConsumer_" + System.currentTimeMillis());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        final Consumer<String, String> kafkaConsumer = new KafkaConsumer<String, String>(config);
        kafkaConsumer.subscribe(Collections.singletonList(topic));
    }

    public void consume(Function<ConsumerRecord<String, String>, Boolean> action) {
        Long count = 0L;

        try {
            while (keepConsuming) {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(10));
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("Consumed record => key: [%s] value [%s] from topic [%s], partition [%d] and offset [%d]",
                            record.key(),
                            record.value(),
                            record.topic(),
                            record.partition(),
                            record.offset());
                   //produce the record back
                    action.apply(record);
                }
            }
        } finally {
            kafkaConsumer.close();
        }
    }

    public void stop() {
        keepConsuming = false;
    }
}
