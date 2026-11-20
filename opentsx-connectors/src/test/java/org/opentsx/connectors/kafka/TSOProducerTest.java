package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.core.TSData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TSOProducerTest {

    private static final String UNREACHABLE_WITHOUT_KEY_SERIALIZER = String.join("\n",
            "bootstrap.servers=127.0.0.1:1",
            "max.block.ms=0",
            "schema.registry.url=http://127.0.0.1:1",
            "interceptor.classes=" + ConfigCapture.class.getName(),
            "");

    private static final String NOT_SENT = TSOProducer.TOPIC_for_events + ": could not send the record; Topic "
            + TSOProducer.TOPIC_for_events + " not present in metadata after 0 ms.";

    private static final String EPISODES_NOT_SENT = TSOProducer.TOPIC_for_episodes + ": could not send the record; Topic "
            + TSOProducer.TOPIC_for_episodes + " not present in metadata after 0 ms.";

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

    private static Vector<TSData> oneSeries() {
        Vector<TSData> data = new Vector<>();
        data.add(new TSData("probe", "probe"));
        return data;
    }

    @Test
    void theEventProducerNeedsNoKeySerializerInTheProfile() throws Exception {
        useProfile(UNREACHABLE_WITHOUT_KEY_SERIALIZER);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TSOProducer().pushTSDataAsEventsToKafka_String_Avro(oneSeries(), "probe"));

        assertEquals(NOT_SENT, e.getMessage());
        assertEquals(StringSerializer.class.getName(),
                String.valueOf(ConfigCapture.configs.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)));
    }

    @Test
    void theKeySerializerOfTheProfileDoesNotApplyToTheEventProducer() throws Exception {
        useProfile(UNREACHABLE_WITHOUT_KEY_SERIALIZER
                + "key.serializer=org.apache.kafka.common.serialization.ByteArraySerializer\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TSOProducer().pushTSDataAsEventsToKafka_String_Avro(oneSeries(), "probe"));

        assertEquals(NOT_SENT, e.getMessage());
        assertEquals(StringSerializer.class.getName(),
                String.valueOf(ConfigCapture.configs.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)));
    }

    @Test
    void theEventProducerSetsNoConsumerKey() throws Exception {
        useProfile(UNREACHABLE_WITHOUT_KEY_SERIALIZER);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TSOProducer().pushTSDataAsEventsToKafka_String_Avro(oneSeries(), "probe"));

        assertEquals(NOT_SENT, e.getMessage());
        assertFalse(ConfigCapture.configs.containsKey(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void theEpisodeProducerSetsNoConsumerKey() throws Exception {
        useProfile(UNREACHABLE_WITHOUT_KEY_SERIALIZER);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TSOProducer().pushTSDataAsEpisodesToKafka_String_Avro(oneSeries(), "probe", null));

        assertEquals(EPISODES_NOT_SENT, e.getMessage());
        assertFalse(ConfigCapture.configs.containsKey(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    public static final class ConfigCapture<K, V> implements ProducerInterceptor<K, V> {

        static Map<String, ?> configs;

        @Override
        public void configure(Map<String, ?> configs) {
            ConfigCapture.configs = Map.copyOf(configs);
        }

        @Override
        public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        }

        @Override
        public void close() {
        }
    }
}
