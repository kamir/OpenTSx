package org.opentsx.stats;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.BucketWriter;
import org.opentsx.kafka.InMemoryEpisodeIndex;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsProcessorTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

    private static Scenario scenario(String name, Duration duration, int turbines) {
        return new Scenario(name, 5, START, duration, Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", name, 54, 13, "GENERIC-3MW", 1, turbines, 5, 7, 0.05),
                Scenario.WindSpec.DEFAULT, List.of(), List.of());
    }

    private static List<Episode> simulate(Scenario s) {
        List<Episode> out = new ArrayList<>();
        new WindparkSimulator(s, ChannelCatalog.standard()).run(out::add);
        return out;
    }

    private static MockConsumer<byte[], byte[]> consumerWith(TopicLayout layout, List<Episode> episodes) {
        return fill(new MockConsumer<>(OffsetResetStrategy.EARLIEST), layout, episodes);
    }

    private static MockConsumer<byte[], byte[]> fill(MockConsumer<byte[], byte[]> c, TopicLayout layout, List<Episode> episodes) {
        OpenTsxAvro avro = new OpenTsxAvro();
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(layout.episodes(), List.of(
                new PartitionInfo(layout.episodes(), 0, node, new Node[]{node}, new Node[]{node}),
                new PartitionInfo(layout.episodes(), 1, node, new Node[]{node}, new Node[]{node})));
        TopicPartition p0 = new TopicPartition(layout.episodes(), 0);
        TopicPartition p1 = new TopicPartition(layout.episodes(), 1);
        c.updateBeginningOffsets(Map.of(p0, 0L, p1, 0L));
        long[] next = new long[2];
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        for (Episode e : episodes) {
            int p = Math.floorMod(e.getSeries().getSeriesId().hashCode(), 2);
            records.add(new ConsumerRecord<>(layout.episodes(), p, next[p]++, null, avro.encode(e)));
        }
        c.updateEndOffsets(Map.of(p0, next[0], p1, next[1]));
        c.schedulePollTask(() -> records.forEach(c::addRecord));
        return c;
    }

    /** Appends the payloads after the end of {@code tp}; returns the offset of the first one. */
    private static long append(MockConsumer<byte[], byte[]> c, TopicPartition tp, byte[]... payloads) {
        long end = c.endOffsets(List.of(tp)).get(tp);
        c.schedulePollTask(() -> {
            for (int i = 0; i < payloads.length; i++) {
                c.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), end + i, null, payloads[i]));
            }
        });
        c.updateEndOffsets(Map.of(tp, end + payloads.length));
        return end;
    }

    private static StatsProcessor.RunResult runCapturingStderr(StatsProcessor p, ByteArrayOutputStream err) {
        return runCapturingStderr(p, err, Duration.ofSeconds(30));
    }

    private static StatsProcessor.RunResult runCapturingStderr(StatsProcessor p, ByteArrayOutputStream err, Duration timeout) {
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return p.runUntilCaughtUp(timeout, true);
        } finally {
            System.setErr(stderr);
        }
    }

    /** The WARN lines slf4j-simple wrote, from the level on, without the thread name in front. */
    private static List<String> warnings(ByteArrayOutputStream err) {
        return logLines(err).stream().filter(l -> l.startsWith("WARN ")).toList();
    }

    private static List<String> logLines(ByteArrayOutputStream err) {
        return err.toString(StandardCharsets.UTF_8).lines().map(l -> l.substring(l.indexOf("] ") + 2)).toList();
    }

    private static String retention(TopicPartition tp, long position, long logStart) {
        return "WARN org.opentsx.stats.StatsProcessor - retention deleted records before they were read; reading on from"
                + " the log start topic=" + tp.topic() + " partition=" + tp.partition() + " position=" + position
                + " logStart=" + logStart + " lost=" + (logStart - position);
    }

    private static Episode countMismatch(Episode e) {
        Episode two = Episodes.regular(e.getSeries(), Micros.of(START) + 3_600_000_000L, 1_000_000L, new double[]{1, 2})
                .episodeId("count-mismatch").source("test").build();
        return Episode.newBuilder(two).setCount(3).build();
    }

    private static Episode foreignSeriesId(Episode e, String episodeId) {
        SeriesKey key = SeriesKey.newBuilder(e.getSeries()).setSeriesId("not-" + e.getSeries().getSeriesId()).build();
        return Episode.newBuilder(e).setEpisodeId(episodeId).setSeries(key).build();
    }

    private static List<SeriesStats> decode(MockProducer<String, byte[]> producer) {
        OpenTsxAvro avro = new OpenTsxAvro();
        return producer.history().stream().map(ProducerRecord::value).map(v -> avro.decode(v, SeriesStats.class)).toList();
    }

    @Test
    void aRefusedFetchIsWaitedOutNotFatal() {
        // KafScale refuses fetches for about a minute at a time; the consumer reports that as IllegalStateException
        MockConsumer<byte[], byte[]> refusing = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
            private int refusals = 3;

            @Override
            public synchronized org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
                if (refusals-- > 0) {
                    throw new IllegalStateException("Unexpected error code 7 while fetching at offset 0 from topic-partition x-0");
                }
                return super.poll(timeout);
            }
        };
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("refused", Duration.ofMinutes(20), 1));
        MockProducer<String, byte[]> out = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StatsProcessor p = new StatsProcessor(fill(refusing, layout, eps), out, layout,
                new StatsEngine(StatsEngine.Config.defaults()), null, false);
        p.patience(new org.opentsx.kafka.Patience(Duration.ofSeconds(30), Duration.ofMillis(5)));
        StatsProcessor.RunResult r = p.runUntilCaughtUp(Duration.ofSeconds(30), true);
        assertEquals(eps.size(), r.records(), "every record is read once the broker answers again");
    }

    @Test
    void boundedRunWithCheckpointResumesWithoutDoubleCounting(@TempDir Path dir) {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("proc", Duration.ofHours(1), 2));
        int half = eps.size() / 2;
        Path checkpoint = dir.resolve("stats.ckpt");

        // run 1: first half, no flush (open windows stay in the checkpoint)
        MockProducer<String, byte[]> out1 = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StatsProcessor p1 = new StatsProcessor(consumerWith(layout, eps.subList(0, half)), out1, layout,
                new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false);
        p1.runUntilCaughtUp(Duration.ofSeconds(30), false);

        // run 2: a new process sees the whole log; it restores positions + state and continues
        MockProducer<String, byte[]> out2 = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StatsProcessor p2 = new StatsProcessor(consumerWith(layout, eps), out2, layout,
                new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false);
        StatsProcessor.RunResult r = p2.runUntilCaughtUp(Duration.ofSeconds(30), true);
        assertEquals(eps.size() - half, r.records(), "only records after the checkpoint are read");

        List<SeriesStats> all = new ArrayList<>(decode(out1));
        all.addAll(decode(out2));
        List<SeriesStats> latest = SeriesStatsLog.latest(all);
        long series = eps.stream().map(e -> e.getSeries().getSeriesId()).distinct().count();
        List<SeriesStats> global = latest.stream().filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL)).toList();
        assertEquals(series, global.size());
        global.forEach(g -> assertEquals(3600, g.getValidCount() + g.getNanCount(), "every value counted exactly once"));
        assertEquals(series * 6, latest.stream().filter(s -> s.getWindowType().equals("PT10M")).count());
        assertEquals(series, latest.stream().filter(s -> s.getWindowType().equals("PT1H")).count());
    }

    @Test
    void anEngineFailureStopsTheRunAndLeavesNoCheckpoint(@TempDir Path dir) {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("engine", Duration.ofMinutes(20), 1));
        IllegalStateException cause = new IllegalStateException("interval catalog unavailable");
        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withExpectedInterval(id -> {
            throw cause;
        }));
        Path checkpoint = dir.resolve("stats.ckpt");
        MockProducer<String, byte[]> out = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StatsProcessor p = new StatsProcessor(consumerWith(layout, eps), out, layout, engine, checkpoint, false);
        RuntimeException e = assertThrows(RuntimeException.class, () -> p.runUntilCaughtUp(Duration.ofSeconds(30), true));
        assertSame(cause, e.getCause());
        assertTrue(e.getMessage().startsWith(layout.episodes() + "-"), e.getMessage());
        assertFalse(Files.exists(checkpoint), "the position must not move past a record the engine failed on");
        assertTrue(p.positions().values().stream().allMatch(o -> o == 0L), p.positions().toString());
        assertTrue(out.history().isEmpty());
    }

    @Test
    void aRecordThatDoesNotDecodeIsCountedAndSkipped() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("undecodable", Duration.ofMinutes(20), 1));
        MockConsumer<byte[], byte[]> c = consumerWith(layout, eps);
        TopicPartition tp = new TopicPartition(layout.episodes(), Math.floorMod(eps.get(0).getSeries().getSeriesId().hashCode(), 2));
        long end = c.endOffsets(List.of(tp)).get(tp);
        c.schedulePollTask(() -> c.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), end, null,
                "not avro".getBytes(StandardCharsets.UTF_8))));
        c.updateEndOffsets(Map.of(tp, end + 1));
        MockProducer<String, byte[]> out = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StatsProcessor.RunResult r = new StatsProcessor(c, out, layout, new StatsEngine(StatsEngine.Config.defaults()), null, false)
                .runUntilCaughtUp(Duration.ofSeconds(30), true);
        assertEquals(eps.size() + 1, r.records());
        assertEquals(1, r.undecodable());
        assertEquals(eps.size(), r.metrics().episodes());
    }

    @Test
    void onlyTheFirstUndecodableRecordOfAPartitionIsReported() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("undecodable-report", Duration.ofMinutes(20), 1));
        MockConsumer<byte[], byte[]> c = consumerWith(layout, eps);
        TopicPartition p1 = new TopicPartition(layout.episodes(), 1);
        long at = append(c, p1, "not avro".getBytes(StandardCharsets.UTF_8), "also not avro".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = runCapturingStderr(p, err);

        assertEquals(2, r.undecodable());
        assertEquals(List.of("WARN org.opentsx.stats.StatsProcessor - undecodable record skipped and counted in undecodable;"
                + " later ones of this partition are only counted topic=" + p1.topic() + " partition=1 offset=" + at
                + " cause=unrecognised payload framing"), warnings(err));
        assertEquals(at + 2, p.positions().get(p1));
    }

    @Test
    void anInconsistentEpisodeIsCountedAsInvalidReportedOncePerPartitionAndSkipped() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("invalid", Duration.ofMinutes(20), 1));
        MockConsumer<byte[], byte[]> c = consumerWith(layout, eps);
        OpenTsxAvro avro = new OpenTsxAvro();
        TopicPartition p0 = new TopicPartition(layout.episodes(), 0);
        TopicPartition p1 = new TopicPartition(layout.episodes(), 1);
        long at0 = append(c, p0, avro.encode(countMismatch(eps.get(0))), avro.encode(foreignSeriesId(eps.get(1), "foreign-1")));
        long at1 = append(c, p1, avro.encode(foreignSeriesId(eps.get(2), "foreign-2")));
        MockProducer<String, byte[]> out = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, out, layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = runCapturingStderr(p, err);

        assertEquals(eps.size() + 3, r.records());
        assertEquals(3, r.invalid());
        assertEquals(0, r.undecodable());
        assertEquals(eps.size(), r.metrics().episodes(), "the engine never sees an inconsistent episode");
        Set<String> valid = eps.stream().map(e -> e.getSeries().getSeriesId()).collect(Collectors.toSet());
        List<SeriesStats> stats = decode(out);
        assertEquals(valid, stats.stream().map(s -> s.getSeriesId().toString()).collect(Collectors.toSet()));
        stats.stream().filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL))
                .forEach(g -> assertEquals(1200, g.getValidCount() + g.getNanCount(), g.getSeriesId()));
        String later = "WARN org.opentsx.stats.StatsProcessor - invalid episode skipped and counted in invalid; later ones of"
                + " this partition are only counted topic=" + layout.episodes();
        String id2 = eps.get(2).getSeries().getSeriesId();
        assertEquals(List.of(later + " partition=0 offset=" + at0 + " cause=count: \"3\" differs from the length 2 of values;"
                        + " expected the number of values",
                        later + " partition=1 offset=" + at1 + " cause=seriesId not-" + id2 + " does not match " + id2),
                warnings(err).stream().sorted().toList());
        assertEquals(Map.of(p0, at0 + 2, p1, at1 + 1), p.positions(), "the position moves past an invalid episode");
    }

    @Test
    void aRegularEpisodeWithoutAPositiveIntervalIsCountedAsInvalidAndTheRunGoesOn() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("interval", Duration.ofMinutes(20), 1));
        MockConsumer<byte[], byte[]> c = consumerWith(layout, eps);
        OpenTsxAvro avro = new OpenTsxAvro();
        TopicPartition p0 = new TopicPartition(layout.episodes(), 0);
        TopicPartition p1 = new TopicPartition(layout.episodes(), 1);
        long at0 = append(c, p0, avro.encode(Episode.newBuilder(eps.get(0)).setEpisodeId("interval-0").setIntervalMicros(0L).build()));
        long at1 = append(c, p1, avro.encode(Episode.newBuilder(eps.get(1)).setEpisodeId("no-interval").setIntervalMicros(null).build()));
        MockProducer<String, byte[]> out = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, out, layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = runCapturingStderr(p, err);

        assertEquals(eps.size() + 2, r.records());
        assertEquals(2, r.invalid());
        assertEquals(eps.size(), r.metrics().episodes(), "the engine never sees an episode without a positive interval");
        decode(out).stream().filter(s -> s.getWindowType().equals(StatsEngine.GLOBAL))
                .forEach(g -> assertEquals(1200, g.getValidCount() + g.getNanCount(), g.getSeriesId()));
        String later = "WARN org.opentsx.stats.StatsProcessor - invalid episode skipped and counted in invalid; later ones of"
                + " this partition are only counted topic=" + layout.episodes();
        assertEquals(List.of(
                        later + " partition=0 offset=" + at0 + " cause=intervalMicros: \"0\" is not allowed; expected a positive"
                                + " number of microseconds",
                        later + " partition=1 offset=" + at1 + " cause=intervalMicros: missing in a REGULAR episode; expected a"
                                + " positive number of microseconds"),
                warnings(err).stream().sorted().toList());
        assertEquals(Map.of(p0, at0 + 1, p1, at1 + 1), p.positions());
    }

    @Test
    void theOnceResultCountsAnInvalidEpisodeAndTheCheckpointMovesPastIt(@TempDir Path dir) {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("once", Duration.ofMinutes(20), 1));
        Path checkpoint = dir.resolve("stats.ckpt");
        byte[] bad = new OpenTsxAvro().encode(countMismatch(eps.get(0)));
        TopicPartition p0 = new TopicPartition(layout.episodes(), 0);

        MockConsumer<byte[], byte[]> first = consumerWith(layout, eps);
        append(first, p0, bad);
        StatsProcessor.RunResult r1 = runCapturingStderr(new StatsProcessor(first,
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false), new ByteArrayOutputStream());
        assertTrue(r1.toString().contains(", undecodable=0, invalid=1, lostToRetention=0, "), r1.toString());

        MockConsumer<byte[], byte[]> second = consumerWith(layout, eps);
        append(second, p0, bad);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor.RunResult r2 = runCapturingStderr(new StatsProcessor(second,
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false), err);
        assertTrue(r2.toString().startsWith("RunResult[records=0, undecodable=0, invalid=0, lostToRetention=0, "), r2.toString());
        assertEquals(List.of(), warnings(err));
    }

    @Test
    void aGapThatOpensDuringARunIsCountedAndReported() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("gap", Duration.ofMinutes(20), 1)).subList(0, 3);
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(layout.episodes(), List.of(new PartitionInfo(layout.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        c.updateBeginningOffsets(Map.of(tp, 0L));
        c.updateEndOffsets(Map.of(tp, 6L));
        OpenTsxAvro avro = new OpenTsxAvro();
        long[] offsets = {0, 1, 5};
        c.schedulePollTask(() -> {
            for (int i = 0; i < offsets.length; i++) {
                c.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), offsets[i], null, avro.encode(eps.get(i))));
            }
        });
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = runCapturingStderr(p, err);

        assertEquals(3, r.records());
        assertEquals(3, r.lostToRetention(), "offsets 2, 3 and 4 were deleted while the run read the partition");
        assertEquals(List.of(retention(tp, 2, 5)), warnings(err));
        assertEquals(Map.of(tp, 6L), p.positions());
    }

    @Test
    void aOnceRunEndsWhenRetentionDeletesPastItsEndOffsetWhileItReads() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("past-end", Duration.ofMinutes(20), 1)).subList(0, 2);
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(layout.episodes(), List.of(new PartitionInfo(layout.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        c.updateBeginningOffsets(Map.of(tp, 0L));
        c.updateEndOffsets(Map.of(tp, 3L));
        OpenTsxAvro avro = new OpenTsxAvro();
        c.schedulePollTask(() -> {
            c.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), 6, null, avro.encode(eps.get(0))));
            c.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), 7, null, avro.encode(eps.get(1))));
        });
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> runCapturingStderr(p, err));

        assertEquals(0, r.records(), "offsets 6 and 7 lie after the end offset 3 seen at the start");
        assertEquals(6, r.lostToRetention(), "offsets 0 to 5 were deleted before they were read");
        assertEquals(List.of(retention(tp, 0, 6)), warnings(err));
        assertEquals(Map.of(tp, 6L), p.positions(), "the next run reads on from offset 6");
    }

    @Test
    void aOnceRunEndsWhenRetentionDeletesEverythingUpToItsEndOffsetAndNothingIsLeft() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(layout.episodes(), List.of(new PartitionInfo(layout.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        c.updateBeginningOffsets(Map.of(tp, 0L));
        c.updateEndOffsets(Map.of(tp, 3L));
        c.schedulePollTask(() -> c.updateBeginningOffsets(Map.of(tp, 3L)));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> runCapturingStderr(p, err, Duration.ofSeconds(5)));

        assertEquals(0, r.records());
        assertEquals(3, r.lostToRetention(), "offsets 0 to 2 were deleted before they were read");
        assertEquals(List.of(retention(tp, 0, 3)), warnings(err));
        assertEquals(Map.of(tp, 3L), p.positions(), "the next run reads on from offset 3");
    }

    @Test
    void aOnceRunEndsWhenRetentionEmptiesOnePartitionWhileAnotherKeepsReceivingRecords() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        Node node = new Node(0, "localhost", 9092);
        String topic = layout.episodes();
        c.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, node, new Node[]{node}, new Node[]{node}),
                new PartitionInfo(topic, 1, node, new Node[]{node}, new Node[]{node})));
        TopicPartition p0 = new TopicPartition(topic, 0);
        TopicPartition p1 = new TopicPartition(topic, 1);
        c.updateBeginningOffsets(Map.of(p0, 0L, p1, 0L));
        c.updateEndOffsets(Map.of(p0, 0L, p1, 3L));
        c.schedulePollTask(new Runnable() {
            private long next;

            @Override
            public void run() {
                c.updateBeginningOffsets(Map.of(p0, 0L, p1, 3L));
                c.addRecord(new ConsumerRecord<>(topic, 0, next++, new byte[0], new byte[0]));
                c.schedulePollTask(this);
            }
        });
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        StatsProcessor p = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        StatsProcessor.RunResult r = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> runCapturingStderr(p, err, Duration.ofSeconds(5)));

        assertEquals(0, r.records(), "the records of p0 lie after its end offset 0");
        assertEquals(3, r.lostToRetention(), "offsets 0 to 2 of p1 were deleted before they were read");
        assertEquals(3L, p.positions().get(p1));
    }

    @Test
    void theLogStartIsAskedForWithinTheDeadlineOfTheRun() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Duration> asked = new ArrayList<>();
        MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions,
                                                                 Duration timeout) {
                asked.add(timeout);
                return super.beginningOffsets(partitions, timeout);
            }
        };
        Node node = new Node(0, "localhost", 9092);
        c.updatePartitions(layout.episodes(), List.of(new PartitionInfo(layout.episodes(), 0, node, new Node[]{node}, new Node[]{node})));
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        c.updateBeginningOffsets(Map.of(tp, 0L));
        c.updateEndOffsets(Map.of(tp, 3L));
        c.schedulePollTask(() -> c.updateBeginningOffsets(Map.of(tp, 3L)));
        StatsProcessor p = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);

        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> runCapturingStderr(p, new ByteArrayOutputStream(), Duration.ofSeconds(5)));

        assertEquals(2, asked.size(), "once at the start, once after the empty poll: " + asked);
        assertTrue(asked.get(1).compareTo(Duration.ofSeconds(5)) < 0,
                "the check inside the run gets the time left, not the whole run timeout: " + asked.get(1));
    }

    /** {@code tp:offset} for every partition, sorted by topic and partition, as the processor logs positions. */
    private static String positions(Map<TopicPartition, Long> positions) {
        return positions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> a.topic().equals(b.topic())
                        ? Integer.compare(a.partition(), b.partition()) : a.topic().compareTo(b.topic())))
                .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(","));
    }

    @Test
    void aContinuousRunLogsOneProgressLinePerCheckpointWithTheCounters() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("progress", Duration.ofMinutes(20), 1));
        MockConsumer<byte[], byte[]> c = consumerWith(layout, eps);
        TopicPartition first = new TopicPartition(layout.episodes(), Math.floorMod(eps.get(0).getSeries().getSeriesId().hashCode(), 2));
        append(c, first, "not avro".getBytes(StandardCharsets.UTF_8), new OpenTsxAvro().encode(eps.get(0)));
        MockProducer<String, byte[]> out = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        StatsProcessor p = new StatsProcessor(c, out, layout, new StatsEngine(StatsEngine.Config.defaults()), null, false);
        int[] loops = {0};
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            // a checkpoint period of zero ends a period on every loop: the episodes, then the bad record and the
            // duplicate, then nothing
            p.run(Duration.ZERO, () -> loops[0]++ == 3);
        } finally {
            System.setErr(stderr);
        }

        String progress = "INFO org.opentsx.stats.StatsProcessor - progress records=";
        String written = " lostToRetention=0 statsWritten=" + out.history().size();
        assertEquals(List.of(
                        progress + eps.size() + " undecodable=0 invalid=0" + written + " duplicates=0 lateValues=0",
                        progress + (eps.size() + 2) + " undecodable=1 invalid=0" + written + " duplicates=1 lateValues=0",
                        progress + (eps.size() + 2) + " undecodable=1 invalid=0" + written + " duplicates=1 lateValues=0"),
                logLines(err).stream().filter(l -> l.startsWith(progress)).toList(), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aContinuousRunLogsTheRestoredCheckpointTheAssignmentAndTheFinalCheckpoint(@TempDir Path dir) {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("lifecycle", Duration.ofMinutes(20), 2));
        Path checkpoint = dir.resolve("stats.ckpt");
        StatsProcessor before = new StatsProcessor(consumerWith(layout, eps.subList(0, eps.size() / 2)),
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false);
        before.runUntilCaughtUp(Duration.ofSeconds(30), false);
        String saved = positions(before.positions());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        StatsProcessor p;
        try {
            p = new StatsProcessor(consumerWith(layout, eps), new MockProducer<>(true, new StringSerializer(),
                    new ByteArraySerializer()), layout, new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false);
            int[] loops = {0};
            p.run(Duration.ofHours(1), () -> loops[0]++ == 1);
        } finally {
            System.setErr(stderr);
        }

        assertEquals(2, saved.split(",").length, saved);
        String info = "INFO org.opentsx.stats.StatsProcessor - ";
        assertEquals(List.of(
                        info + "checkpoint restored path=" + checkpoint + " domain=" + layout.domain() + " positions=" + saved,
                        info + "partitions assigned count=2 start=" + saved,
                        info + "final checkpoint written path=" + checkpoint + " positions=" + positions(p.positions())),
                logLines(err));
        assertEquals(eps.size(), p.positions().values().stream().mapToLong(Long::longValue).sum(), "every record was read");
    }

    @Test
    void aRunWithoutACheckpointFileOrPartitionsSaysSo() {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        StatsProcessor.RunResult r;
        try {
            r = new StatsProcessor(new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                    new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                    new StatsEngine(StatsEngine.Config.defaults()), null, true).runUntilCaughtUp(Duration.ofSeconds(5), true);
        } finally {
            System.setErr(stderr);
        }

        assertEquals(0, r.records());
        assertEquals(List.of(
                        "INFO org.opentsx.stats.StatsProcessor - no checkpoint; reading the topics from the log start path=none",
                        "WARN org.opentsx.stats.StatsProcessor - no partitions assigned; the input topics do not exist or have"
                                + " no partitions topics=" + layout.episodes() + "," + layout.observations()),
                logLines(err));
    }

    @Test
    void anUnreadableOrUnwritableCheckpointNamesTheFile(@TempDir Path dir) throws IOException {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        Path empty = Files.createFile(dir.resolve("stats.ckpt"));
        UncheckedIOException read = assertThrows(UncheckedIOException.class, () -> new StatsProcessor(
                new MockConsumer<>(OffsetResetStrategy.EARLIEST), new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), empty, false));
        assertEquals("cannot read checkpoint " + empty, read.getMessage());
        assertInstanceOf(EOFException.class, read.getCause());

        Path other = dir.resolve("other.ckpt");
        new StatsProcessor(new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), new TopicLayout("solar"),
                new StatsEngine(StatsEngine.Config.defaults()), other, false).checkpoint();
        UncheckedIOException domain = assertThrows(UncheckedIOException.class, () -> new StatsProcessor(
                new MockConsumer<>(OffsetResetStrategy.EARLIEST), new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                layout, new StatsEngine(StatsEngine.Config.defaults()), other, false));
        assertEquals("cannot read checkpoint " + other, domain.getMessage());
        assertEquals("checkpoint belongs to domain solar, not " + layout.domain(), domain.getCause().getMessage());
        byte[] whole = Files.readAllBytes(other);
        Path cut = Files.write(dir.resolve("cut.ckpt"), Arrays.copyOf(whole, whole.length - 1));
        UncheckedIOException truncated = assertThrows(UncheckedIOException.class, () -> new StatsProcessor(
                new MockConsumer<>(OffsetResetStrategy.EARLIEST), new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()),
                new TopicLayout("solar"), new StatsEngine(StatsEngine.Config.defaults()), cut, false));
        assertEquals("cannot read checkpoint " + cut, truncated.getMessage());
        assertInstanceOf(EOFException.class, truncated.getCause());

        Path noDirectory = dir.resolve("missing").resolve("stats.ckpt");
        StatsProcessor p = new StatsProcessor(new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                new StatsEngine(StatsEngine.Config.defaults()), noDirectory, false);
        UncheckedIOException write = assertThrows(UncheckedIOException.class, p::checkpoint);
        assertEquals("cannot write checkpoint " + noDirectory, write.getMessage());
    }

    @Test
    void recordsLostToRetentionAreCountedAndReported(@TempDir Path dir) {
        TopicLayout layout = new TopicLayout("t" + UUID.randomUUID().toString().substring(0, 8));
        List<Episode> eps = simulate(scenario("retention", Duration.ofHours(1), 2));
        Path checkpoint = dir.resolve("stats.ckpt");
        StatsProcessor first = new StatsProcessor(consumerWith(layout, eps.subList(0, eps.size() / 2)),
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false);
        first.runUntilCaughtUp(Duration.ofSeconds(30), false);
        TopicPartition p0 = new TopicPartition(layout.episodes(), 0);
        TopicPartition p1 = new TopicPartition(layout.episodes(), 1);
        long saved = first.positions().get(p0);
        Map<TopicPartition, Long> logStart = Map.of(p0, saved + 3, p1, first.positions().get(p1));

        MockConsumer<byte[], byte[]> c = consumerWith(layout, eps);
        c.updateBeginningOffsets(logStart);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        StatsProcessor.RunResult r;
        try {
            r = new StatsProcessor(c, new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                    new StatsEngine(StatsEngine.Config.defaults()), checkpoint, false).runUntilCaughtUp(Duration.ofSeconds(30), true);
        } finally {
            System.setErr(stderr);
        }
        assertEquals(3, r.lostToRetention());
        assertEquals(List.of(retention(p0, saved, saved + 3)), warnings(err));

        MockConsumer<byte[], byte[]> fresh = consumerWith(layout, eps);
        fresh.updateBeginningOffsets(logStart);
        StatsProcessor.RunResult none = new StatsProcessor(fresh,
                new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), layout,
                new StatsEngine(StatsEngine.Config.defaults()), null, false).runUntilCaughtUp(Duration.ofSeconds(30), true);
        assertEquals(0, none.lostToRetention(), "without a saved position nothing was lost");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
    void againstKafScale(@TempDir Path dir) throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        TopicLayout layout = new TopicLayout("stats" + UUID.randomUUID().toString().substring(0, 8));
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            layout.provision(admin, 3, (short) 1);
        }
        Scenario s = scenario("kafscale-stats", Duration.ofMinutes(30), 2);
        List<Episode> eps = simulate(s);
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap);
             BucketWriter w = BucketWriter.open(producer, layout, new InMemoryEpisodeIndex(), "stats-it", Map.of())) {
            eps.forEach(w::append);
        }
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            StatsProcessor p = new StatsProcessor(consumer, producer, layout,
                    new StatsEngine(StatsEngine.Config.defaults()), dir.resolve("ckpt"), false);
            StatsProcessor.RunResult r = p.runUntilCaughtUp(Duration.ofMinutes(2), true);
            assertEquals(eps.size(), r.records());
            List<SeriesStats> stats = SeriesStatsLog.readLatest(consumer, layout, Duration.ofMinutes(1));
            Map<String, Long> counts = new HashMap<>();
            stats.stream().filter(x -> x.getWindowType().equals("PT10M"))
                    .forEach(x -> counts.merge(x.getSeriesId().toString(), x.getValidCount() + x.getNanCount(), Long::sum));
            assertEquals(eps.stream().map(e -> e.getSeries().getSeriesId()).distinct().count(), counts.size());
            assertTrue(counts.values().stream().allMatch(c -> c == 1800), counts::toString);
        }
    }
}
