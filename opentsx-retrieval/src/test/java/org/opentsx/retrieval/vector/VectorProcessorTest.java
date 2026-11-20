package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.SeriesKey;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class VectorProcessorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    public static final long T0 = 1_790_812_800_000_000L;      // 2026-10-01T00:00:00Z
    public static final long SECOND = 1_000_000L;
    public static final TopicLayout LAYOUT = new TopicLayout("wind");
    public static final SeriesKey SPEED = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p", "turbine", "T01"), "m/s");
    public static final SeriesKey POWER = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "p", "turbine", "T01"), "kW");
    public static final VectorProcessor.Timing FAST = new VectorProcessor.Timing(Duration.ZERO, Duration.ofHours(1), Duration.ZERO,
            Duration.ZERO, Duration.ofMillis(1), Duration.ofSeconds(30));

    /** Topics in memory. Every consumer it hands out sees what is appended, also after it was created. */
    public static final class FakeBroker {
        public final Map<TopicPartition, List<byte[]>> log = new TreeMap<>((a, b) -> a.topic().equals(b.topic())
                ? Integer.compare(a.partition(), b.partition()) : a.topic().compareTo(b.topic()));
        /** The first offset retention kept, per partition; 0 when not set. Consumers created later see it. */
        public final Map<TopicPartition, Long> logStart = new HashMap<>();
        /** Topics whose metadata cannot be read: asking for their partitions throws this, a RuntimeException or an Error. */
        public final Map<String, Throwable> unreadable = new HashMap<>();

        public FakeBroker() {
            log.put(new TopicPartition(LAYOUT.dags(), 0), new ArrayList<>());
            log.put(new TopicPartition(LAYOUT.episodes(), 0), new ArrayList<>());
            log.put(new TopicPartition(LAYOUT.episodes(), 1), new ArrayList<>());
        }

        public void append(String topic, int partition, byte[] value) {
            log.get(new TopicPartition(topic, partition)).add(value);
        }

        public Consumer<byte[], byte[]> consumer() {
            Map<TopicPartition, Integer> handed = new HashMap<>();
            MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
                @Override
                public synchronized List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
                    Throwable t = unreadable.get(topic);
                    if (t instanceof Error e) {
                        throw e;
                    }
                    if (t != null) {
                        throw (RuntimeException) t;
                    }
                    return super.partitionsFor(topic, timeout);
                }

                private void sync() {
                    Map<TopicPartition, Long> end = new HashMap<>();
                    log.forEach((tp, records) -> end.put(tp, (long) records.size()));
                    updateEndOffsets(end);
                    for (TopicPartition tp : assignment()) {
                        List<byte[]> records = log.get(tp);
                        for (int i = handed.getOrDefault(tp, 0); i < records.size(); i++) {
                            addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), i, new byte[0], records.get(i)));
                        }
                        handed.put(tp, records.size());
                    }
                }

                @Override
                public synchronized void assign(Collection<TopicPartition> partitions) {
                    super.assign(partitions);
                    handed.clear();         // records queued for an earlier assignment are gone
                }

                @Override
                public synchronized Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
                    sync();
                    return super.endOffsets(partitions, timeout);
                }

                @Override
                public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
                    sync();
                    return super.poll(timeout);
                }
            };
            Node node = new Node(0, "localhost", 9092);
            Map<TopicPartition, Long> begin = new HashMap<>();
            Map<String, List<PartitionInfo>> infos = new HashMap<>();
            for (TopicPartition tp : log.keySet()) {
                begin.put(tp, logStart.getOrDefault(tp, 0L));
                infos.computeIfAbsent(tp.topic(), t -> new ArrayList<>())
                        .add(new PartitionInfo(tp.topic(), tp.partition(), node, new Node[]{node}, new Node[]{node}));
            }
            infos.forEach(c::updatePartitions);
            c.updateBeginningOffsets(begin);
            return c;
        }
    }

    FakeBroker broker;
    /** Every producer the processor asked for, in the order it asked: one per runner and one for the status. */
    final List<MockProducer<String, byte[]>> producers = new ArrayList<>();
    /** How many of the next producers do not confirm their sends on their own (a broker that does not answer). */
    int silentProducers;
    final OpenTsxAvro avro = new OpenTsxAvro();

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        producers.clear();
        silentProducers = 0;
    }

    MockProducer<String, byte[]> newProducer() {
        MockProducer<String, byte[]> m = new MockProducer<>(silentProducers-- <= 0, new StringSerializer(), new ByteArraySerializer());
        producers.add(m);
        return m;
    }

    /** Ten minutes of both series at 1 Hz, number {@code block}; the speed on partition 0, the power on partition 1. */
    void appendBlock(int block) {
        double[] speed = new double[600];
        double[] power = new double[600];
        for (int i = 0; i < 600; i++) {
            speed[i] = 5 + block;
            power[i] = 100 * (block + 1);
        }
        long start = T0 + block * 600 * SECOND;
        broker.append(LAYOUT.episodes(), 0, avro.encode(Episodes.regular(SPEED, start, SECOND, speed).source("t").createdAt(T0).build()));
        broker.append(LAYOUT.episodes(), 1, avro.encode(Episodes.regular(POWER, start, SECOND, power).source("t").createdAt(T0).build()));
    }

    public static String dag(String series, String extraNode, String in) {
        return """
                dag: wind-exp2-smart
                version: 1
                source:
                  kind: kafscale-broker
                  topics: [tsx.wind.episodes.v2]
                  series: [%s]
                nodes:
                  - id: decode
                    op: decode.avro-soe
                %s  - id: n1
                    op: align.vector
                    in: [%s]
                    params: { every: PT60S, key: turbine, agg: MEAN, emit: any, names: [state] }
                sinks:
                  - { from: n1, topic: tsx.wind.smart.turbine-state.vectors.v1, schema: opentsx.vector.v1 }
                """.formatted(series, extraNode, in);
    }

    public static String goodDag() {
        return dag("\"" + SPEED.getSeriesId() + "\", \"" + POWER.getSeriesId() + "\"", "", "decode");
    }

    void deploy(String spec) {
        broker.append(LAYOUT.dags(), 0, VectorProcessor.deployRecord(VectorProcessor.dagName(spec), spec, "test"));
    }

    VectorProcessor processor(Path state) {
        return processor(state, FAST);
    }

    VectorProcessor processor(Path state, VectorProcessor.Timing timing) {
        return new VectorProcessor(broker::consumer, this::newProducer, LAYOUT, state, timing);
    }

    static void steps(VectorProcessor p, int n) {
        for (int i = 0; i < n; i++) {
            p.step();
        }
    }

    /** What the processor sent to a topic, over all its producers. */
    List<JsonNode> written(String topic) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (MockProducer<String, byte[]> from : producers) {
            for (ProducerRecord<String, byte[]> r : from.history()) {
                if (r.topic().equals(topic)) {
                    out.add(JSON.readTree(r.value()));
                }
            }
        }
        return out;
    }

    List<JsonNode> vectors() throws Exception {
        return written("tsx.wind.smart.turbine-state.vectors.v1");
    }

    /**
     * The newest status of a sink ("dag/vector"), or of a DAG the processor refused ("dag"). All status records go
     * through one producer after the other, so the last one found is the newest.
     */
    JsonNode lastStatus(String key) throws Exception {
        JsonNode last = null;
        for (MockProducer<String, byte[]> from : producers) {
            for (ProducerRecord<String, byte[]> r : from.history()) {
                if (r.topic().equals(LAYOUT.dagStatus()) && r.key().equals(key)) {
                    last = JSON.readTree(r.value());
                }
            }
        }
        return last;
    }

    JsonNode lastStatus() throws Exception {
        return lastStatus("wind-exp2-smart/turbine-state");
    }

    /** The lines the loggers write while {@code action} runs; slf4j-simple writes to System.err. */
    static List<String> logged(Runnable action) {
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

    /** The messages the processor logged at this level, without thread, level and logger. */
    static List<String> messages(List<String> lines, String level) {
        String prefix = " " + level + " " + VectorProcessor.class.getName() + " - ";
        return lines.stream().filter(l -> l.contains(prefix)).map(l -> l.substring(l.indexOf(prefix) + prefix.length())).toList();
    }

    static final String NPE = "Cannot invoke \"String.length()\" because \"topic\" is null";

    @Test
    void aSinkThatThrowsLogsOneErrorWithTheCauseChainAndNoStackTrace(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        long[] now = {0};
        VectorProcessor.Timing timing = new VectorProcessor.Timing(Duration.ZERO, Duration.ofHours(1), Duration.ZERO,
                Duration.ofSeconds(60), Duration.ofMillis(1), Duration.ofSeconds(30));
        try (VectorProcessor p = new VectorProcessor(broker::consumer, this::newProducer, LAYOUT, state, timing, () -> now[0])) {
            p.sinkProvisioner(topic -> {
                throw new NullPointerException(NPE);
            });
            List<String> lines = logged(() -> steps(p, 3));
            assertEquals(List.of("sink failed; the next try is in PT1M dag=wind-exp2-smart sink=turbine-state"
                    + " cause=java.lang.NullPointerException: " + NPE), messages(lines, "ERROR"), String.join("\n", lines));
            assertFalse(lines.stream().anyMatch(l -> l.startsWith("\tat ")), "the stack trace is logged at DEBUG only");
            assertEquals("failed", lastStatus().path("state").asText());
            assertEquals(NPE, lastStatus().path("error").asText());

            now[0] += Duration.ofSeconds(60).toNanos();
            assertEquals(1, messages(logged(() -> steps(p, 1)), "ERROR").size(), "every failed try is one line");
        }
    }

    @Test
    void atLevelDebugTheStackTraceOfASinkFailureIsLoggedOncePerDistinctFailure(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path"), FailingSinkMain.class.getName(), dir.resolve("state").toString())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        pb.environment().put("OPENTSX_LOG_LEVEL", "debug");
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 60 s\nstderr: " + Files.readString(err));
        }
        assertEquals(0, p.exitValue(), Files.readString(err));
        List<String> lines = Files.readAllLines(err);
        assertEquals(3, messages(lines, "ERROR").size(), String.join("\n", lines));
        assertEquals(1, lines.stream().filter(l -> l.equals("java.lang.NullPointerException: " + NPE)).count(),
                "one stack trace for three failures that are the same:\n" + String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("\tat ")), String.join("\n", lines));
    }

    @Test
    void aCheckpointBehindTheLogStartCountsTheGapAndLogsItPerPartition(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
        }
        assertEquals(0, lastStatus().path("lostToRetention").asLong());
        // the processor was down: three blocks arrived, and retention deleted the first two of them
        appendBlock(1);
        appendBlock(2);
        appendBlock(3);
        broker.logStart.put(new TopicPartition(LAYOUT.episodes(), 0), 3L);
        broker.logStart.put(new TopicPartition(LAYOUT.episodes(), 1), 3L);
        List<String> lines;
        try (VectorProcessor p = processor(state)) {
            lines = logged(() -> steps(p, 5));
        }
        assertEquals(4, lastStatus().path("lostToRetention").asLong(), lastStatus().toString());
        assertEquals(List.of(
                "retention deleted records before the sink read them; reading on from the log start dag=wind-exp2-smart"
                        + " sink=turbine-state topic=tsx.wind.episodes.v2 partition=0 saved=1 logStart=3 lost=2",
                "retention deleted records before the sink read them; reading on from the log start dag=wind-exp2-smart"
                        + " sink=turbine-state topic=tsx.wind.episodes.v2 partition=1 saved=1 logStart=3 lost=2"),
                messages(lines, "WARN"), String.join("\n", lines));

        // the count is kept in the checkpoint, so a later restart still reports it
        try (VectorProcessor p = processor(state)) {
            steps(p, 3);
        }
        assertEquals(4, lastStatus().path("lostToRetention").asLong());
    }

    @Test
    void recordsOfAnotherSchemaOnTheControlTopicAreCountedInTheStatusAndTheFirstIsLogged(@TempDir Path state) throws Exception {
        appendBlock(0);
        broker.append(LAYOUT.dags(), 0, "{\"schema\":\"opentsx.dag.v1\",\"dag\":\"wind-exp2-smart\"}".getBytes(StandardCharsets.UTF_8));
        broker.append(LAYOUT.dags(), 0, "not json".getBytes(StandardCharsets.UTF_8));
        deploy(goodDag());
        List<String> lines;
        try (VectorProcessor p = processor(state)) {
            lines = logged(() -> steps(p, 5));
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "running"), p.states());
        }
        assertEquals(2, lastStatus().path("ignoredControlRecords").asLong(), lastStatus().toString());
        assertEquals(List.of("control record ignored and counted in ignoredControlRecords; later ones are only counted"
                + " topic=tsx.wind.dags.v1 partition=0 offset=0"), messages(lines, "WARN"), String.join("\n", lines));
    }

    @Test
    void aRecordWithoutAValueOnTheControlTopicIsCountedAndTheProcessorGoesOn(@TempDir Path state) throws Exception {
        appendBlock(0);
        broker.append(LAYOUT.dags(), 0, null);
        deploy(goodDag());
        List<String> lines;
        try (VectorProcessor p = processor(state)) {
            lines = logged(() -> steps(p, 5));
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "running"), p.states());
        }
        assertEquals(1, lastStatus().path("ignoredControlRecords").asLong(), lastStatus().toString());
        assertEquals(List.of("control record ignored and counted in ignoredControlRecords; later ones are only counted"
                + " topic=tsx.wind.dags.v1 partition=0 offset=0"), messages(lines, "WARN"), String.join("\n", lines));
    }

    @Test
    void theFirstUndecodableEpisodeOfAPartitionIsLoggedAndTheNextOnlyCounted(@TempDir Path state) throws Exception {
        broker.append(LAYOUT.episodes(), 0, "not avro".getBytes(StandardCharsets.UTF_8));
        appendBlock(0);
        broker.append(LAYOUT.episodes(), 0, "not avro either".getBytes(StandardCharsets.UTF_8));
        deploy(goodDag());
        List<String> lines;
        try (VectorProcessor p = processor(state)) {
            lines = logged(() -> steps(p, 5));
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "running"), p.states());
        }
        assertEquals(2, lastStatus().path("undecodable").asLong(), lastStatus().toString());
        List<String> warned = messages(lines, "WARN");
        assertEquals(1, warned.size(), String.join("\n", lines));
        assertTrue(warned.get(0).startsWith("undecodable record skipped and counted in undecodable; later ones of this partition"
                + " are only counted dag=wind-exp2-smart sink=turbine-state topic=tsx.wind.episodes.v2 partition=0 offset=0"
                + " cause="), warned.get(0));
    }

    @Test
    void theFirstStatusRecordThatIsNotSentIsLoggedAndSoIsTheRecovery(@TempDir Path state) throws Exception {
        // a refused DAG is reported at once through the first producer the processor asks for, which does not confirm
        silentProducers = 1;
        String refused = dag("\"" + SPEED.getSeriesId() + "\"", "  - id: n0\n    op: norm.zscore\n    in: [decode]\n", "n0");
        deploy(refused);
        try (VectorProcessor p = processor(state)) {
            steps(p, 1);
            List<String> failed = logged(() -> producers.get(0).errorNext(new org.apache.kafka.common.errors.TimeoutException(
                    "Expiring 1 record(s) for tsx.wind.dag-status.v1-0")));
            assertEquals(List.of("status record not sent; the next one goes through a new producer topic=tsx.wind.dag-status.v1"
                    + " key=wind-exp2-smart cause=org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s) for"
                    + " tsx.wind.dag-status.v1-0"), messages(failed, "WARN"), String.join("\n", failed));

            deploy(refused.replace("dag: wind-exp2-smart", "dag: wind-other"));
            List<String> recovered = logged(() -> steps(p, 1));
            assertEquals(List.of("status records are sent again topic=tsx.wind.dag-status.v1"), messages(recovered, "INFO"),
                    String.join("\n", recovered));
            assertTrue(producers.get(0).closed(), "the producer whose send failed is given up");
        }
    }

    @Test
    void closingWritesTheFinalCheckpointOfASinkAndLogsIt(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        VectorProcessor.Timing seldom = new VectorProcessor.Timing(Duration.ofHours(1), Duration.ofHours(1), Duration.ZERO,
                Duration.ZERO, Duration.ofMillis(1), Duration.ofSeconds(30));
        VectorProcessor p = processor(state, seldom);
        steps(p, 5);
        assertFalse(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")), "no checkpoint before the end");
        List<String> lines = logged(p::close);
        assertEquals(List.of("final checkpoint written dag=wind-exp2-smart sink=turbine-state records=10"
                        + " positions=tsx.wind.episodes.v2-0:1,tsx.wind.episodes.v2-1:1"), messages(lines, "INFO"),
                String.join("\n", lines));
        assertTrue(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")));
    }

    @Test
    void aDeployedDagIsRunAndFollowsTheTopic(@TempDir Path state) throws Exception {
        appendBlock(0);
        appendBlock(1);
        deploy(goodDag());
        List<String> provisioned = new ArrayList<>();
        try (VectorProcessor p = processor(state)) {
            p.sinkProvisioner(provisioned::add);
            steps(p, 5);
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "running"), p.states());
            assertEquals(List.of("tsx.wind.smart.turbine-state.vectors.v1"), provisioned, "the sink topic is asked for once, before the first write");
            List<JsonNode> first = vectors();
            assertEquals(20, first.size(), "twenty minutes, one measuring point per minute");
            assertEquals("opentsx.vector.v1", first.get(0).path("schema").asText());
            assertEquals("{\"turbine\":\"T01\"}", first.get(0).path("key").toString());
            assertEquals("[\"power_active\",\"wind_speed\"]", first.get(0).path("components").toString());
            assertEquals("[100.0,5.0]", first.get(0).path("values").toString());
            assertEquals("[200.0,6.0]", first.get(19).path("values").toString());
            assertEquals("2026-10-01T00:20:00Z", first.get(19).path("ts").asText());
            assertEquals("turbine=T01", producers.stream().flatMap(m -> m.history().stream())
                    .filter(r -> r.topic().endsWith(".vectors.v1")).findFirst().orElseThrow().key());

            // more data arrives: only the new ticks are written
            appendBlock(2);
            steps(p, 5);
            List<JsonNode> all = vectors();
            assertEquals(30, all.size());
            assertEquals("[300.0,7.0]", all.get(29).path("values").toString());

            JsonNode status = lastStatus();
            assertEquals("running", status.path("state").asText());
            assertEquals("tsx.wind.smart.turbine-state.vectors.v1", status.path("topic").asText());
            assertEquals(30, status.path("records").asLong(), "the report follows what was written");
            assertEquals("2026-10-01T00:30:00Z", status.path("watermark").asText());
        }
    }

    @Test
    void aSecondDagThatWritesTheVectorTopicOfARunningOneIsRefusedNamingBoth(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        String copy = goodDag().replace("dag: wind-exp2-smart", "dag: wind-copy");
        deploy(copy);
        try (VectorProcessor p = processor(state)) {
            List<String> lines = logged(() -> steps(p, 3));
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "running"), p.states());
            String refusal = "wind-copy: sink topic tsx.wind.smart.turbine-state.vectors.v1 is already written by DAG"
                    + " wind-exp2-smart; stop that DAG first or choose another sink";
            assertEquals("failed", lastStatus("wind-copy").path("state").asText());
            assertEquals(refusal, lastStatus("wind-copy").path("error").asText());
            assertTrue(messages(lines, "WARN").contains("dag refused dag=wind-copy cause=java.lang.IllegalArgumentException: " + refusal),
                    String.join("\n", lines));

            // the same DAG deployed again under its own name keeps its topic
            deploy(goodDag().replace("every: PT60S", "every: PT30S"));
            steps(p, 3);
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "running"), p.states());

            // once the first is stopped, the copy may write the topic
            broker.append(LAYOUT.dags(), 0, VectorProcessor.stopRecord("wind-exp2-smart", "test"));
            deploy(copy);
            steps(p, 3);
            assertEquals(Map.of("wind-copy/turbine-state", "running"), p.states());
        }
    }

    @Test
    void waitsUntilEverySeriesWasSeenAndClosesATickOnlyWhenAllPassedIt(@TempDir Path state) throws Exception {
        // only the speed has arrived
        double[] speed = new double[600];
        broker.append(LAYOUT.episodes(), 0, avro.encode(Episodes.regular(SPEED, T0, SECOND, speed).source("t").createdAt(T0).build()));
        deploy(goodDag());
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "waiting"), p.states());
            assertEquals(0, vectors().size());
            JsonNode status = lastStatus();
            assertEquals("waiting", status.path("state").asText());
            assertEquals(POWER.getSeriesId(), status.path("waitingFor").get(0).asText());

            // five minutes of power: five ticks are final, although the speed is ten minutes ahead
            double[] power = new double[300];
            broker.append(LAYOUT.episodes(), 1, avro.encode(Episodes.regular(POWER, T0, SECOND, power).source("t").createdAt(T0).build()));
            steps(p, 5);
            assertEquals(5, vectors().size());
            assertEquals("running", lastStatus().path("state").asText());
        }
    }

    @Test
    void aRestartContinuesAtTheCheckpointWithoutWritingAgain(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
        }
        assertEquals(10, vectors().size());
        assertTrue(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")));

        // a new process: same control topic, same state directory
        appendBlock(1);
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
        }
        List<JsonNode> after = vectors().subList(10, vectors().size());
        assertEquals(10, after.size(), "only the ten new minutes");
        assertEquals("2026-10-01T00:11:00Z", after.get(0).path("ts").asText());
        assertEquals(20, lastStatus().path("records").asLong(), "the count goes on from the checkpoint");
    }

    @Test
    void aCheckpointThatCannotBeReadFailsTheSinkAndIsKept(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        Path checkpoint = state.resolve("wind-exp2-smart--turbine-state.json");
        String cut = "{\"dag\":\"wind-exp2-smart\",\"vector\":\"turbine-state\",\"specHash\":";
        Files.writeString(checkpoint, cut);
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "failed"), p.states());
            assertEquals(0, vectors().size(), "nothing is written without the positions of the checkpoint");
            JsonNode status = lastStatus();
            assertEquals("failed", status.path("state").asText());
            assertTrue(status.path("error").asText().startsWith("checkpoint " + checkpoint + ": cannot be read ("), status.toString());
            assertTrue(status.path("error").asText().endsWith("; repair it, or delete it to start the sink over"), status.toString());
        }
        assertEquals(cut, Files.readString(checkpoint), "the file is kept as it was");
    }

    @Test
    void anEmptyCheckpointFailsTheSinkAndIsKept(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        Path checkpoint = state.resolve("wind-exp2-smart--turbine-state.json");
        Files.writeString(checkpoint, "");
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(Map.of("wind-exp2-smart/turbine-state", "failed"), p.states());
            assertEquals("checkpoint " + checkpoint + ": cannot be read (it holds no JSON object); repair it, or delete it to start the sink over",
                    lastStatus().path("error").asText());
        }
        assertEquals("", Files.readString(checkpoint));
    }

    @Test
    void aFailureWhileApplyingTheControlTopicIsNotTakenForAnUnreadableTopic(@TempDir Path state) {
        deploy(dag("\"" + SPEED.getSeriesId() + "\"", "  - id: n0\n    op: norm.zscore\n    in: [decode]\n", "n0"));
        VectorProcessor p = new VectorProcessor(broker::consumer, () -> {
            throw new IllegalStateException("no producer for the status");
        }, LAYOUT, state, FAST);
        assertEquals("no producer for the status", assertThrows(IllegalStateException.class, p::step).getMessage());
    }

    @Test
    void aDagThatIsNotValidYamlNamesTheParserMessageAndKeepsTheCause() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VectorProcessor.dagName("dag: [a"));
        assertTrue(e.getMessage().startsWith("the DAG is not valid YAML: while parsing a flow sequence"), e.getMessage());
        assertTrue(e.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException, String.valueOf(e.getCause()));
    }

    @Test
    void aStoppedDagEndsAndAChangedOneStartsOver(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(10, vectors().size());

            broker.append(LAYOUT.dags(), 0, VectorProcessor.stopRecord("wind-exp2-smart", "test"));
            steps(p, 3);
            assertEquals(Map.of(), p.states());
            assertEquals("stopped", lastStatus().path("state").asText());
            assertFalse(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")), "the checkpoint goes with the DAG");
            appendBlock(1);
            steps(p, 3);
            assertEquals(10, vectors().size(), "a stopped DAG writes nothing");

            // deployed again with another clock: it starts from the beginning of the topic
            deploy(goodDag().replace("PT60S", "PT300S"));
            steps(p, 5);
            List<JsonNode> all = vectors();
            assertEquals(14, all.size(), "twenty minutes in ticks of five");
            assertEquals("PT300S", all.get(13).path("clock").asText());
        }
    }

    @Test
    void aSinkTheBrokerDoesNotConfirmHoldsUpNeitherTheOthersNorTheStatus(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        String other = goodDag().replace("dag: wind-exp2-smart", "dag: wind-other-smart").replace("turbine-state", "other-state");
        deploy(other);
        // the first producer the processor asks for belongs to the runner of wind-exp2-smart: it never gets an answer
        silentProducers = 1;
        VectorProcessor.Timing impatient = new VectorProcessor.Timing(Duration.ZERO, Duration.ofHours(1), Duration.ZERO,
                Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(150));
        long[] now = {0};
        try (VectorProcessor p = new VectorProcessor(broker::consumer, this::newProducer, LAYOUT, state, impatient, () -> now[0])) {
            steps(p, 5);
            // the other sink is written, confirmed and checkpointed although the first one waits
            assertEquals(10, written("tsx.wind.smart.other-state.vectors.v1").size());
            assertTrue(Files.exists(state.resolve("wind-other-smart--other-state.json")));
            assertEquals("running", lastStatus("wind-other-smart/other-state").path("state").asText());
            // the silent sink sent its measuring points but may not checkpoint them
            assertEquals(10, vectors().size());
            assertFalse(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")), "no checkpoint without confirmation");
            assertEquals("running", p.states().get("wind-exp2-smart/turbine-state"));

            now[0] += Duration.ofMillis(200).toNanos();
            steps(p, 1);
            JsonNode failed = lastStatus("wind-exp2-smart/turbine-state");
            assertEquals("failed", failed.path("state").asText(), "the status still gets out");
            assertTrue(failed.path("error").asText().contains("has not confirmed"), failed.toString());
            assertTrue(producers.get(0).closed(), "the producer that got no answer is given up");

            // the next try starts at the last checkpoint (there is none: from the beginning) with a new producer
            steps(p, 5);
            assertEquals("running", lastStatus("wind-exp2-smart/turbine-state").path("state").asText());
            assertEquals(20, vectors().size(), "written again; readers take the later measuring point");
            assertTrue(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")));
            assertEquals(10, written("tsx.wind.smart.other-state.vectors.v1").size(), "the other sink was not disturbed");
        }
    }

    @Test
    void aDagThisRuntimeCannotRunIsRefusedWithTheReason(@TempDir Path state) throws Exception {
        appendBlock(0);
        String viaOther = dag("\"" + SPEED.getSeriesId() + "\"", "  - id: n0\n    op: norm.zscore\n    in: [decode]\n", "n0");
        deploy(viaOther);
        try (VectorProcessor p = processor(state)) {
            steps(p, 3);
            assertEquals(Map.of(), p.states());
            JsonNode status = lastStatus("wind-exp2-smart");
            assertEquals("failed", status.path("state").asText());
            assertTrue(status.path("error").asText().contains("node n0 (norm.zscore) is not available"), status.toString());

            // without the series named there is no telling when a vector is complete
            deploy(dag("", "", "decode"));
            steps(p, 3);
            assertTrue(lastStatus("wind-exp2-smart").path("error").asText().contains("needs the ids of its series"));
            assertEquals(0, vectors().size());
        }
    }

    static final String CLEAN_SINK = "wind-flow-7/park-T01-WTUR.W";

    /** An opentsx.dag.v2 document as the workbench writes it for "Publish as clean signal". */
    static String cleanDag(String op, String extraSink) {
        return """
                schema: opentsx.dag.v2
                dag: wind-flow-7
                version: 1
                source:
                  kind: kafscale-broker
                  topics: [tsx.wind.episodes.v2]
                  series: ["%s"]
                nodes:
                  - id: decode
                    op: decode.avro-soe
                  - id: n1
                    op: %s
                    in: [decode]
                    params: { checks: [PLAUSIBLE_RANGE], min: 0, max: 150, mode: gap }
                  - id: n2
                    op: resample
                    in: [n1]
                    params: { every: PT1M, agg: MEAN }
                sinks:
                  - { from: n2, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: park/T01/WTUR.W, grade: clean }, origin: SYNTHETIC }
                %s""".formatted(POWER.getSeriesId(), op, extraSink);
    }

    List<org.opentsx.model.v2.Episode> cleanEpisodes() {
        List<org.opentsx.model.v2.Episode> out = new ArrayList<>();
        for (MockProducer<String, byte[]> from : producers) {
            for (ProducerRecord<String, byte[]> r : from.history()) {
                if (r.topic().equals(LAYOUT.clean())) {
                    out.add(avro.decode(r.value(), org.opentsx.model.v2.Episode.class));
                }
            }
        }
        return out;
    }

    @Test
    void aSeriesSinkWritesTheCleanGradeOfASignalAndGoesOnAfterARestart(@TempDir Path state) throws Exception {
        appendBlock(0);     // power 100: inside the range
        appendBlock(1);     // power 200: outside, so every minute is a gap
        deploy(cleanDag("dq.checks", ""));
        List<String> provisioned = new ArrayList<>();
        try (VectorProcessor p = processor(state)) {
            p.sinkProvisioner(provisioned::add);
            steps(p, 5);
            assertEquals(Map.of(CLEAN_SINK, "running"), p.states());
            assertEquals(List.of("tsx.wind.clean.v2"), provisioned);
            List<org.opentsx.model.v2.Episode> clean = cleanEpisodes();
            assertEquals(2, clean.size(), "one clean episode per raw episode");
            org.opentsx.model.v2.Episode first = clean.get(0);
            assertEquals("wind.turbine.power_active", first.getSeries().getMetric());
            assertEquals("h2-chain", first.getSeries().getTags().get("system"));
            assertEquals("park/T01/WTUR.W", first.getSeries().getTags().get("signal"));
            assertEquals("T01", first.getSeries().getTags().get("turbine"));
            assertEquals("clean", first.getLabels().get("grade"));
            assertEquals("SYNTHETIC", first.getLabels().get("origin"));
            assertEquals(10, first.getCount());
            assertEquals(60 * SECOND, first.getIntervalMicros());
            assertEquals(100.0, Episodes.values(first)[0]);
            assertTrue(Double.isNaN(Episodes.values(clean.get(1))[0]), "a minute of implausible values is a gap");
            JsonNode status = lastStatus(CLEAN_SINK);
            assertEquals("series", status.path("kind").asText());
            assertEquals(LAYOUT.clean(), status.path("topic").asText());
            assertEquals(2, status.path("records").asInt());
        }
        appendBlock(2);
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(3, cleanEpisodes().size(), "after the restart only the new block is written");
            assertEquals(T0 + 1200 * SECOND, org.opentsx.model.Micros.of(cleanEpisodes().get(2).getTStart()));
        }
    }

    @Test
    void aDagMayCarryAVectorAndACleanSeriesAndWhatCannotRunIsRefused(@TempDir Path state) throws Exception {
        appendBlock(0);
        String both = cleanDag("dq.checks", """
                  - { from: v1, topic: tsx.wind.smart.turbine-state.vectors.v1, schema: opentsx.vector.v1 }
                """).replace("sinks:", """
                  - id: v1
                    op: align.vector
                    in: [decode]
                    params: { every: PT60S, key: turbine, agg: MEAN, emit: any, names: [state] }
                sinks:""");
        deploy(both);
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(Map.of(CLEAN_SINK, "running", "wind-flow-7/turbine-state", "running"), p.states());
            assertEquals(1, cleanEpisodes().size());
            assertEquals(10, vectors().size());

            // an operator the runtime does not run before a series sink: the whole document is refused, with the node
            deploy(cleanDag("norm.zscore", ""));
            steps(p, 3);
            assertEquals(Map.of(), p.states());
            assertTrue(lastStatus("wind-flow-7").path("error").asText().contains("node n1 (norm.zscore) is not available"),
                    lastStatus("wind-flow-7").toString());
        }
    }

    @Test
    void aSinkMayReadAGeneratedSeriesAsTheRawGradeOfItsSignal(@TempDir Path state) throws Exception {
        // what a demo generator wrote for an open signal lives in the topic of the generated series
        SeriesKey raw = SeriesKeys.of("h2-chain.offtake.demand_kgph", Map.of("system", "h2-chain", "signal", "offtake.demand_kgph", "grade", "raw"), "kg/h");
        broker.log.put(new TopicPartition(LAYOUT.synthetic(), 0), new ArrayList<>());
        broker.append(LAYOUT.synthetic(), 0, avro.encode(Episodes.regular(raw, T0, 60 * SECOND, new double[]{80, 90, 500, 85}).source("gen.sine").createdAt(T0).build()));
        deploy("""
                schema: opentsx.dag.v2
                dag: wind-flow-11
                version: 1
                source:
                  kind: kafscale-broker
                  topics: [tsx.wind.synthetic.v2]
                  series: ["%s"]
                nodes:
                  - id: decode
                    op: decode.avro-soe
                  - id: n1
                    op: dq.checks
                    in: [decode]
                    params: { checks: [PLAUSIBLE_RANGE], min: 0, max: 120, mode: gap }
                sinks:
                  - { from: n1, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: offtake.demand_kgph, grade: clean }, origin: SYNTHETIC }
                """.formatted(raw.getSeriesId()));
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(Map.of("wind-flow-11/offtake.demand_kgph", "running"), p.states());
        }
        org.opentsx.model.v2.Episode clean = null;
        for (MockProducer<String, byte[]> from : producers) {
            for (ProducerRecord<String, byte[]> r : from.history()) {
                clean = r.topic().equals(LAYOUT.clean()) ? avro.decode(r.value(), org.opentsx.model.v2.Episode.class) : clean;
            }
        }
        assertEquals("clean", clean.getSeries().getTags().get("grade"));
        assertEquals("SYNTHETIC", clean.getLabels().get("origin"), "cleaning does not make a generated series a measurement");
        double[] v = Episodes.values(clean);
        assertEquals(80.0, v[0]);
        assertTrue(Double.isNaN(v[2]), "the value outside the plausible range is a gap");
    }

    @Test
    void aSinkMayReadCleanSeriesAndWriteAnEstimateOfAnotherSignalAndFeatures(@TempDir Path state) throws Exception {
        // the clean wind speed of one turbine, as a flow before this one wrote it: ten minutes at 8 m/s
        SeriesKey clean = SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01", "system", "h2-chain",
                "signal", "park/T01/WMET.HorWdSpd", "grade", "clean"), "m/s");
        broker.log.put(new TopicPartition(LAYOUT.clean(), 0), new ArrayList<>());
        double[] wind = new double[10];
        java.util.Arrays.fill(wind, 8);
        broker.append(LAYOUT.clean(), 0, avro.encode(Episodes.regular(clean, T0, 60 * SECOND, wind).source("t").createdAt(T0).build()));
        deploy("""
                schema: opentsx.dag.v2
                dag: wind-flow-9
                version: 1
                source:
                  kind: kafscale-broker
                  topics: [tsx.wind.clean.v2]
                  series: ["%s"]
                nodes:
                  - id: decode
                    op: decode.avro-soe
                  - id: n1
                    op: model.power-curve
                    in: [decode]
                    params: { ratedKw: 3000, cutIn: 3, ratedWind: 12.5, cutOut: 25, count: 4 }
                  - id: lag_1
                    op: feature.lag
                    in: [decode]
                    params: { steps: 1 }
                sinks:
                  - { from: n1, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: park.p_potential_kw, grade: clean, metric: h2-chain.park.p_potential_kw }, origin: ESTIMATED, madeOf: "park/T01/WMET.HorWdSpd@clean (SYNTHETIC)" }
                  - { from: lag_1, topic: tsx.wind.features.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: park/T01/WMET.HorWdSpd, grade: features } }
                """.formatted(clean.getSeriesId()));
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(Map.of("wind-flow-9/park.p_potential_kw", "running", "wind-flow-9/park-T01-WMET.HorWdSpd--lag_1", "running"), p.states());
        }
        org.opentsx.model.v2.Episode estimate = null;
        org.opentsx.model.v2.Episode feature = null;
        for (MockProducer<String, byte[]> from : producers) {
            for (ProducerRecord<String, byte[]> r : from.history()) {
                if (r.topic().equals(LAYOUT.clean())) {
                    estimate = avro.decode(r.value(), org.opentsx.model.v2.Episode.class);
                } else if (r.topic().equals(LAYOUT.features())) {
                    feature = avro.decode(r.value(), org.opentsx.model.v2.Episode.class);
                }
            }
        }
        // the estimate is a series of its own: the metric of the port, no tags of the sensor it was made of
        assertEquals("h2-chain.park.p_potential_kw", estimate.getSeries().getMetric());
        assertEquals("{flow=wind-flow-9, grade=clean, signal=park.p_potential_kw, system=h2-chain}", new TreeMap<>(estimate.getSeries().getTags()).toString());
        assertEquals("kW", estimate.getSeries().getUnit());
        assertEquals("ESTIMATED", estimate.getLabels().get("origin"));
        assertEquals("park/T01/WMET.HorWdSpd@clean (SYNTHETIC)", estimate.getLabels().get("madeOf"));
        assertEquals(4 * 3000 * (512.0 - 27) / (1953.125 - 27), Episodes.values(estimate)[0], 1e-9);
        // the feature keeps the quantity of its signal and is named after its node
        assertEquals("wind.met.wind_speed", feature.getSeries().getMetric());
        assertEquals("lag_1", feature.getSeries().getTags().get("feature"));
        assertEquals("features", feature.getSeries().getTags().get("grade"));
        assertTrue(Double.isNaN(Episodes.values(feature)[0]));
        assertEquals(8.0, Episodes.values(feature)[1]);
    }

    @Test
    void featuresAreComputedFromCleanSeriesOnly(@TempDir Path state) throws Exception {
        deploy(cleanDag("dq.checks", "").replace("topic: tsx.wind.clean.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: park/T01/WTUR.W, grade: clean }",
                "topic: tsx.wind.features.v2, schema: opentsx.episode.v2, signal: { system: h2-chain, id: park/T01/WTUR.W, grade: features }"));
        try (VectorProcessor p = processor(state)) {
            steps(p, 3);
            assertEquals(Map.of(), p.states());
            assertTrue(lastStatus("wind-flow-7").path("error").asText().contains("the node of a feature gives it its name")
                    || lastStatus("wind-flow-7").path("error").asText().contains("features are computed from clean series"), lastStatus("wind-flow-7").toString());
        }
    }
}
