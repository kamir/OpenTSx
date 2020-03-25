package demo1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.ListenableFuture;

@SpringBootApplication
public class KafkaDemoApp implements ApplicationRunner {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void sendMessage(String msg) {
        kafkaTemplate.send("MYM", msg);
    }

    public static void main(String[] args) {

        System.out.println("Go...");

        SpringApplication.run(KafkaDemoApp.class, args);

    }

    @KafkaListener(topics = "MYM", groupId = "group-2")
    public void listen(String message) {

        System.out.println(">>> Received Messasge in group - group-id: " + message);

    }

    @Override
    public void run(ApplicationArguments args) throws Exception {

        sendMessage("* Hi! Welcome to Spring For Apache Kafka");

        System.out.println("> MESSAGE SENT ...");

    }
}
