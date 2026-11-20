package org.opentsx.lg.klatency;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestResponseAnalysisConsumerTest {

    private static final String TOPIC = "latency_benchmark_response";

    @TempDir
    Path dir;

    private String previousProfile;

    private int previousGiveUp;

    @BeforeEach
    void useProfile() throws Exception {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        previousGiveUp = RequestResponseAnalysisConsumer.GIVE_UP;
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, "bootstrap.servers=127.0.0.1:1\n");
        OpenTSxClusterLink.PROPS_FN = profile.toString();
        RequestResponseAnalysisConsumer.GIVE_UP = 2;
    }

    @AfterEach
    void restore() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
        RequestResponseAnalysisConsumer.GIVE_UP = previousGiveUp;
    }

    private static MockConsumer<String, String> consumerWith(int records) {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        for (long i = 0; i < records; i++) {
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i, "k" + i, "{\"sendTS\":" + (i + 1) + "}"));
        }
        return consumer;
    }

    @Test
    void withoutOpentsxShowGuiTheTableOfTheTopicIsWrittenToTheFolder() throws Exception {
        MockConsumer<String, String> consumer = consumerWith(3);
        Path plots = dir.resolve("plots");

        RequestResponseAnalysisConsumer.runConsumer(consumer, null, plots.toString());

        File[] tables = plots.toFile().listFiles((d, name) -> name.startsWith("latency-by-message-"));
        assertEquals(1, tables.length);
        assertTrue(Files.readString(tables[0].toPath()).contains(TOPIC));
        assertTrue(consumer.closed());
    }

    @Test
    void anEmptyTopicStopsAndNamesTheTopic() {
        MockConsumer<String, String> consumer = consumerWith(0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RequestResponseAnalysisConsumer.runConsumer(consumer, null, dir.resolve("plots").toString()));

        assertEquals("getStatisticData: the series latency_benchmark_response has no values; expected at least 1 value",
                e.getMessage());
        assertTrue(consumer.closed());
    }
}
