package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.klatency.LatencyTestEventProducer;
import org.opentsx.connectors.kafka.kping.TSOEventAnalysisProducer;
import org.opentsx.data.model.LatencyTesterEvent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProfileOutputTest {

    private static final String PROFILE = String.join("\n",
            "bootstrap.servers=127.0.0.1:1",
            "max.block.ms=0",
            "sasl.mechanism=PLAIN",
            "sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"probe-user\" password=\"probe-secret\";",
            "sasl.username=probe-user",
            "sasl.password=probe-secret",
            "basic.auth.credentials.source=USER_INFO",
            "basic.auth.user.info=probe-user:probe-secret",
            "schema.registry.basic.auth.user.info=probe-user:probe-secret",
            "schema.registry.url=http://127.0.0.1:1",
            "key.serializer=org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer=io.confluent.kafka.serializers.KafkaAvroSerializer",
            "");

    private static final String TLS_AND_TOKEN_SECRETS = String.join("\n",
            "ssl.truststore.password=probe-secret",
            "ssl.keystore.password=probe-secret",
            "ssl.key.password=probe-secret",
            "schema.registry.ssl.truststore.password=probe-secret",
            "bearer.auth.token=probe-secret",
            "");

    @TempDir
    Path dir;

    private String previousProfile;

    @BeforeEach
    void useProbeProfile() throws Exception {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, PROFILE);
        OpenTSxClusterLink.PROPS_FN = profile.toString();
    }

    @AfterEach
    void restoreProfile() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
    }

    @Test
    void loadingTheProfilePrintsNoCredentials() {
        String out = stdout(OpenTSxClusterLink::getClientProperties);
        assertNoCredentials(out);
        assertTrue(out.contains("probe.props"), out);
    }

    @Test
    void theProfileWithoutSecurityPropertiesHasNoCredentials() throws Exception {
        Files.writeString(Path.of(OpenTSxClusterLink.PROPS_FN), PROFILE + TLS_AND_TOKEN_SECRETS);
        String[] shown = new String[1];
        String out = stdout(() -> shown[0] = OpenTSxClusterLink.getPropsAsString_NO_SECURITY_PROPS());
        assertNoCredentials(out);
        assertNoCredentials(shown[0]);
        assertTrue(shown[0].contains("bootstrap.servers=127.0.0.1:1"), shown[0]);
    }

    @Test
    void theProfileIsReadOnceForTheListWithoutSecurityProperties() {
        String out = stdout(OpenTSxClusterLink::getPropsAsString_NO_SECURITY_PROPS);
        assertEquals(1, out.split("Client properties from file", -1).length - 1, out);
    }

    @Test
    void theProducersPrintNoCredentials() {
        String out = stdout(() -> {
            new TSOProducer().pushTSDataAsEpisodesToKafka_String_Avro(new Vector<>(), "probe", null);
            new TSOProducer().pushTSDataAsEventsToKafka_String_Avro(new Vector<>(), "probe");
            new TSOEventAnalysisProducer().pushTSDataAsEpisodesToKafka_String_Avro(new Vector<>(), "probe", null);
            new TSOEventAnalysisProducer().pushTSDataAsEventsToKafka_String_Avro(new Vector<>(), "probe");
            LatencyTestEventProducer latency = new LatencyTestEventProducer();
            latency.pushTestEventToCluster(new LatencyTesterEvent());
            latency.close();
        });
        assertNoCredentials(out);
    }

    @Test
    void theStringProducersReadTheConfiguredProfile() {
        String out = stdout(() -> {
            try (Producer<String, String> producer = TSOProducer.createProducer()) {
                assertTrue(producer.metrics().size() > 0);
            }
            try (Producer<String, String> producer = TSOEventAnalysisProducer.createProducer()) {
                assertTrue(producer.metrics().size() > 0);
            }
        });
        assertNoCredentials(out);
        assertEquals(2, out.split("probe.props", -1).length - 1, out);
    }

    @Test
    void aProfileThatCannotBeReadThrowsAndNamesThePath() {
        Path missing = dir.resolve("absent.props");
        OpenTSxClusterLink.PROPS_FN = missing.toString();
        UncheckedIOException e = assertThrows(UncheckedIOException.class, OpenTSxClusterLink::getClientProperties);
        assertTrue(e.getMessage().startsWith(missing.toAbsolutePath() + ": "), e.getMessage());
    }

    @Test
    void aProfileThatCannotBeReadStopsTheEventProducers() {
        Path missing = dir.resolve("absent.props");
        OpenTSxClusterLink.PROPS_FN = missing.toString();
        assertNamesTheProfile(missing, () -> new TSOProducer().pushTSDataAsEventsToKafka_String_Avro(new Vector<>(), "probe"));
        assertNamesTheProfile(missing, () -> new TSOEventAnalysisProducer().pushTSDataAsEventsToKafka_String_Avro(new Vector<>(), "probe"));
        assertNamesTheProfile(missing, () -> new LatencyTestEventProducer().pushTestEventToCluster(new LatencyTesterEvent()));
    }

    @Test
    void aProfileThatCannotBeReadStopsTheEpisodeProducers() {
        Path missing = dir.resolve("absent.props");
        OpenTSxClusterLink.PROPS_FN = missing.toString();
        assertNamesTheProfile(missing, () -> new TSOProducer().pushTSDataAsEpisodesToKafka_String_Avro(new Vector<>(), "probe", null));
        assertNamesTheProfile(missing, () -> new TSOEventAnalysisProducer().pushTSDataAsEpisodesToKafka_String_Avro(new Vector<>(), "probe", null));
    }

    private static void assertNamesTheProfile(Path profile, Executable action) {
        UncheckedIOException e = assertThrows(UncheckedIOException.class, action);
        assertTrue(e.getMessage().startsWith(profile.toAbsolutePath() + ": cannot read the Kafka client profile"), e.getMessage());
    }

    private static void assertNoCredentials(String text) {
        assertFalse(text.contains("sasl."), text);
        assertFalse(text.contains("basic.auth"), text);
        assertFalse(text.contains("probe-secret"), text);
    }

    private static String stdout(Executable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.execute();
        } catch (Throwable t) {
            throw new AssertionError("the action failed; output so far:\n" + buffer.toString(StandardCharsets.UTF_8), t);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
