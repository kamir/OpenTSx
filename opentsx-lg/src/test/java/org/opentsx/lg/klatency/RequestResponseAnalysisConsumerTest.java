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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void anEmptyRunPrintsNothingAndExitsWith1AfterOneLogLineWithoutAStackTrace() throws Exception {
        MockConsumer<String, String> consumer = consumerWith(0);
        int[] status = {-1};

        String[] printed = outAndErrOf(
                () -> status[0] = RequestResponseAnalysisConsumer.run(consumer, null, dir.resolve("plots").toString()));

        assertEquals(1, status[0]);
        assertEquals("", printed[0]);
        assertEquals(1, printed[1].lines().count(), printed[1]);
        assertTrue(printed[1].contains("ERROR " + RequestResponseAnalysisConsumer.class.getName()
                + " - no latency measured topic=latency_benchmark_response cause=getStatisticData: the series"
                + " latency_benchmark_response has no values; expected at least 1 value"), printed[1]);
        assertTrue(consumer.closed());
        assertTrue(Files.notExists(dir.resolve("plots")));
    }

    @Test
    void aRunWithResponsesExitsWith0() throws Exception {
        MockConsumer<String, String> consumer = consumerWith(3);
        int[] status = {-1};

        String[] printed = outAndErrOf(
                () -> status[0] = RequestResponseAnalysisConsumer.run(consumer, null, dir.resolve("plots").toString()));

        assertEquals(0, status[0]);
        assertEquals("", printed[1]);
        assertTrue(printed[0].endsWith("DONE\n"), printed[0]);
    }

    @Test
    void mainLogsEachEmptyPollButTheLastAtDebugPrintsNothingAndExitsWith1() throws Exception {
        Path profile = dir.resolve("main.props");
        Files.writeString(profile, "bootstrap.servers=127.0.0.1:1\nklatency.consumer.giveUp=2\nklatency.consumer.nrOfSamples=1\n");
        String logger = RequestResponseAnalysisConsumer.class.getName();

        String[] run = mainOf(EmptyTopic.class, logger, profile);

        assertEquals("1", run[0], run[2]);
        assertEquals("", run[1]);
        assertEquals(List.of(
                "[main] INFO " + logger + " - starting RequestResponseAnalysisConsumer",
                "[main] DEBUG " + logger + " - empty poll topic=latency_benchmark_response emptyPolls=1 giveUp=2",
                "[main] DEBUG " + logger + " - empty poll topic=latency_benchmark_response emptyPolls=2 giveUp=2",
                "[main] ERROR " + logger + " - no latency measured topic=latency_benchmark_response"
                        + " cause=getStatisticData: the series latency_benchmark_response has no values; expected at"
                        + " least 1 value",
                "[main] DEBUG " + logger + " - no latency measured topic=latency_benchmark_response"),
                run[2].lines().filter(line -> line.startsWith("[main] ")).toList(), run[2]);
    }

    public static class EmptyTopic {
        public static void main(String[] args) {
            RequestResponseAnalysisConsumer.consumers = topic -> consumerWith(0);
            RequestResponseAnalysisConsumer.main(args);
        }
    }

    /** Runs {@code launcher} in a new JVM with {@code logger} at DEBUG; returns the exit status, stdout and stderr. */
    private String[] mainOf(Class<?> launcher, String logger, Path profile) throws Exception {
        Path out = dir.resolve("main.out");
        Path err = dir.resolve("main.err");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + dir.resolve("home"),
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-Dorg.slf4j.simpleLogger.log." + logger + "=debug",
                "-cp", System.getProperty("surefire.test.class.path"), launcher.getName())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().put("OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME", profile.toString());
        builder.environment().remove("OPENTSX_TOPIC_MAP_FILE_NAME");
        builder.environment().remove("OPENTSX_SHOW_GUI");
        int status = builder.start().waitFor();
        return new String[] {String.valueOf(status), Files.readString(out), Files.readString(err)};
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

    @Test
    void theDefaultTableFolderIsBelowTheHomeDirectory() throws Exception {
        Path home = dir.resolve("home");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            RequestResponseAnalysisConsumer.runConsumer(consumerWith(3), null, RequestResponseAnalysisConsumer.plotFolder());
        }
        finally {
            System.setProperty("user.home", previousHome);
        }

        File[] tables = home.resolve("opentsx/plots").toFile().listFiles((d, name) -> name.startsWith("latency-by-message-"));
        assertNotNull(tables);
        assertEquals(1, tables.length);
    }
}
