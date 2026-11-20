package org.opentsx.connectors.kafka.kping;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.TSOProducer;

import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TSOEventAnalysisConsumerTest {

    private static final String EVENT_PRODUCER_PROFILE = String.join("\n",
            "bootstrap.servers=127.0.0.1:1",
            "key.serializer=org.apache.kafka.common.serialization.StringSerializer",
            "schema.registry.url=http://127.0.0.1:1",
            "interceptor.classes=" + ConfigCapture.class.getName(),
            "");

    @TempDir
    Path dir;

    private String previousProfile;

    @BeforeEach
    void useTheEventProducerProfile() throws Exception {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, EVENT_PRODUCER_PROFILE);
        OpenTSxClusterLink.PROPS_FN = profile.toString();
        ConfigCapture.configs = null;
    }

    @AfterEach
    void restoreProfile() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
    }

    @Test
    void theConsumerDeclaresTheStringKeyAndTheAvroValueOfTheEventProducer() throws Exception {
        ParameterizedType consumer = (ParameterizedType) TSOEventAnalysisConsumer.class
                .getMethod("createConsumer", String.class).getGenericReturnType();

        assertEquals(List.of(String.class, GenericRecord.class), List.of(consumer.getActualTypeArguments()));
    }

    @Test
    void theProfileOfTheEventProducerGivesAConsumerOfTheEventTopic() {
        assertSubscribesToTheEventTopic();
    }

    @Test
    void theDeserializersOfTheProfileDoNotApply() throws Exception {
        Files.writeString(Path.of(OpenTSxClusterLink.PROPS_FN), EVENT_PRODUCER_PROFILE
                + "key.deserializer=org.opentsx.probe.NoSuchDeserializer\n"
                + "value.deserializer=org.opentsx.probe.NoSuchDeserializer\n");

        assertSubscribesToTheEventTopic();
    }

    @Test
    void theConsumerReadsTheLabelKeyThatTheEventProducerWrites() throws Exception {
        assertSubscribesToTheEventTopic();

        assertEquals(KafkaAvroDeserializer.class.getName(),
                String.valueOf(ConfigCapture.configs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)));
        try (Deserializer<?> keys = (Deserializer<?>) Class.forName(String.valueOf(
                        ConfigCapture.configs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)))
                .getDeclaredConstructor().newInstance();
             StringSerializer producerKeys = new StringSerializer()) {
            String topic = TSOProducer.TOPIC_for_events;
            assertEquals("probe-label", keys.deserialize(topic, producerKeys.serialize(topic, "probe-label")));
        }
    }

    private static void assertSubscribesToTheEventTopic() {
        Consumer<?, ?> consumer = TSOEventAnalysisConsumer.createConsumer(TSOProducer.TOPIC_for_events);
        try {
            assertEquals(Set.of(TSOProducer.TOPIC_for_events), consumer.subscription());
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
