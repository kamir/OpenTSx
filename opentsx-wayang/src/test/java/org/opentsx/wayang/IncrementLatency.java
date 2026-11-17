package org.opentsx.wayang;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class IncrementLatency {

    static final ObjectMapper JSON = new ObjectMapper();
    static final List<com.fasterxml.jackson.databind.JsonNode> NODES;
    static final SeriesStream.Target TARGET = new SeriesStream.Target("latency", "wind", "clean", "SYNTHETIC", "latency", "a".repeat(64));

    static {
        try {
            NODES = JSON.readValue("""
                    [{"id":"check","op":"dq.checks","params":{"checks":["PLAUSIBLE_RANGE"],"min":0,"max":1000000,"mode":"gap"}},
                     {"id":"minute","op":"resample","params":{"every":"PT1M","agg":"MEAN"}}]
                    """, JSON.getTypeFactory().constructCollectionType(List.class, com.fasterxml.jackson.databind.JsonNode.class));
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    record Sample(int increment, long scheduled, long start, long fetch, long end, long records) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("expected backend-port output-directory chain interval-seconds increments");
        int backend = Integer.parseInt(args[0]);
        Path output = Path.of(args[1]);
        String chain = args[2];
        if (!Set.of("passthrough", "clean").contains(chain)) throw new IllegalArgumentException("unknown chain " + chain);
        long interval = Duration.ofSeconds(Long.parseLong(args[3])).toNanos();
        int count = Integer.parseInt(args[4]);
        if (interval <= 0 || count <= 0) throw new IllegalArgumentException("interval and increments must be positive");
        List<Episode> input = KafScaleFixture.scenarioW();
        if (input.size() < count) throw new IllegalArgumentException("the input must populate every increment");
        Files.createDirectories(output);
        try (LatencyProtocolObserver observer = new LatencyProtocolObserver(backend)) {
            String bootstrap = observer.bootstrap();
            TopicLayout layout = new TopicLayout("latency");
            try (var admin = KafScaleClients.admin(bootstrap)) {
                layout.provision(admin, 1, (short) 1);
                layout.provisionTopic(admin, layout.clean(), (short) 1);
            }
            TsxIncrement.Config config = new TsxIncrement.Config(bootstrap, "latency", 1, TARGET.specHash(),
                    layout.episodes(), KafScaleFixture.EPISODE_ONLY, "earliest", output.resolve("checkpoint.json"));
            List<Sample> samples = new ArrayList<>();
            List<CompletableFuture<Void>> ready = new ArrayList<>();
            for (int i = 0; i < count; i++) ready.add(new CompletableFuture<>());
            try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
                producer.partitionsFor(layout.episodes());
                long origin = System.nanoTime();
                var writer = Executors.newSingleThreadExecutor();
                Future<?> feeding = writer.submit(() -> {
                    try {
                        OpenTsxAvro avro = new OpenTsxAvro();
                        for (int period = 0; period < count; period++) {
                            int from = period * input.size() / count;
                            int to = (period + 1) * input.size() / count;
                            List<Future<?>> sent = new ArrayList<>();
                            for (int index = from; index < to; index++) {
                                long phase = (long) (interval * (0.05 + 0.85 * (index - from + 0.5) / (to - from)));
                                waitUntil(origin + period * interval + phase);
                                sent.add(producer.send(JavaTsxTopicSink.toRecord(avro, layout.episodes(), input.get(index))));
                            }
                            producer.flush();
                            for (Future<?> future : sent) future.get();
                            ready.get(period).complete(null);
                        }
                    } catch (Exception e) {
                        ready.forEach(future -> future.completeExceptionally(e));
                        throw new IllegalStateException("input pacing failed", e);
                    }
                });
                try {
                    for (int i = 0; i < count; i++) {
                        long scheduled = origin + (i + 1L) * interval;
                        waitUntil(scheduled);
                        ready.get(i).get();
                        observer.check();
                        observer.begin(layout.episodes(), i);
                        long started = System.nanoTime();
                        TsxIncrement.Result result = TsxIncrement.run(config, (source, state) -> {
                            TsxTopicSink sink = new TsxTopicSink(bootstrap, layout.clean());
                            if (chain.equals("passthrough")) source.connectTo(0, sink, 0);
                            else {
                                TsxSeriesChain clean = new TsxSeriesChain("minute", NODES, TARGET, List.of(), state);
                                source.connectTo(0, clean, 0);
                                clean.connectTo(0, sink, 0);
                            }
                            return sink;
                        });
                        long end = System.nanoTime();
                        long fetch = observer.end();
                        samples.add(new Sample(i, scheduled, started, fetch, end, result.records()));
                        System.out.println("increment " + (i + 1) + "/" + count + ", records " + result.records());
                    }
                    feeding.get();
                } finally {
                    writer.shutdownNow();
                }
                validate(bootstrap, layout.clean(), chain, input);
                observer.check();
                write(output, chain, input, layout, observer.entries, samples, origin);
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("case.json").toFile(), Map.of(
                        "chain", chain, "intervalSeconds", interval / 1_000_000_000L, "increments", count,
                        "inputRecords", input.size(), "partitions", 1, "completedAt", Instant.now().toString(),
                        "originNanos", origin, "clock", "System.nanoTime; increment clocks relative to originNanos; Produce clocks absolute",
                        "referenceEquality", "episode ids, series keys, timestamps and values; passthrough payload bytes"));
            }
        }
    }

    static void waitUntil(long deadline) throws InterruptedException {
        long rest;
        while ((rest = deadline - System.nanoTime()) > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(rest);
    }

    static String rawIdentity(SeriesKey key) {
        Map<String, String> tags = new TreeMap<>();
        key.getTags().forEach((k, v) -> {
            if (!Set.of("system", "signal", "grade", "flow", "feature").contains(k.toString())) tags.put(k.toString(), v.toString());
        });
        return key.getMetric() + "|" + tags;
    }

    static List<Episode> contributors(Episode output, List<Episode> input) {
        String identity = rawIdentity(output.getSeries());
        long from = Micros.of(output.getTStart());
        long to = Micros.of(output.getTEnd());
        List<Episode> matches = input.stream().filter(e -> rawIdentity(e.getSeries()).equals(identity)
                && Micros.of(e.getTStart()) < to && Micros.of(e.getTEnd()) > from).toList();
        if (matches.isEmpty()) throw new IllegalStateException("no input provenance for " + output.getEpisodeId());
        return matches;
    }

    static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) throw new IllegalArgumentException("percentile needs at least one sample");
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(q * sorted.size()) - 1));
    }

    static void validate(String bootstrap, String topic, String chain, List<Episode> input) {
        Map<String, Episode> expected = new TreeMap<>();
        SeriesStream reference = new SeriesStream(CleanChain.of(NODES), TARGET, List.of());
        for (Episode episode : input) {
            List<Episode> out = chain.equals("passthrough") ? List.of(episode) : reference.add(episode);
            for (Episode result : out) {
                if (expected.put(result.getEpisodeId(), result) != null) throw new IllegalStateException("duplicate reference id");
            }
        }
        Map<String, Episode> actual = new TreeMap<>();
        OpenTsxAvro avro = new OpenTsxAvro();
        try (KafkaConsumer<byte[], byte[]> reader = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = reader.partitionsFor(topic).stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            Map<TopicPartition, Long> begin = reader.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = reader.endOffsets(partitions);
            Map<TopicPartition, long[]> ranges = new HashMap<>();
            partitions.forEach(p -> ranges.put(p, new long[]{begin.get(p), end.get(p)}));
            LogReader.read(reader, ranges, record -> {
                Episode episode = avro.decode(record.value(), Episode.class);
                if (actual.put(episode.getEpisodeId(), episode) != null) throw new IllegalStateException("duplicate output id");
                Episode want = expected.get(episode.getEpisodeId());
                if (want == null || !want.getSeries().equals(episode.getSeries())
                        || !Arrays.equals(Episodes.timestampsMicros(want), Episodes.timestampsMicros(episode))
                        || !Arrays.equals(Episodes.values(want), Episodes.values(episode))) {
                    throw new IllegalStateException("reference mismatch for " + episode.getEpisodeId());
                }
                if (chain.equals("passthrough") && !Arrays.equals(record.value(), avro.encode(JavaTsxTopicSink.canonical(want)))) {
                    throw new IllegalStateException("passthrough bytes differ for " + episode.getEpisodeId());
                }
            }, Duration.ofMinutes(5));
        }
        if (!expected.keySet().equals(actual.keySet())) throw new IllegalStateException("output id sets differ from reference");
    }

    static void row(PrintWriter out, Object... values) {
        out.println(Arrays.stream(values).map(v -> "\"" + v.toString().replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(",")));
        if (out.checkError()) throw new IllegalStateException("CSV write failed");
    }

    static void write(Path directory, String chain, List<Episode> input, TopicLayout layout,
                      List<LatencyProtocolObserver.Entry> observed, List<Sample> samples, long origin) throws Exception {
        List<LatencyProtocolObserver.Entry> entries = List.copyOf(observed);
        Map<String, LatencyProtocolObserver.Entry> inputs = new HashMap<>();
        for (var e : entries) {
            if (e.error() == 0 && e.topic().equals(layout.episodes())) inputs.merge(e.episode().getEpisodeId(), e,
                    (a, b) -> a.requestNanos() < b.requestNanos() ? a : b);
        }
        if (inputs.size() != input.size()) throw new IllegalStateException("not every input has an acknowledged wire observation");
        Map<Integer, List<Double>> latency = new HashMap<>();
        try (PrintWriter records = new PrintWriter(Files.newBufferedWriter(directory.resolve("records.csv")));
             PrintWriter attempts = new PrintWriter(Files.newBufferedWriter(directory.resolve("produce-attempts.csv")));
             PrintWriter increments = new PrintWriter(Files.newBufferedWriter(directory.resolve("increments.csv")))) {
            row(records, "increment", "output_id", "input_ids", "input_request_ns", "input_ack_ns", "output_request_ns", "output_ack_ns", "latency_ms", "output_partition", "output_offset");
            row(attempts, "topic", "partition", "offset", "episode_id", "request_ns", "ack_ns", "at", "increment", "error_code");
            for (var e : entries) {
                row(attempts, e.topic(), e.partition(), e.offset(), e.episode().getEpisodeId(), e.requestNanos(), e.acknowledgedNanos(), e.at(), e.increment(), e.error());
                if (e.error() != 0 || !e.topic().equals(layout.clean())) continue;
                List<Episode> source = chain.equals("passthrough")
                        ? List.of(inputs.get(e.episode().getEpisodeId()).episode()) : contributors(e.episode(), input);
                var latestInput = source.stream().map(s -> inputs.get(s.getEpisodeId()))
                        .max(java.util.Comparator.comparingLong(LatencyProtocolObserver.Entry::requestNanos)).orElseThrow();
                long latest = latestInput.requestNanos();
                double ms = (e.requestNanos() - latest) / 1_000_000.0;
                if (ms < 0 || e.increment() < 0) throw new IllegalStateException("invalid output timing");
                latency.computeIfAbsent(e.increment(), ignored -> new ArrayList<>()).add(ms);
                row(records, e.increment(), e.episode().getEpisodeId(), source.stream().map(Episode::getEpisodeId).collect(java.util.stream.Collectors.joining(";")),
                        latest, latestInput.acknowledgedNanos(), e.requestNanos(), e.acknowledgedNanos(), ms, e.partition(), e.offset());
            }
            row(increments, "increment", "scheduled_ns", "start_ns", "first_fetch_ns", "end_ns", "lateness_ms", "first_fetch_ms", "wall_ms", "records", "outputs", "p50_ms", "p95_ms", "p99_ms");
            for (Sample sample : samples) {
                List<Double> values = latency.getOrDefault(sample.increment(), List.of());
                row(increments, sample.increment(), sample.scheduled() - origin, sample.start() - origin, sample.fetch() - origin,
                        sample.end() - origin, (sample.start() - sample.scheduled()) / 1_000_000.0,
                        (sample.fetch() - sample.start()) / 1_000_000.0, (sample.end() - sample.start()) / 1_000_000.0,
                        sample.records(), values.size(), values.isEmpty() ? "" : percentile(values, 0.50),
                        values.isEmpty() ? "" : percentile(values, 0.95), values.isEmpty() ? "" : percentile(values, 0.99));
            }
        }
    }
}
