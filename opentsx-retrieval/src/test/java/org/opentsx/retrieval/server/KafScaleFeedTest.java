package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.vector.VectorAligner;
import org.opentsx.retrieval.vector.VectorJson;
import org.opentsx.retrieval.vector.VectorProcessorTest.FakeBroker;
import org.opentsx.store.InMemorySeriesStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.opentsx.retrieval.vector.VectorProcessorTest.LAYOUT;
import static org.opentsx.retrieval.vector.VectorProcessorTest.SECOND;
import static org.opentsx.retrieval.vector.VectorProcessorTest.SPEED;
import static org.opentsx.retrieval.vector.VectorProcessorTest.T0;

/** The KafScale feed of the retrieval server over a broker in memory: what it counts, logs and hands to the inventory. */
class KafScaleFeedTest {

    static final String VECTORS = "tsx.wind.smart.turbine-state.vectors.v1";
    static final Node NODE = new Node(0, "127.0.0.1", 1);
    static final OpenTsxAvro AVRO = new OpenTsxAvro();

    interface Action {
        void run() throws Exception;
    }

    /** The lines the loggers write while {@code action} runs; slf4j-simple writes to System.err. */
    static List<String> logged(Action action) throws Exception {
        PrintStream stderr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(stderr);
        }
        return err.toString(StandardCharsets.UTF_8).lines().toList();
    }

    /** The messages of log lines, without thread, level and logger. */
    static List<String> messages(List<String> lines) {
        return lines.stream().map(l -> l.substring(l.indexOf(" - ") + 3)).toList();
    }

    static byte[] episode(int block) {
        double[] v = new double[600];
        java.util.Arrays.fill(v, 5 + block);
        return AVRO.encode(Episodes.regular(SPEED, T0 + block * 600 * SECOND, SECOND, v).source("t").createdAt(T0).build());
    }

    static byte[] vectorRow(int minute) {
        return VectorJson.bytes(VectorJson.record("turbine-state", new VectorAligner.Point(Map.of("turbine", "T01"),
                T0 + minute * 60 * SECOND, T0 + (minute + 1) * 60 * SECOND, List.of("wind_speed"), List.of(5.0)), "d", "h"));
    }

    /** The topics of domain wind with a vector topic and the status topic, each with one partition. */
    static FakeBroker broker() {
        FakeBroker broker = new FakeBroker();
        broker.log.put(new TopicPartition(VECTORS, 0), new ArrayList<>());
        broker.log.put(new TopicPartition(LAYOUT.dagStatus(), 0), new ArrayList<>());
        return broker;
    }

    static KafScaleFeed feed(FakeBroker broker, InMemorySeriesStore store, Runnable onChange) {
        return new KafScaleFeed("ks:9092", "wind", broker.consumer(), b -> new MockAdminClient(List.of(NODE), NODE),
                b -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), store, 100, onChange);
    }

    static JsonNode inventorySource(KafScaleFeed feed, InMemorySeriesStore store) throws Exception {
        SeriesInventory inventory = new SeriesInventory(store, new SeriesInventory.Source("kafscale", "wind",
                KafScaleFeed.topics("wind")), null, null, Duration.ZERO);
        inventory.sourceDetails(feed::details);
        try (RetrievalServer server = new RetrievalServer(new EpisodeService(store), 0, inventory)) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/v1/inventory")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            return EpisodeJson.JSON.readTree(r.body()).path("source");
        }
    }

    @Test
    void whatTheFeedDropsIsCountedLoggedOnceAndShownInTheInventory() throws Exception {
        FakeBroker broker = broker();
        broker.append(LAYOUT.episodes(), 0, episode(0));
        broker.append(LAYOUT.episodes(), 1, "not an episode".getBytes(StandardCharsets.UTF_8));
        broker.append(VECTORS, 0, vectorRow(0));
        broker.append(VECTORS, 0, "{\"schema\":\"opentsx.vector.v1\"}".getBytes(StandardCharsets.UTF_8));
        broker.append(LAYOUT.dags(), 0, "not json".getBytes(StandardCharsets.UTF_8));
        broker.append(LAYOUT.dagStatus(), 0, "{\"schema\":\"other\"}".getBytes(StandardCharsets.UTF_8));
        InMemorySeriesStore store = new InMemorySeriesStore();
        int[] changes = {0};
        try (KafScaleFeed feed = feed(broker, store, () -> changes[0]++)) {
            List<String> load = logged(feed::load);
            JsonNode src = inventorySource(feed, store);
            assertEquals(1, src.path("undecodable").asLong(), src.toString());
            assertEquals(1, src.path("droppedVectorRows").asLong(), src.toString());
            assertEquals(1, src.path("droppedControlRecords").asLong(), src.toString());
            assertEquals(1, src.path("droppedStatusRecords").asLong(), src.toString());
            assertEquals(0, src.path("lostToRetention").asLong(), src.toString());
            assertEquals(0, src.path("refreshFailures").asLong(), src.toString());
            assertEquals("[\"" + VECTORS + "\"]", src.path("vectorTopics").toString());
            List<String> loaded = load.stream().filter(l -> l.contains(" INFO org.opentsx.retrieval.server.KafScaleFeed - feed loaded")).toList();
            assertEquals(1, loaded.size(), String.join("\n", load));
            assertTrue(loaded.get(0).endsWith(" - feed loaded topic=tsx.wind.episodes.v2 episodes=1 truncated=false"
                    + " vectorTopics=" + VECTORS + " undecodable=1 lostToRetention=0 droppedVectorRows=1"
                    + " droppedControlRecords=1 droppedStatusRecords=1"), loaded.get(0));
            List<String> drops = load.stream().filter(l -> l.contains(" WARN org.opentsx.retrieval.server.KafScaleFeed - ")).toList();
            assertEquals(List.of(
                    "record dropped and counted in droppedVectorRows; later ones of this partition are only counted topic=" + VECTORS
                            + " partition=0 offset=1 reason=not an opentsx.vector.v1 record",
                    "record dropped and counted in droppedControlRecords; later ones of this partition are only counted"
                            + " topic=tsx.wind.dags.v1 partition=0 offset=0 reason=not an opentsx.dag-control.v1 record with a dag",
                    "record dropped and counted in droppedStatusRecords; later ones of this partition are only counted"
                            + " topic=tsx.wind.dag-status.v1 partition=0 offset=0 reason=not an opentsx.dag-status.v1 record with a key"),
                    messages(drops), String.join("\n", load));

            // a second bad row of the same partition is counted, not logged; the refresh line and the inventory follow
            broker.append(VECTORS, 0, "[]".getBytes(StandardCharsets.UTF_8));
            int before = changes[0];
            List<String> refresh = logged(feed::refresh);
            assertTrue(changes[0] > before, "the inventory is told that the counters changed");
            assertEquals(List.of("feed refreshed episodes=0 undecodable=1 lostToRetention=0 droppedVectorRows=2"
                            + " droppedControlRecords=1 droppedStatusRecords=1"),
                    messages(refresh));
            assertEquals(2, inventorySource(feed, store).path("droppedVectorRows").asLong());

            // a refresh that finds nothing new logs nothing
            assertEquals(List.of(), logged(feed::refresh));
        }
    }

    @Test
    void aRefreshThatThrowsAnErrorKeepsTheScheduleAndLogsOneError() throws Exception {
        FakeBroker broker = broker();
        broker.append(LAYOUT.episodes(), 0, episode(0));
        InMemorySeriesStore store = new InMemorySeriesStore();
        try (KafScaleFeed feed = feed(broker, store, () -> { })) {
            logged(feed::load);
            broker.unreadable.put(LAYOUT.episodes(), new AssertionError("engine invariant broken"));
            List<String> failing = logged(() -> {
                // the scheduled task returns, so the executor keeps the schedule; an exception would cancel it for good
                feed.refresh();
                feed.refresh();
            });
            List<String> errors = failing.stream().filter(l -> l.contains(" ERROR ")).toList();
            assertEquals(1, errors.size(), String.join("\n", failing));
            assertTrue(errors.get(0).endsWith(" ERROR org.opentsx.retrieval.server.KafScaleFeed - feed refresh failed; the next"
                    + " refresh tries again source=refresh cause=java.lang.AssertionError: engine invariant broken"), errors.get(0));
            assertEquals(2, inventorySource(feed, store).path("refreshFailures").asLong());

            broker.unreadable.clear();
            broker.append(LAYOUT.episodes(), 0, episode(1));
            List<String> recovered = logged(feed::refresh);
            assertEquals(List.of("feed refresh works again source=refresh",
                            "feed refreshed episodes=1 undecodable=0 lostToRetention=0 droppedVectorRows=0"
                                    + " droppedControlRecords=0 droppedStatusRecords=0"),
                    messages(recovered));
            assertEquals(0, inventorySource(feed, store).path("refreshFailures").asLong());
        }
    }

    @Test
    void aTopicThatCannotBeReadIsLoggedOnceAtWarnAndItsRecoveryAtInfo() throws Exception {
        FakeBroker broker = broker();
        broker.append(LAYOUT.episodes(), 0, episode(0));
        broker.append(VECTORS, 0, vectorRow(0));
        InMemorySeriesStore store = new InMemorySeriesStore();
        try (KafScaleFeed feed = feed(broker, store, () -> { })) {
            broker.unreadable.put(VECTORS, new org.apache.kafka.common.errors.TimeoutException(
                    "Timeout expired while fetching topic metadata"));
            broker.unreadable.put(LAYOUT.dags(), new IllegalStateException("timed out reading [tsx.wind.dags.v1-0]"));
            List<String> load = logged(feed::load);
            assertEquals(List.of(
                    "vector topic cannot be read; the next try is in PT1M30S source=" + VECTORS
                            + " cause=org.apache.kafka.common.errors.TimeoutException: Timeout expired while fetching topic metadata",
                    "topic cannot be read now; the next refresh tries again source=tsx.wind.dags.v1,tsx.wind.dag-status.v1"
                            + " cause=java.lang.IllegalStateException: timed out reading [tsx.wind.dags.v1-0]"),
                    messages(load.stream().filter(l -> l.contains(" WARN ")).toList()), String.join("\n", load));
            assertEquals("[\"" + VECTORS + "\"]", inventorySource(feed, store).path("unreadableVectorTopics").toString());

            assertEquals(List.of(), logged(feed::refresh), "the same failure again is not logged again");
            broker.unreadable.remove(LAYOUT.dags());
            assertEquals(List.of("topic readable again source=tsx.wind.dags.v1,tsx.wind.dag-status.v1"), messages(logged(feed::refresh)));
        }
    }

    @Test
    void aRecordWithoutAValueOnTheControlOrStatusTopicIsCountedNotAReadFailure() throws Exception {
        FakeBroker broker = broker();
        broker.append(LAYOUT.dags(), 0, null);
        broker.append(LAYOUT.dagStatus(), 0, null);
        InMemorySeriesStore store = new InMemorySeriesStore();
        try (KafScaleFeed feed = feed(broker, store, () -> { })) {
            List<String> lines = logged(feed::load);
            assertFalse(lines.stream().anyMatch(l -> l.contains("cannot be read")), String.join("\n", lines));
            JsonNode src = inventorySource(feed, store);
            assertEquals(1, src.path("droppedControlRecords").asLong(), src.toString());
            assertEquals(1, src.path("droppedStatusRecords").asLong(), src.toString());
            assertEquals(List.of(
                    "record dropped and counted in droppedControlRecords; later ones of this partition are only counted"
                            + " topic=tsx.wind.dags.v1 partition=0 offset=0 reason=not an opentsx.dag-control.v1 record with a dag",
                    "record dropped and counted in droppedStatusRecords; later ones of this partition are only counted"
                            + " topic=tsx.wind.dag-status.v1 partition=0 offset=0 reason=not an opentsx.dag-status.v1 record with a key"),
                    messages(lines.stream().filter(l -> l.contains(" WARN ")).toList()), String.join("\n", lines));
        }
    }

    @Test
    void aWriteWhoseReadBackFailsIsAnsweredAndTheReadFailureIsLogged() throws Exception {
        FakeBroker broker = broker();
        InMemorySeriesStore store = new InMemorySeriesStore();
        try (KafScaleFeed feed = feed(broker, store, () -> { })) {
            logged(feed::load);
            broker.unreadable.put(LAYOUT.dags(), new IllegalStateException("timed out reading [tsx.wind.dags.v1-0]"));
            JsonNode[] answer = new JsonNode[1];
            List<String> lines = logged(() -> answer[0] = feed.stop("wind-exp2-smart", "test"));
            assertEquals("{\"dag\":\"wind-exp2-smart\",\"state\":\"stopped\"}", answer[0].toString(), "the record is written");
            assertEquals(List.of("topic cannot be read now; the next refresh tries again source=tsx.wind.dags.v1,tsx.wind.dag-status.v1"
                    + " cause=java.lang.IllegalStateException: timed out reading [tsx.wind.dags.v1-0]"), messages(lines));
        }
    }

    @Test
    void theLinesOfTheFeedAndTheProcessorFollowTheLogLevel(@TempDir Path dir) throws Exception {
        String quiet = runLogMain(dir.resolve("error"), "error");
        assertEquals("", quiet, "at level error the feed and the processor write nothing");
        String info = runLogMain(dir.resolve("info"), "info");
        for (String line : List.of(" INFO org.opentsx.retrieval.server.KafScaleFeed - feed loaded ",
                " INFO org.opentsx.retrieval.server.KafScaleFeed - feed refreshed episodes=1 ",
                " WARN org.opentsx.retrieval.server.KafScaleFeed - vector topic cannot be read; ",
                " WARN org.opentsx.retrieval.server.KafScaleFeed - topic cannot be read now; the next refresh tries again source=tsx.wind.dags.v1,",
                " INFO org.opentsx.retrieval.vector.VectorProcessor - sink deployed dag=wind-exp2-smart sink=turbine-state ",
                " WARN org.opentsx.retrieval.vector.VectorProcessor - dag refused dag=wind-bad ",
                " INFO org.opentsx.retrieval.vector.VectorProcessor - dag stopped dag=wind-exp2-smart",
                " WARN org.opentsx.retrieval.vector.VectorProcessor - control topic cannot be read now; ")) {
            assertTrue(info.contains(line), "expected \"" + line + "\" in\n" + info);
        }
    }

    private static String runLogMain(Path dir, String level) throws Exception {
        Files.createDirectories(dir);
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path"), FeedLogMain.class.getName(), dir.resolve("state").toString())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        pb.environment().put("OPENTSX_LOG_LEVEL", level);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 60 s\nstderr: " + Files.readString(err));
        }
        assertEquals(0, p.exitValue(), Files.readString(err));
        assertEquals("", Files.readString(out), "the feed and the processor write nothing to stdout");
        return Files.readString(err);
    }

    @Test
    void theFeedTellsWhenItLastRefreshedAndHowManyRefreshesFailedSince() throws Exception {
        FakeBroker broker = broker();
        try (KafScaleFeed feed = feed(broker, new InMemorySeriesStore(), () -> { })) {
            feed.load();
            java.time.Instant loaded = feed.lastRefresh();
            assertTrue(loaded != null, "the load is the first refresh that worked");
            assertEquals(0, feed.refreshFailures());
            broker.unreadable.put(LAYOUT.episodes(), new org.apache.kafka.common.errors.TimeoutException("no answer"));
            logged(() -> {
                feed.refresh();
                feed.refresh();
            });
            assertEquals(2, feed.refreshFailures());
            assertEquals(loaded, feed.lastRefresh(), "a failed refresh leaves the time of the last one that worked");
            broker.unreadable.clear();
            feed.refresh();
            assertEquals(0, feed.refreshFailures());
            assertTrue(feed.lastRefresh() != null);
        }
    }
}
