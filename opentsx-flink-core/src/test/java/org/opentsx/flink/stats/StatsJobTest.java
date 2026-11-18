package org.opentsx.flink.stats;

import org.apache.flink.api.common.functions.util.ListCollector;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.groups.UnregisteredMetricsGroup;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.metrics.groups.InternalOperatorMetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.apache.flink.runtime.metrics.util.InterceptingOperatorMetricGroup;
import org.apache.flink.runtime.operators.testutils.MockEnvironment;
import org.apache.flink.runtime.operators.testutils.MockEnvironmentBuilder;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.SimpleUserCodeClassLoader;
import org.apache.flink.util.UserCodeClassLoader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.SeriesStatsLog;
import org.opentsx.stats.StatsEngine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsJobTest {

    private static final EngineSpec SPEC = EngineSpec.defaults();

    @Test
    void flinkJobComputesWhatTheEngineComputes() throws Exception {
        List<Episode> episodes = StatsData.episodes("parity", Duration.ofHours(1), 2);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        DataStream<StatsInput> inputs = env.fromData(StatsData.inputs(episodes), TypeInformation.of(StatsInput.class));
        List<StatsRecord> records = new ArrayList<>();
        StatsJob.statistics(inputs, SPEC, Duration.ZERO).executeAndCollect().forEachRemaining(records::add);

        List<SeriesStats> expected = StatsData.direct(episodes, SPEC);
        assertTrue(expected.size() > 0);
        assertEquals(expected, StatsData.decode(records));
    }

    @Test
    void restoreFromACheckpointCountsEveryValueOnce() throws Exception {
        List<StatsInput> inputs = StatsData.inputs(StatsData.episodes("restore", Duration.ofHours(1), 2));
        int half = inputs.size() / 2;

        List<StatsRecord> uninterrupted;
        try (var h = harness()) {
            h.open();
            for (StatsInput in : inputs) {
                h.processElement(new StreamRecord<>(in));
            }
            h.processWatermark(Long.MAX_VALUE);
            uninterrupted = h.extractOutputValues();
        }

        List<StatsRecord> resumed = new ArrayList<>();
        OperatorSubtaskState checkpoint;
        try (var h = harness()) {
            h.open();
            for (StatsInput in : inputs.subList(0, half)) {
                h.processElement(new StreamRecord<>(in));
            }
            checkpoint = h.snapshot(1L, 0L);
            resumed.addAll(h.extractOutputValues());
        }
        try (var h = harness()) {
            h.initializeState(checkpoint);
            h.open();
            for (StatsInput in : inputs.subList(half, inputs.size())) {
                h.processElement(new StreamRecord<>(in));
            }
            h.processWatermark(Long.MAX_VALUE);
            resumed.addAll(h.extractOutputValues());
        }

        List<SeriesStats> expected = StatsData.decode(uninterrupted);
        assertEquals(expected, StatsData.sorted(SeriesStatsLog.latest(StatsData.decode(resumed))));
        List<SeriesStats> global = expected.stream().filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).toList();
        assertEquals(inputs.stream().map(StatsInput::getSeriesId).distinct().count(), global.size());
        global.forEach(g -> assertEquals(3600, g.getValidCount() + g.getNanCount(), "every value counted exactly once"));
    }

    @Test
    void globalTimerEmitsChangedSeriesOnly() throws Exception {
        List<StatsInput> inputs = StatsData.inputs(StatsData.episodes("global", Duration.ofMinutes(10), 1));
        try (var h = harness(60_000)) {
            h.open();
            h.setProcessingTime(0);
            h.processElement(new StreamRecord<>(inputs.get(0)));
            h.setProcessingTime(60_000);
            long globals = StatsData.decode(h.extractOutputValues()).stream()
                    .filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).count();
            assertEquals(1, globals, "one GLOBAL record after the first interval");
            h.setProcessingTime(180_000);
            assertEquals(globals, StatsData.decode(h.extractOutputValues()).stream()
                    .filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).count(), "an idle series emits nothing");
        }
    }

    @Test
    void undecodableInputIsSkipped() throws Exception {
        try (var h = harness()) {
            h.open();
            h.processElement(new StreamRecord<>(new StatsInput("x", true, new byte[]{1, 2, 3}, "tsx.unit.episodes.v2", 0, 0)));
            h.processWatermark(Long.MAX_VALUE);
            assertTrue(h.extractOutputValues().isEmpty());
        }
    }

    @Test
    void onlyTheFirstUndecodableInputOfAPartitionIsReported() throws Exception {
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        List<StatsRecord> out = runCapturingStderr(List.of(new StatsInput("x", true, new byte[]{1, 2, 3}, "tsx.unit.episodes.v2", 1, 4),
                new StatsInput("x", true, new byte[]{4, 5, 6}, "tsx.unit.episodes.v2", 1, 5)), metrics, err);

        assertTrue(out.isEmpty());
        assertEquals(2, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals("tsx.unit.episodes.v2-1 offset 4: undecodable record (unrecognised payload framing); skipped and counted"
                + " in undecodable, this subtask only counts later undecodable records of this partition"
                + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theDeserializerReportsOnlyTheFirstUndecodableRecordOfAPartition() {
        StatsInputDeserializer deserializer = new StatsInputDeserializer("tsx.unit.episodes.v2");
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        deserializer.open(new DeserializationSchema.InitializationContext() {
            @Override
            public MetricGroup getMetricGroup() {
                return metrics;
            }

            @Override
            public UserCodeClassLoader getUserCodeClassLoader() {
                return SimpleUserCodeClassLoader.create(getClass().getClassLoader());
            }
        });
        List<StatsInput> inputs = new ArrayList<>();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            deserializer.deserialize(record(2, 10, new byte[]{1, 2, 3}), new ListCollector<>(inputs));
            deserializer.deserialize(record(2, 11, null), new ListCollector<>(inputs));
            deserializer.deserialize(record(3, 12, null), new ListCollector<>(inputs));
        } finally {
            System.setErr(stderr);
        }

        assertTrue(inputs.isEmpty());
        assertEquals(3, ((Counter) metrics.get("undecodable")).getCount());
        String later = "; skipped and counted in undecodable, this subtask only counts later undecodable records of this partition";
        assertEquals(List.of("tsx.unit.episodes.v2-2 offset 10: undecodable record (unrecognised payload framing)" + later,
                        "tsx.unit.episodes.v2-3 offset 12: undecodable record (no value)" + later),
                err.toString(StandardCharsets.UTF_8).lines().toList());
    }

    @Test
    void anInconsistentEpisodeIsCountedAsInvalidReportedOncePerPartitionAndSkipped() throws Exception {
        List<Episode> episodes = StatsData.episodes("invalid", Duration.ofMinutes(10), 1);
        Episode valid = episodes.get(0);
        Episode other = episodes.stream().filter(e -> !e.getSeries().getSeriesId().equals(valid.getSeries().getSeriesId()))
                .findFirst().orElseThrow();
        OpenTsxAvro avro = new OpenTsxAvro();
        List<StatsInput> inputs = new ArrayList<>();
        StatsInputDeserializer deserializer = new StatsInputDeserializer("tsx.unit.episodes.v2");
        deserializer.open(new DeserializationSchema.InitializationContext() {
            @Override
            public MetricGroup getMetricGroup() {
                return new UnregisteredMetricsGroup();
            }

            @Override
            public UserCodeClassLoader getUserCodeClassLoader() {
                return SimpleUserCodeClassLoader.create(getClass().getClassLoader());
            }
        });
        ListCollector<StatsInput> collector = new ListCollector<>(inputs);
        deserializer.deserialize(record(2, 40, avro.encode(valid)), collector);
        deserializer.deserialize(record(2, 41, avro.encode(countMismatch(other, "bad-1"))), collector);
        deserializer.deserialize(record(2, 42, avro.encode(countMismatch(other, "bad-2"))), collector);
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        List<StatsRecord> out;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try (MockEnvironment env = environment(metrics); var h = harness(env)) {
            h.open();
            for (StatsInput in : inputs) {
                h.processElement(new StreamRecord<>(in));
            }
            h.processWatermark(Long.MAX_VALUE);
            out = h.extractOutputValues();
        } finally {
            System.setErr(stderr);
        }

        Set<String> series = StatsData.decode(out).stream().map(s -> s.getSeriesId().toString()).collect(Collectors.toSet());
        assertEquals(Set.of(valid.getSeries().getSeriesId()), series, "the inconsistent episodes produce no statistics");
        assertEquals(0, ((Counter) metrics.get("undecodable")).getCount());
        assertNotNull(metrics.get("invalid"), "the function registers the metric invalid");
        assertEquals(2, ((Counter) metrics.get("invalid")).getCount());
        assertEquals("tsx.unit.episodes.v2-2 offset 41: invalid episode (count 3 != values 2); skipped and counted in"
                + " invalid, this subtask only counts later invalid episodes of this partition" + System.lineSeparator(),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aRegularEpisodeWithoutAnIntervalIsCountedAsInvalid() throws Exception {
        Episode valid = StatsData.episodes("no-interval", Duration.ofMinutes(10), 1).get(0);
        Episode missing = Episode.newBuilder(valid).setEpisodeId("no-interval").setIntervalMicros(null).build();
        OpenTsxAvro avro = new OpenTsxAvro();
        String id = valid.getSeries().getSeriesId();
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        List<StatsRecord> out = runCapturingStderr(List.of(new StatsInput(id, true, avro.encode(valid), "tsx.unit.episodes.v2", 3, 7),
                new StatsInput(id, true, avro.encode(missing), "tsx.unit.episodes.v2", 3, 8)), metrics, err);

        assertEquals(StatsData.direct(List.of(valid), SPEC), StatsData.decode(out));
        assertEquals(1, ((Counter) metrics.get("invalid")).getCount());
        assertEquals("tsx.unit.episodes.v2-3 offset 8: invalid episode (intervalMicros: missing in a REGULAR episode; expected"
                + " a positive number of microseconds); skipped and counted in invalid, this subtask only counts later invalid"
                + " episodes of this partition" + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
    }

    private static List<StatsRecord> runCapturingStderr(List<StatsInput> inputs, InterceptingOperatorMetricGroup metrics,
                                                        ByteArrayOutputStream err) throws Exception {
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try (MockEnvironment env = environment(metrics); var h = harness(env)) {
            h.open();
            for (StatsInput in : inputs) {
                h.processElement(new StreamRecord<>(in));
            }
            h.processWatermark(Long.MAX_VALUE);
            return h.extractOutputValues();
        } finally {
            System.setErr(stderr);
        }
    }

    private static ConsumerRecord<byte[], byte[]> record(int partition, long offset, byte[] value) {
        return new ConsumerRecord<>("tsx.unit.episodes.v2", partition, offset, null, value);
    }

    private static Episode countMismatch(Episode e, String episodeId) {
        Episode two = Episodes.regular(e.getSeries(), Micros.of(e.getTStart()), 1_000_000L, new double[]{1, 2})
                .episodeId(episodeId).source("test").build();
        return Episode.newBuilder(two).setCount(3).build();
    }

    @Test
    void anEngineFailureFailsTheTask() throws Exception {
        StatsInput in = StatsData.inputs(StatsData.episodes("engine", Duration.ofMinutes(10), 1)).get(0);
        IllegalStateException cause = new IllegalStateException("interval catalog unavailable");
        StatsEngine failing = new StatsEngine(SPEC.config().withExpectedInterval(id -> {
            throw cause;
        }));
        try (var h = harness()) {
            h.open();
            h.getOperator().setCurrentKey(in.getSeriesId());
            h.getOperator().<String>getKeyedStateBackend().getPartitionedState(VoidNamespace.INSTANCE,
                    VoidNamespaceSerializer.INSTANCE, new ValueStateDescriptor<>("engine", new StatsEngineSerializer(SPEC)))
                    .update(failing);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> h.processElement(new StreamRecord<>(in)));
            assertSame(cause, e);
        }
    }

    @Test
    void anAlphaOutsideTheSketchRangeIsRefusedAtStart() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--alpha", "5"}));
        assertTrue(e.getMessage().startsWith("sketchAlpha: \"5.0\" is not allowed"), e.getMessage());
    }

    @Test
    void optionsMirrorTheProcessor() {
        StatsJob.Options o = StatsJob.parse(new String[]{"--kafscale", "localhost:9092", "--domain", "solar",
                "--windows", "PT1H", "--once", "true"});
        assertEquals("tsx.solar.episodes.v2", o.layout().episodes());
        assertEquals(List.of(Duration.ofHours(1)), o.spec().windows());
        assertTrue(o.once());
        assertEquals(Duration.ofSeconds(30), o.globalEvery());
        assertThrows(IllegalArgumentException.class, () -> StatsJob.parse(new String[]{"--domain", "wind"}));
        assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--checkpoint", "stats.ckpt"}));
        assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--windws", "PT1H"}));
        assertThrows(IllegalArgumentException.class,
                () -> StatsJob.parse(new String[]{"--kafscale", "k:9092", "--once"}));
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness() throws Exception {
        return harness(0);
    }

    private static MockEnvironment environment(InterceptingOperatorMetricGroup metrics) {
        return new MockEnvironmentBuilder().setMetricGroup(new UnregisteredMetricGroups.UnregisteredTaskMetricGroup() {
            @Override
            public InternalOperatorMetricGroup getOrAddOperator(OperatorID id, String name) {
                return metrics;
            }
        }).build();
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness(MockEnvironment env)
            throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new StatsFunction(SPEC, 0)),
                StatsInput::getSeriesId, Types.STRING, env);
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness(long globalEveryMs)
            throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new StatsFunction(SPEC, globalEveryMs)),
                StatsInput::getSeriesId, Types.STRING);
    }
}
