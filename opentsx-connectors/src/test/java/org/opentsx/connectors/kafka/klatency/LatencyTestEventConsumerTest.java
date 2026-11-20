package org.opentsx.connectors.kafka.klatency;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.data.model.LatencyTesterEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyTestEventConsumerTest {

    private static final String TOPIC = LatencyTestEventConsumer.TOPIC_for_response_events_string;

    private static final String PROFILE = String.join("\n",
            "bootstrap.servers=127.0.0.1:1",
            "schema.registry.url=http://127.0.0.1:1",
            "interceptor.classes=" + ConfigCapture.class.getName(),
            "");

    @TempDir
    Path dir;

    private String previousProfile;

    @BeforeEach
    void rememberProfile() {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        ConfigCapture.configs = null;
    }

    @AfterEach
    void restoreProfile() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
    }

    private void useProfile(String text) throws Exception {
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, text);
        OpenTSxClusterLink.PROPS_FN = profile.toString();
    }

    @Test
    void theConsumerReadsTheJsonResponsesAsStringsWhenTheProfileSetsTheAvroDeserializer() throws Exception {
        useProfile(PROFILE
                + "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer\n"
                + "value.deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer\n");

        assertSubscribesToTheResponseTopic();

        assertEquals(StringDeserializer.class.getName(),
                String.valueOf(ConfigCapture.configs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)));
        assertEquals(StringDeserializer.class.getName(),
                String.valueOf(ConfigCapture.configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)));
        String json = LatencyTesterEvent.asJson(new LatencyTesterEvent());
        try (Deserializer<?> values = (Deserializer<?>) Class.forName(String.valueOf(
                        ConfigCapture.configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)))
                .getDeclaredConstructor().newInstance();
             StringSerializer producerValues = new StringSerializer()) {
            assertEquals(json, values.deserialize(TOPIC, producerValues.serialize(TOPIC, json)));
        }
    }

    @Test
    void aProfileWithoutDeserializersGivesAConsumerOfTheResponseTopic() throws Exception {
        useProfile(PROFILE);

        assertSubscribesToTheResponseTopic();
    }

    private static void assertSubscribesToTheResponseTopic() {
        Consumer<String, String> consumer = LatencyTestEventConsumer.createConsumer(TOPIC);
        try {
            assertEquals(Set.of(TOPIC), consumer.subscription());
        } finally {
            consumer.close(Duration.ZERO);
        }
    }

    public static final class ConfigCapture<K, V> implements ConsumerInterceptor<K, V> {

        static Map<String, ?> configs;

        @Override
        public void configure(Map<String, ?> configs) {
            ConfigCapture.configs = Map.copyOf(configs);
        }

        @Override
        public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
            return records;
        }

        @Override
        public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        }

        @Override
        public void close() {
        }
    }
}
