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
            Duration.ZERO, Duration.ofMillis(1));

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
    MockProducer<String, byte[]> producer;
    final OpenTsxAvro avro = new OpenTsxAvro();

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
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
        return new VectorProcessor(broker::consumer, producer, LAYOUT, state, FAST);
    }

    static void steps(VectorProcessor p, int n) {
        for (int i = 0; i < n; i++) {
            p.step();
        }
    }

    List<JsonNode> written(MockProducer<String, byte[]> from, String topic) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (ProducerRecord<String, byte[]> r : from.history()) {
            if (r.topic().equals(topic)) {
                out.add(JSON.readTree(r.value()));
            }
        }
        return out;
    }

    List<JsonNode> vectors(MockProducer<String, byte[]> from) throws Exception {
        return written(from, "tsx.wind.smart.turbine-state.vectors.v1");
    }

    JsonNode lastStatus(MockProducer<String, byte[]> from) throws Exception {
        List<JsonNode> all = written(from, LAYOUT.dagStatus());
        return all.get(all.size() - 1);
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
            List<JsonNode> first = vectors(producer);
            assertEquals(20, first.size(), "twenty minutes, one measuring point per minute");
            assertEquals("opentsx.vector.v1", first.get(0).path("schema").asText());
            assertEquals("{\"turbine\":\"T01\"}", first.get(0).path("key").toString());
            assertEquals("[\"power_active\",\"wind_speed\"]", first.get(0).path("components").toString());
            assertEquals("[100.0,5.0]", first.get(0).path("values").toString());
            assertEquals("[200.0,6.0]", first.get(19).path("values").toString());
            assertEquals("2026-10-01T00:20:00Z", first.get(19).path("ts").asText());
            assertEquals("turbine=T01", producer.history().stream().filter(r -> r.topic().endsWith(".vectors.v1")).findFirst()
                    .orElseThrow().key());

            // more data arrives: only the new ticks are written
            appendBlock(2);
            steps(p, 5);
            List<JsonNode> all = vectors(producer);
            assertEquals(30, all.size());
            assertEquals("[300.0,7.0]", all.get(29).path("values").toString());

            JsonNode status = lastStatus(producer);
            assertEquals("running", status.path("state").asText());
            assertEquals("tsx.wind.smart.turbine-state.vectors.v1", status.path("topic").asText());
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
            assertEquals(0, vectors(producer).size());
            JsonNode status = lastStatus(producer);
            assertEquals("waiting", status.path("state").asText());
            assertEquals(POWER.getSeriesId(), status.path("waitingFor").get(0).asText());

            // five minutes of power: five ticks are final, although the speed is ten minutes ahead
            double[] power = new double[300];
            broker.append(LAYOUT.episodes(), 1, avro.encode(Episodes.regular(POWER, T0, SECOND, power).source("t").createdAt(T0).build()));
            steps(p, 5);
            assertEquals(5, vectors(producer).size());
            assertEquals("running", lastStatus(producer).path("state").asText());
        }
    }

    @Test
    void aRestartContinuesAtTheCheckpointWithoutWritingAgain(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
        }
        assertEquals(10, vectors(producer).size());
        assertTrue(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")));

        // a new process: same control topic, same state directory
        MockProducer<String, byte[]> second = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        appendBlock(1);
        try (VectorProcessor p = new VectorProcessor(broker::consumer, second, LAYOUT, state, FAST)) {
            steps(p, 5);
        }
        List<JsonNode> after = vectors(second);
        assertEquals(10, after.size(), "only the ten new minutes");
        assertEquals("2026-10-01T00:11:00Z", after.get(0).path("ts").asText());
        List<JsonNode> statuses = written(second, LAYOUT.dagStatus());
        assertEquals(20, statuses.get(statuses.size() - 1).path("records").asLong(), "the count goes on from the checkpoint");
    }

    @Test
    void aStoppedDagEndsAndAChangedOneStartsOver(@TempDir Path state) throws Exception {
        appendBlock(0);
        deploy(goodDag());
        try (VectorProcessor p = processor(state)) {
            steps(p, 5);
            assertEquals(10, vectors(producer).size());

            broker.append(LAYOUT.dags(), 0, VectorProcessor.stopRecord("wind-exp2-smart", "test"));
            steps(p, 3);
            assertEquals(Map.of(), p.states());
            assertEquals("stopped", lastStatus(producer).path("state").asText());
            assertFalse(Files.exists(state.resolve("wind-exp2-smart--turbine-state.json")), "the checkpoint goes with the DAG");
            appendBlock(1);
            steps(p, 3);
            assertEquals(10, vectors(producer).size(), "a stopped DAG writes nothing");

            // deployed again with another clock: it starts from the beginning of the topic
            deploy(goodDag().replace("PT60S", "PT300S"));
            steps(p, 5);
            List<JsonNode> all = vectors(producer);
            assertEquals(14, all.size(), "twenty minutes in ticks of five");
            assertEquals("PT300S", all.get(13).path("clock").asText());
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
            JsonNode status = lastStatus(producer);
            assertEquals("failed", status.path("state").asText());
            assertTrue(status.path("error").asText().contains("node n0 (norm.zscore) is not available"), status.toString());

            // without the series named there is no telling when a vector is complete
            deploy(dag("", "", "decode"));
            steps(p, 3);
            assertTrue(lastStatus(producer).path("error").asText().contains("needs the ids of its series"));
            assertEquals(0, vectors(producer).size());
        }
    }
}
