package org.opentsx.connectors.kafka;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.klatency.LatencyTestEventProducer;
import org.opentsx.core.TSData;
import org.opentsx.data.model.LatencyTesterEvent;
import org.opentsx.data.series.TimeSeriesObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelperFailureTest {

    private static final String UNREACHABLE = String.join("\n",
            "bootstrap.servers=127.0.0.1:1",
            "max.block.ms=0",
            "key.serializer=org.apache.kafka.common.serialization.StringSerializer",
            "schema.registry.url=http://127.0.0.1:1",
            "");

    @TempDir
    Path dir;

    private String previousProfile;

    @BeforeEach
    void rememberProfile() {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
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
        TSData series = new TSData("probe", "probe");
        Vector<TSData> data = new Vector<>();
        data.add(series);
        return data;
    }

    private static String notSent(String topic) {
        return topic + ": could not send the record; Topic " + topic + " not present in metadata after 0 ms.";
    }

    @Test
    void theLatencyProducerThrowsWhenTheBrokerIsUnreachable() throws Exception {
        useProfile(UNREACHABLE);
        LatencyTestEventProducer producer = new LatencyTestEventProducer();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> producer.pushTestEventToCluster(new LatencyTesterEvent()));
        producer.close();
        assertEquals(notSent(LatencyTestEventProducer.TOPIC_for_request_events_string), e.getMessage());
    }

    @Test
    void theFlowStateProducerThrowsWhenTheBrokerIsUnreachable() throws Exception {
        useProfile(UNREACHABLE);
        EventFlowStateProducer producer = new EventFlowStateProducer();
        Properties state = new Properties();
        state.put("generator", "probe");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> producer.pushFlowState(state, "probe"));
        producer.close();
        assertEquals(notSent(EventFlowStateProducer.TOPIC), e.getMessage());
    }

    @Test
    void theEventProducerThrowsWhenTheBrokerIsUnreachable() throws Exception {
        useProfile(UNREACHABLE);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TSOProducer().pushTSDataAsEventsToKafka_String_Avro(oneSeries(), "probe"));
        assertEquals(notSent(TSOProducer.TOPIC_for_events), e.getMessage());
    }

    @Test
    void theEpisodeProducerThrowsWhenTheBrokerIsUnreachable() throws Exception {
        useProfile(UNREACHABLE);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TSOProducer().pushTSDataAsEpisodesToKafka_String_Avro(oneSeries(), "probe", "A"));
        assertEquals(notSent(TSOProducer.TOPIC_for_episodes + "_A"), e.getMessage());
    }

    @Test
    void aSendThatFailsBeforeReachingTheBrokerNamesTheTopic() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        producer.sendException = new KafkaException("probe: the serializer failed");
        Properties state = new Properties();
        state.put("generator", "probe");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new EventFlowStateProducer(producer).pushFlowState(state, "probe"));
        assertEquals(EventFlowStateProducer.TOPIC + ": could not send the record; probe: the serializer failed",
                e.getMessage());
        assertSame(producer.sendException, e.getCause());
    }

    @Test
    void persistBucketKafkaThrowsAndStoresNothing() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> TSOWriter.persistBucket_Kafka(new Vector<>(), "probe", "probe_topic"));
        assertEquals("probe_topic: persistBucket_Kafka stores nothing; use org.opentsx.bridge.LegacyBridge and"
                + " org.opentsx.kafka.BucketWriter", e.getMessage());
    }

    @Test
    void theSequenceFileWriterPassesOnAFileSystemError() {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "probe:///");
        TSOWriter.setConfig(conf);
        try {
            assertThrows(UnsupportedFileSystemException.class,
                    () -> TSOWriter.persistBucket_SequenceFile(new Vector<>(), "probe", "probe", "seq"));
        }
        finally {
            TSOWriter.setConfig(null);
        }
    }

    @Test
    void theTsoEventProducerTakesTheBootstrapFromTheProfile() throws Exception {
        useProfile("bootstrap.servers=probe-bootstrap\n");
        KafkaException e = assertThrows(KafkaException.class,
                () -> new TSOEventProducer().pushTSOItemsToKafka(new Vector<TimeSeriesObject>()));
        assertEquals("Invalid url in bootstrap.servers: probe-bootstrap", e.getCause().getMessage());
    }

    @Test
    void theTsoConsumerTakesTheBootstrapFromTheProfile() throws Exception {
        useProfile("bootstrap.servers=probe-bootstrap\n");
        KafkaException e = assertThrows(KafkaException.class, () -> new TSOConsumer("probe"));
        assertEquals("Invalid url in bootstrap.servers: probe-bootstrap", e.getCause().getMessage());
    }
}
