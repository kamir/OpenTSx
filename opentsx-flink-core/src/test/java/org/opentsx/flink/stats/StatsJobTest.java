package org.opentsx.flink.stats;

import org.apache.flink.api.common.JobExecutionResult;
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
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.SimpleUserCodeClassLoader;
import org.apache.flink.util.UserCodeClassLoader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.SeriesStatsLog;
import org.opentsx.stats.StatsEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        List<String> logged = new ArrayList<>();

        List<StatsRecord> out = runCapturingLog(List.of(new StatsInput("x", true, new byte[]{1, 2, 3}, "tsx.unit.episodes.v2", 1, 4),
                new StatsInput("x", true, new byte[]{4, 5, 6}, "tsx.unit.episodes.v2", 1, 5)), metrics, logged);

        assertTrue(out.isEmpty());
        assertEquals(2, ((Counter) metrics.get("undecodable")).getCount());
        assertEquals(List.of("WARN " + StatsFunction.class.getName() + " - undecodable record skipped and counted in undecodable;"
                + " later ones of this partition are only counted by this subtask topic=tsx.unit.episodes.v2 partition=1"
                + " offset=4 cause=unrecognised payload framing"), logged);
    }

    @Test
    void theDeserializerLogsOnlyTheFirstUndecodableRecordOfAPartition() throws Exception {
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
        List<String> logged = log(StatsInputDeserializer.class, () -> {
            deserializer.deserialize(record(2, 10, new byte[]{1, 2, 3}), new ListCollector<>(inputs));
            deserializer.deserialize(record(2, 11, null), new ListCollector<>(inputs));
            deserializer.deserialize(record(3, 12, null), new ListCollector<>(inputs));
        });

        assertTrue(inputs.isEmpty());
        assertEquals(3, ((Counter) metrics.get("undecodable")).getCount());
        String line = "WARN org.opentsx.flink.stats.StatsInputDeserializer - undecodable record skipped and counted in"
                + " undecodable; later ones of this partition are only counted by this subtask topic=tsx.unit.episodes.v2";
        assertEquals(List.of(line + " partition=2 offset=10 cause=unrecognised payload framing",
                line + " partition=3 offset=12 cause=no value"), logged);
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
        List<String> logged = new ArrayList<>();
        List<StatsRecord> out = runCapturingLog(inputs, metrics, logged);

        Set<String> series = StatsData.decode(out).stream().map(s -> s.getSeriesId().toString()).collect(Collectors.toSet());
        assertEquals(Set.of(valid.getSeries().getSeriesId()), series, "the inconsistent episodes produce no statistics");
        assertEquals(0, ((Counter) metrics.get("undecodable")).getCount());
        assertNotNull(metrics.get("invalid"), "the function registers the metric invalid");
        assertEquals(2, ((Counter) metrics.get("invalid")).getCount());
        assertEquals(List.of("WARN " + StatsFunction.class.getName() + " - invalid episode skipped and counted in invalid; later"
                + " ones of this partition are only counted by this subtask topic=tsx.unit.episodes.v2 partition=2 offset=41"
                + " cause=count: \"3\" differs from the length 2 of values; expected the number of values"), logged);
    }

    @Test
    void aRegularEpisodeWithoutAnIntervalIsCountedAsInvalid() throws Exception {
        Episode valid = StatsData.episodes("no-interval", Duration.ofMinutes(10), 1).get(0);
        Episode missing = Episode.newBuilder(valid).setEpisodeId("no-interval").setIntervalMicros(null).build();
        OpenTsxAvro avro = new OpenTsxAvro();
        String id = valid.getSeries().getSeriesId();
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        List<String> logged = new ArrayList<>();

        List<StatsRecord> out = runCapturingLog(List.of(new StatsInput(id, true, avro.encode(valid), "tsx.unit.episodes.v2", 3, 7),
                new StatsInput(id, true, avro.encode(missing), "tsx.unit.episodes.v2", 3, 8)), metrics, logged);

        assertEquals(StatsData.direct(List.of(valid), SPEC), StatsData.decode(out));
        assertEquals(1, ((Counter) metrics.get("invalid")).getCount());
        assertEquals(List.of("WARN " + StatsFunction.class.getName() + " - invalid episode skipped and counted in invalid; later"
                + " ones of this partition are only counted by this subtask topic=tsx.unit.episodes.v2 partition=3 offset=8"
                + " cause=intervalMicros: missing in a REGULAR episode; expected a positive number of microseconds"), logged);
    }

    @Test
    void theGlobalTimerLogsTheRunningCountsOncePerInterval() throws Exception {
        List<Episode> episodes = StatsData.episodes("progress", Duration.ofMinutes(10), 1);
        Episode a = episodes.get(0);
        Episode b = episodes.stream().filter(e -> !e.getSeries().getSeriesId().equals(a.getSeries().getSeriesId()))
                .findFirst().orElseThrow();
        OpenTsxAvro avro = new OpenTsxAvro();
        String topic = "tsx.unit.episodes.v2";
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        List<String> logged;
        try (MockEnvironment env = environment(metrics); var h = harness(env, 60_000)) {
            h.open();
            h.setProcessingTime(0);
            logged = log(StatsFunction.class, () -> {
                h.processElement(new StreamRecord<>(new StatsInput(a.getSeries().getSeriesId(), true, avro.encode(a), topic, 0, 1)));
                h.processElement(new StreamRecord<>(new StatsInput(b.getSeries().getSeriesId(), true, avro.encode(b), topic, 0, 2)));
                h.processElement(new StreamRecord<>(new StatsInput(b.getSeries().getSeriesId(), true,
                        avro.encode(countMismatch(b, "bad")), topic, 0, 3)));
                h.setProcessingTime(60_000);
            });
        }

        List<String> progress = logged.stream().filter(l -> l.startsWith("INFO ")).toList();
        assertEquals(1, progress.size(), "two series fire their GLOBAL timer in the same interval: " + logged);
        assertTrue(progress.get(0).startsWith("INFO " + StatsFunction.class.getName() + " - progress subtask=0 records=3"
                + " undecodable=0 invalid=1 statsEmitted="), progress.get(0));
    }

    @Test
    void aSubtaskWithOnlyBadInputsStillLogsItsCounts() throws Exception {
        Episode a = StatsData.episodes("bad-only", Duration.ofMinutes(10), 1).get(0);
        String series = a.getSeries().getSeriesId();
        OpenTsxAvro avro = new OpenTsxAvro();
        String topic = "tsx.unit.episodes.v2";
        InterceptingOperatorMetricGroup metrics = new InterceptingOperatorMetricGroup();
        List<String> logged;
        try (MockEnvironment env = environment(metrics); var h = harness(env, 60_000)) {
            h.open();
            h.setProcessingTime(0);
            logged = log(StatsFunction.class, () -> {
                h.processElement(new StreamRecord<>(new StatsInput(series, true, avro.encode(countMismatch(a, "bad-1")), topic, 0, 1)));
                h.processElement(new StreamRecord<>(new StatsInput("x", true, new byte[]{1, 2, 3}, topic, 0, 2)));
                h.processElement(new StreamRecord<>(new StatsInput(series, true, avro.encode(countMismatch(a, "bad-2")), topic, 0, 3)));
                h.setProcessingTime(60_000);
                h.processElement(new StreamRecord<>(new StatsInput("x", true, new byte[]{4, 5, 6}, topic, 0, 4)));
                h.setProcessingTime(120_000);
            });
            assertTrue(h.extractOutputValues().isEmpty());
        }

        String progress = "INFO " + StatsFunction.class.getName() + " - progress subtask=0";
        assertEquals(List.of(progress + " records=3 undecodable=1 invalid=2 statsEmitted=0",
                progress + " records=4 undecodable=2 invalid=2 statsEmitted=0"),
                logged.stream().filter(l -> l.startsWith("INFO ")).toList(), String.join("\n", logged));
    }

    @Test
    void theJobLogsItsStartAndItsSubmissionBeforeTheClusterAnswers() throws Exception {
        Exit[] exit = new Exit[1];
        List<String> logged;
        RefusingEnvironment.setAsContext();
        try {
            logged = log(StatsJob.class, () -> exit[0] = run("--kafscale", "k:9092"));
        } finally {
            RefusingEnvironment.unsetAsContext();
        }

        assertEquals(new Exit(1, "", "opentsx: the cluster refuses the job\n"), exit[0]);
        assertEquals(2, logged.size(), String.join("\n", logged));
        assertTrue(logged.get(0).startsWith("INFO " + StatsJob.class.getName() + " - starting stats-flink: "), logged.get(0));
        assertEquals("INFO " + StatsJob.class.getName() + " - submitting the statistics job domain=wind"
                + " topics=tsx.wind.episodes.v2 sink=tsx.wind.series-stats.v2 bootstrap=k:9092 once=false checkpointEvery=PT30S"
                + " restarts=20 restartDelay=PT15S", logged.get(1));
    }

    /** An environment that refuses every job, as a cluster that cannot be reached would. */
    private static final class RefusingEnvironment extends StreamExecutionEnvironment {

        @Override
        public JobExecutionResult execute(StreamGraph streamGraph) {
            throw new IllegalStateException("the cluster refuses the job");
        }

        static void setAsContext() {
            initializeContextEnvironment(configuration -> new RefusingEnvironment());
        }

        static void unsetAsContext() {
            resetContextEnvironment();
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    /** The lines that the logger of {@code type} writes while {@code body} runs, as {@code <level> <logger> - <message>}. */
    private static List<String> log(Class<?> type, Body body) throws Exception {
        Logger logger = (Logger) LogManager.getLogger(type);
        StringWriter logged = new StringWriter();
        WriterAppender appender = WriterAppender.newBuilder().setName("captured").setTarget(logged)
                .setLayout(PatternLayout.newBuilder().withPattern("%p %c - %m%n").build()).build();
        appender.start();
        Level level = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            body.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(level);
            appender.stop();
        }
        return logged.toString().lines().toList();
    }

    private static List<StatsRecord> runCapturingLog(List<StatsInput> inputs, InterceptingOperatorMetricGroup metrics,
                                                     List<String> logged) throws Exception {
        List<StatsRecord> out = new ArrayList<>();
        try (MockEnvironment env = environment(metrics); var h = harness(env)) {
            h.open();
            logged.addAll(log(StatsFunction.class, () -> {
                for (StatsInput in : inputs) {
                    h.processElement(new StreamRecord<>(in));
                }
                h.processWatermark(Long.MAX_VALUE);
            }));
            out.addAll(h.extractOutputValues());
        }
        return out;
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
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse("--kafscale", "k:9092", "--alpha", "5"));
        assertTrue(e.getMessage().startsWith("sketchAlpha: \"5.0\" is not allowed"), e.getMessage());
        assertEquals(new Exit(2, "", "opentsx: " + e.getMessage() + "\n"), run("--kafscale", "k:9092", "--alpha", "5"));
    }

    @Test
    void optionsMirrorTheProcessor() {
        StatsJob.Options o = parse("--kafscale", "localhost:9092", "--domain", "solar", "--windows", "PT1H", "--once", "true");
        assertEquals("tsx.solar.episodes.v2", o.layout().episodes());
        assertEquals(List.of(Duration.ofHours(1)), o.spec().windows());
        assertTrue(o.once());
        assertEquals(Duration.ofSeconds(30), o.globalEvery());
        assertTrue(parse("--kafscale", "k:9092", "--once").once(), "a bare boolean flag means true");
        assertEquals("opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf opentsx.kafscale.bootstrap=<value>"
                + " or OPENTSX_KAFSCALE_BOOTSTRAP", assertThrows(OpenTsxConfException.class, () -> parse("--domain", "wind")).getMessage());
        assertEquals("--checkpoint: an option of stats, not of stats-flink; the Flink job keeps its positions and state in Flink"
                        + " checkpoints (--checkpoint-every)",
                assertThrows(OpenTsxConfException.class, () -> parse("--kafscale", "k:9092", "--checkpoint", "stats.ckpt")).getMessage());
        assertEquals("--windws: unknown option of stats-flink; did you mean --windows?",
                assertThrows(OpenTsxConfException.class, () -> parse("--kafscale", "k:9092", "--windws", "PT1H")).getMessage());
        assertEquals("--once: set twice on the command line (also by --once); set each key once",
                assertThrows(OpenTsxConfException.class, () -> parse("--kafscale", "k:9092", "--once", "--once")).getMessage());
        assertEquals("opentsx.stats.checkpointInterval: \"0s\" is not allowed; expected a positive duration such as PT30S",
                assertThrows(OpenTsxConfException.class, () -> parse("--kafscale", "k:9092", "--checkpoint-every", "0s")).getMessage());
    }

    @Test
    void aMalformedValuePrintsOneLineAndExitsWith2() throws Exception {
        assertEquals(new Exit(2, "", "opentsx: --windows: item \"PT10\" is not a duration; expected ISO-8601 such as PT10M or a"
                + " whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days\n"),
                main("--kafscale", "k:9092", "--windows", "PT10"));
    }

    @Test
    void versionPrintsTheVersionOnTheGivenStdoutAndReturnsZero() {
        assertEquals(new Exit(0, ConfCli.versionLine(), ""), run("--version"));
    }

    @Test
    void helpPrintsTheKeysOfStatsFlinkOnStdout() throws Exception {
        Exit help = main("--help");
        assertEquals(new Exit(0, Registry.shipped().help("stats-flink"), ""), help);
        assertTrue(help.out().contains("\n  opentsx.stats.checkpointInterval  duration = PT30S  --checkpoint-every"
                + "  OPENTSX_STATS_CHECKPOINT_INTERVAL\n"), help.out());
    }

    @Test
    void theStartLogsOneLineWithTheKeysOfStatsFlink() {
        Logger logger = (Logger) LogManager.getLogger(StatsJob.class);
        StringWriter logged = new StringWriter();
        WriterAppender appender = WriterAppender.newBuilder().setName("start-line").setTarget(logged)
                .setLayout(PatternLayout.newBuilder().withPattern("%p %c - %m%n").build()).build();
        appender.start();
        Level level = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            StatsJob.start(StatsJob.load(List.of("--kafscale", "k:9092", "--alpha", "0.02"), Map.of()));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(level);
            appender.stop();
        }
        List<String> lines = logged.toString().lines().toList();
        assertEquals(1, lines.size(), logged.toString());
        String prefix = "INFO " + StatsJob.class.getName() + " - ";
        assertTrue(lines.get(0).startsWith(prefix), lines.get(0));
        String line = lines.get(0).substring(prefix.length());
        assertTrue(line.startsWith("starting stats-flink: opentsx.domain=wind (default), opentsx.kafscale.bootstrap=k:9092"
                + " (commandLine), "), line);
        assertTrue(line.contains(", opentsx.stats.checkpointInterval=PT30S (default), "), line);
        assertTrue(line.contains(", opentsx.stats.sketchAlpha=0.02 (commandLine), "), line);
        assertTrue(line.endsWith(", opentsx.stats.windows=PT10M,PT1H (default)"), line);
        assertFalse(line.contains("opentsx.log.level"), "stats-flink logs through log4j2: " + line);
    }

    private record Exit(int code, String out, String err) {
    }

    private static StatsJob.Options parse(String... args) {
        return StatsJob.options(StatsJob.load(List.of(args), Map.of()).conf());
    }

    private static Exit run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StatsJob.run(List.of(args), Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Exit(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Runs the main class in a new JVM with an empty environment. */
    private static Exit main(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), StatsJob.class.getName()));
        command.addAll(List.of(args));
        Path dir = Files.createTempDirectory("statsjob");
        try {
            File out = dir.resolve("out").toFile();
            File err = dir.resolve("err").toFile();
            ProcessBuilder pb = new ProcessBuilder(command).redirectOutput(out).redirectError(err);
            pb.environment().clear();
            Process p = pb.start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly().waitFor();
                fail("still running after 60 s: " + String.join(" ", args) + "\n" + Files.readString(err.toPath()));
            }
            return new Exit(p.exitValue(), Files.readString(out.toPath()), Files.readString(err.toPath()));
        } finally {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
            Files.delete(dir);
        }
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
        return harness(env, 0);
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness(MockEnvironment env,
                                                                                                  long globalEveryMs)
            throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new StatsFunction(SPEC, globalEveryMs)),
                StatsInput::getSeriesId, Types.STRING, env);
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, StatsInput, StatsRecord> harness(long globalEveryMs)
            throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new StatsFunction(SPEC, globalEveryMs)),
                StatsInput::getSeriesId, Types.STRING);
    }
}
