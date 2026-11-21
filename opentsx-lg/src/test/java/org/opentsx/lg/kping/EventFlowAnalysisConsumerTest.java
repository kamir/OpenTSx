package org.opentsx.lg.kping;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventFlowAnalysisConsumerTest {

    private static final String TOPIC = "OpenTSx_Events";

    private static final Schema EVENT = SchemaBuilder.record("Event").fields()
            .requiredLong("timestamp").requiredLong("producer_timestamp").endRecord();

    @TempDir
    Path dir;

    private String previousProfile;

    private int previousGiveUp;

    @BeforeEach
    void useProfile() throws Exception {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        previousGiveUp = EventFlowAnalysisConsumer.GIVE_UP;
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, "bootstrap.servers=127.0.0.1:1\n");
        OpenTSxClusterLink.PROPS_FN = profile.toString();
        EventFlowAnalysisConsumer.GIVE_UP = 2;
    }

    @AfterEach
    void restore() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
        EventFlowAnalysisConsumer.GIVE_UP = previousGiveUp;
    }

    private static MockConsumer<String, GenericRecord> consumerWith(int records) {
        MockConsumer<String, GenericRecord> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        for (long i = 0; i < records; i++) {
            GenericRecord event = new GenericData.Record(EVENT);
            event.put("timestamp", i);
            event.put("producer_timestamp", i);
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i, "probe", event));
        }
        return consumer;
    }

    @Test
    void withoutOpentsxShowGuiTheTableOfTheTopicIsWrittenToTheFolder() throws Exception {
        MockConsumer<String, GenericRecord> consumer = consumerWith(3);
        Path plots = dir.resolve("plots");

        EventFlowAnalysisConsumer.runConsumer(consumer, null, plots.toString());

        File[] tables = plots.toFile().listFiles((d, name) -> name.startsWith("latency-by-message-"));
        assertEquals(1, tables.length);
        assertTrue(Files.readString(tables[0].toPath()).contains(TOPIC));
        assertTrue(consumer.closed());
    }

    @Test
    void theEmptyPollsCountOverTheWholeRunSoAPollWithEventsDoesNotResetThem() {
        int[] polls = {0};
        MockConsumer<String, GenericRecord> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized ConsumerRecords<String, GenericRecord> poll(Duration timeout) {
                polls[0]++;
                return super.poll(timeout);
            }
        };
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        GenericRecord event = new GenericData.Record(EVENT);
        event.put("timestamp", 0L);
        event.put("producer_timestamp", 0L);
        consumer.scheduleNopPollTask();
        consumer.schedulePollTask(() -> consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L, "probe", event)));

        EventFlowAnalysisConsumer.runConsumer(consumer, null, dir.resolve("plots").toString());

        assertEquals(4, polls[0]);
    }

    @Test
    void anEmptyTopicStopsAndNamesTheTopic() {
        MockConsumer<String, GenericRecord> consumer = consumerWith(0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EventFlowAnalysisConsumer.runConsumer(consumer, null, dir.resolve("plots").toString()));

        assertEquals("getStatisticData: the series OpenTSx_Events has no values; expected at least 1 value",
                e.getMessage());
        assertTrue(consumer.closed());
        assertTrue(Files.notExists(dir.resolve("plots")));
    }

    @Test
    void anEmptyRunPrintsNothingAndExitsWith1AfterOneLogLineWithoutAStackTrace() throws Exception {
        MockConsumer<String, GenericRecord> consumer = consumerWith(0);
        int[] status = {-1};

        String[] printed = outAndErrOf(
                () -> status[0] = EventFlowAnalysisConsumer.run(consumer, null, dir.resolve("plots").toString()));

        assertEquals(1, status[0]);
        assertEquals("", printed[0]);
        assertEquals(1, printed[1].lines().count(), printed[1]);
        assertTrue(printed[1].contains("ERROR " + EventFlowAnalysisConsumer.class.getName() + " - no latency measured"
                + " topic=OpenTSx_Events cause=getStatisticData: the series OpenTSx_Events has no values; expected at"
                + " least 1 value"), printed[1]);
        assertTrue(consumer.closed());
        assertTrue(Files.notExists(dir.resolve("plots")));
    }

    @Test
    void aRunWithEventsExitsWith0() throws Exception {
        MockConsumer<String, GenericRecord> consumer = consumerWith(3);
        int[] status = {-1};

        String[] printed = outAndErrOf(
                () -> status[0] = EventFlowAnalysisConsumer.run(consumer, null, dir.resolve("plots").toString()));

        assertEquals(0, status[0]);
        assertEquals("", printed[1]);
        assertTrue(printed[0].endsWith("DONE\n"), printed[0]);
    }

    @Test
    void thePlotsGoToTheHomeDirectory() {
        assertEquals(System.getProperty("user.home") + "/opentsx/plots", EventFlowAnalysisConsumer.PLOT_FOLDER);
    }

    interface Action {
        void run() throws Exception;
    }

    private static String[] outAndErrOf(Action action) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, UTF_8));
        System.setErr(new PrintStream(capturedErr, true, UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new String[] {capturedOut.toString(UTF_8), capturedErr.toString(UTF_8)};
    }
}
