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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorProcessorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long T0 = 1_790_812_800_000_000L;      // 2026-10-01T00:00:00Z
    static final long SECOND = 1_000_000L;
    static final TopicLayout LAYOUT = new TopicLayout("wind");
    static final SeriesKey SPEED = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p", "turbine", "T01"), "m/s");
    static final SeriesKey POWER = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "p", "turbine", "T01"), "kW");
    static final VectorProcessor.Timing FAST = new VectorProcessor.Timing(Duration.ZERO, Duration.ofHours(1), Duration.ZERO,
            Duration.ZERO, Duration.ofMillis(1), Duration.ofSeconds(30));

    /** Topics in memory. Every consumer it hands out sees what is appended, also after it was created. */
    static final class FakeBroker {
        final Map<TopicPartition, List<byte[]>> log = new TreeMap<>((a, b) -> a.topic().equals(b.topic())
                ? Integer.compare(a.partition(), b.partition()) : a.topic().compareTo(b.topic()));

        FakeBroker() {
            log.put(new TopicPartition(LAYOUT.dags(), 0), new ArrayList<>());
            log.put(new TopicPartition(LAYOUT.episodes(), 0), new ArrayList<>());
            log.put(new TopicPartition(LAYOUT.episodes(), 1), new ArrayList<>());
        }

        void append(String topic, int partition, byte[] value) {
            log.get(new TopicPartition(topic, partition)).add(value);
        }

        Consumer<byte[], byte[]> consumer() {
            Map<TopicPartition, Integer> handed = new HashMap<>();
            MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
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
                begin.put(tp, 0L);
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

    static String dag(String series, String extraNode, String in) {
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

    static String goodDag() {
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
            assertEquals(List.of("turbine-state"), provisioned, "the sink topic is asked for once, before the first write");
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
        try (VectorProcessor p = processor(state, impatient)) {
            steps(p, 5);
            // the other sink is written, confirmed and checkpointed although the first one waits
            assertEquals(10, written("tsx.wind.smart.other-state.vectors.v1").size());
            assertTrue(Files.exists(state.resolve("wind-other-smart--other-state.json")));
            assertEquals("running", lastStatus("wind-other-smart/other-state").path("state").asText());
            // the silent sink sent its measuring points but may not checkpoint them
            assertEquals(10, vectors().size());
            assertFalse(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")), "no checkpoint without confirmation");
            assertEquals("running", p.states().get("wind-exp2-smart/turbine-state"));

            Thread.sleep(200);
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
}
