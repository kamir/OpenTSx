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
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
