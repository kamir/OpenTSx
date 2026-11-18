package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;
import org.opentsx.retrieval.vector.DagPlan;
import org.opentsx.retrieval.vector.VectorProcessor;
import org.opentsx.stats.StatsEngine;
import org.opentsx.stats.StatsProcessor;

import java.io.DataInputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class StreamingEvaluation {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    static final TopicLayout LAYOUT = new TopicLayout("a5evaluation");
    static final StatsEngine.Config STATS = StatsEngine.Config.defaults();

    private StreamingEvaluation() { }

    public static void main(String[] args) throws Exception {
        Instant mainEnteredAt = Instant.now();
        if (args.length != 5) throw new IllegalArgumentException("expected broker-port output-directory chain runtime partitions");
        int port = Integer.parseInt(args[0]);
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        String chain = args[2];
        String runtime = args[3];
        int partitions = Integer.parseInt(args[4]);
        validateArguments(port, chain, runtime, partitions);
        if (!Files.isDirectory(output)) throw new IllegalArgumentException("evaluation output directory must exist");
        for (String name : List.of("case.json", "measurement.csv", "records.csv", "produce-attempts.csv")) {
            if (Files.exists(output.resolve(name))) throw new IllegalArgumentException("evaluation output already exists: " + name);
        }
        List<Episode> input = KafScaleFixture.scenarioW();
        String topic = chain.equals("stats") ? LAYOUT.seriesStats() : LAYOUT.clean();
        ObjectNode document = document(chain, input);
        String text = JSON.writeValueAsString(document);
        String hash = ExecutionAssignments.sha256(text);
        Path checkpoint = output.resolve("checkpoint.json");
        try (LatencyProtocolObserver observer = new LatencyProtocolObserver(port, Set.of(LAYOUT.episodes(), topic))) {
            String bootstrap = observer.bootstrap();
            try (var admin = KafScaleClients.admin(bootstrap)) {
                LAYOUT.provision(admin, partitions, (short) 1);
                if (!topic.equals(LAYOUT.seriesStats())) {
                    admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)
                            .configs(Map.of("compression.type", "zstd", "cleanup.policy", "delete"))))
                            .all().get(30, TimeUnit.SECONDS);
                }
                LAYOUT.provisionDagTopics(admin, (short) 1);
            }
            try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
                List<Future<?>> sent = new ArrayList<>();
                OpenTsxAvro avro = new OpenTsxAvro();
                for (Episode episode : input) sent.add(producer.send(JavaTsxTopicSink.toRecord(avro, LAYOUT.episodes(), episode)));
                producer.flush();
                for (Future<?> future : sent) future.get();
            }
            Map<TopicPartition, Long> ends = endOffsets(bootstrap, LAYOUT.episodes());
            if (ends.size() != partitions || ends.values().stream().mapToLong(Long::longValue).sum() != input.size()) {
                throw new IllegalStateException("input partition inventory differs from W");
            }
            observer.check();
            observer.begin(LAYOUT.episodes(), 0);
            Instant startedAt = Instant.now();
            long started = System.nanoTime();
            long cpuStart = cpuNanos();
            if (runtime.equals("wayang")) runWayang(bootstrap, chain, text, hash, checkpoint);
            else if (chain.equals("passthrough")) runDecode(bootstrap, checkpoint, ends);
            else if (chain.equals("clean")) runVector(bootstrap, text, output.resolve("processor"), input.size(), ends);
            else runStatistics(bootstrap, checkpoint, ends, input.size());
            long ended = System.nanoTime();
            Instant jobEndedAt = Instant.now();
            long cpu = Math.subtractExact(cpuNanos(), cpuStart);
            long fetch = observer.end();
            if (runtime.equals("wayang")) verifyWayangCheckpoint(checkpoint, hash, ends);
            else if (chain.equals("clean")) verifyVectorCheckpoint(output.resolve("processor"), ends);
            else if (chain.equals("stats")) verifyStatisticsCheckpoint(checkpoint, ends);
            else verifyPositions(JSON.readTree(checkpoint.toFile()).path("next"), ends, false);
            List<LatencyProtocolObserver.RecordEntry> observed = List.copyOf(observer.recordEntries);
            observer.check();
            if (observed.stream().anyMatch(entry -> entry.error() != 0)) throw new IllegalStateException("an observed Produce acknowledgement failed");
            Map<String, SpecificRecord> actual = read(bootstrap, topic);
            validate(chain, document, hash, input, actual);
            write(output, chain, runtime, partitions, hash, mainEnteredAt, startedAt, jobEndedAt,
                    started, ended, cpu, fetch, topic, input, observed, actual);
        }
    }

    static void validateArguments(int port, String chain, String runtime, int partitions) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("broker port must be between 1 and 65535");
        if (!Set.of("passthrough", "clean", "stats").contains(chain)) throw new IllegalArgumentException("unknown evaluation chain " + chain);
        if (!Set.of("wayang", "java").contains(runtime)) throw new IllegalArgumentException("unknown evaluation runtime " + runtime);
        if (!Set.of(1, 4, 12).contains(partitions)) throw new IllegalArgumentException("evaluation partitions must be 1, 4 or 12");
    }

    static ObjectNode document(String chain, List<Episode> input) throws Exception {
        ObjectNode dag = JSON.createObjectNode().put("dag", "a5-evaluation").put("version", 1);
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker").put("start", "earliest");
        source.putArray("topics").add(LAYOUT.episodes());
        var series = source.putArray("series");
        input.stream().map(e -> e.getSeries().getSeriesId().toString()).distinct().sorted().forEach(series::add);
        var nodes = dag.putArray("nodes");
        nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        String last = "decode";
        if (chain.equals("clean")) {
            ObjectNode check = nodes.addObject().put("id", "check").put("op", "dq.checks");
            check.putArray("in").add(last);
            ObjectNode params = check.putObject("params").put("min", 0).put("max", 1000000).put("mode", "gap");
            params.putArray("checks").add("PLAUSIBLE_RANGE");
            ObjectNode sample = nodes.addObject().put("id", "sample").put("op", "resample");
            sample.putArray("in").add("check");
            sample.putObject("params").put("every", "PT1M").put("agg", "MEAN");
            last = "sample";
        } else if (chain.equals("stats")) {
            ObjectNode statistics = nodes.addObject().put("id", "statistics").put("op", "stats.welford-ddsketch");
            statistics.putArray("in").add(last);
            statistics.putObject("params").put("alpha", 0.01).putArray("windows").add("PT10M").add("PT1H");
            last = "statistics";
        }
        ObjectNode sink = dag.putArray("sinks").addObject().put("from", last)
                .put("topic", chain.equals("stats") ? LAYOUT.seriesStats() : LAYOUT.clean())
                .put("schema", chain.equals("stats") ? "opentsx.series-stats.v2" : "opentsx.episode.v2").put("origin", "SYNTHETIC");
        if (!chain.equals("stats")) sink.putObject("signal").put("system", "ostsee").put("id", "wind").put("grade", "clean");
        return dag;
    }

    private static void runWayang(String bootstrap, String chain, String document, String hash, Path checkpoint) throws Exception {
        TsxIncrement.Config config = new TsxIncrement.Config(bootstrap, "a5-evaluation", 1, hash, LAYOUT.episodes(),
                KafScaleFixture.EPISODE_ONLY, "earliest", checkpoint);
        TsxIncrement.Plan plan;
        if (chain.equals("clean")) {
            DagToWayang compiled = DagToWayang.compile(JSON.readTree(document), LAYOUT, hash, Path.of("..", "systems", "processors"));
            plan = compiled.plan(bootstrap, compiled.series().get(0).name());
        } else {
            plan = (source, state) -> {
                TsxTopicSink sink = new TsxTopicSink(bootstrap, chain.equals("stats") ? LAYOUT.seriesStats() : LAYOUT.clean());
                if (chain.equals("stats")) {
                    TsxStatsWindow statistics = TsxStatsWindow.bounded("statistics", STATS, state);
                    source.connectTo(0, statistics, 0);
                    statistics.connectTo(0, sink, 0);
                } else source.connectTo(0, sink, 0);
                return sink;
            };
        }
        TsxIncrement.run(config, plan);
    }

    private static void runDecode(String bootstrap, Path checkpoint, Map<TopicPartition, Long> ends) throws Exception {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Future<?>> sent = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            LogReader.read(consumer, ranges(ends), record -> {
                Episode episode = avro.decode(record.value(), Episode.class);
                sent.add(producer.send(JavaTsxTopicSink.toRecord(avro, LAYOUT.clean(), episode)));
            }, TIMEOUT);
            producer.flush();
            for (Future<?> future : sent) future.get();
        }
        ObjectNode saved = JSON.createObjectNode().put("schema", "opentsx.java-decode-checkpoint.v1");
        ObjectNode next = saved.putObject("next");
        ends.forEach((partition, offset) -> next.put(Integer.toString(partition.partition()), offset));
        Files.write(checkpoint, JSON.writeValueAsBytes(saved), StandardOpenOption.CREATE_NEW);
    }

    private static void runVector(String bootstrap, String document, Path directory, long inputCount,
                                  Map<TopicPartition, Long> ends) throws Exception {
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            producer.send(new ProducerRecord<>(LAYOUT.dags(), "a5-evaluation",
                    VectorProcessor.deployRecord("a5-evaluation", document, "evaluation"))).get(30, TimeUnit.SECONDS);
        }
        AtomicLong consumed = new AtomicLong();
        try (VectorProcessor processor = new VectorProcessor(() -> new KafkaConsumer<>(KafScaleClients.consumerProps(bootstrap),
                new ByteArrayDeserializer(), new ByteArrayDeserializer()) {
            @Override public ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
                ConsumerRecords<byte[], byte[]> records = super.poll(timeout);
                consumed.addAndGet(count(records));
                return records;
            }
        }, () -> KafScaleClients.producer(bootstrap), LAYOUT, directory, VectorProcessor.Timing.standard())) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (consumed.get() < inputCount) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("timed out catching up the vector processor");
                processor.step();
                if (processor.states().containsValue("failed")) throw new IllegalStateException("the vector processor failed");
            }
            if (consumed.get() != inputCount) throw new IllegalStateException("the vector processor consumed duplicate inputs");
            if (!processor.states().equals(Map.of("a5-evaluation/wind", "running"))) {
                throw new IllegalStateException("the vector processor did not finish in running state");
            }
        }
    }

    private static void verifyVectorCheckpoint(Path directory, Map<TopicPartition, Long> ends) throws Exception {
        JsonNode saved = JSON.readTree(directory.resolve("a5-evaluation--wind.json").toFile());
        if (saved.path("stream").path("skippedBlocks").asLong() != 0) throw new IllegalStateException("the vector processor skipped input blocks");
        verifyPositions(saved.path("positions"), ends, true);
    }

    private static long count(ConsumerRecords<byte[], byte[]> records) {
        long count = 0;
        for (var ignored : records.records(LAYOUT.episodes())) count++;
        return count;
    }

    private static void runStatistics(String bootstrap, Path checkpoint, Map<TopicPartition, Long> ends, long inputCount) throws Exception {
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap);
             KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            StatsProcessor processor = new StatsProcessor(consumer, producer, LAYOUT, new StatsEngine(STATS), checkpoint, false);
            StatsProcessor.RunResult result = processor.runUntilCaughtUp(TIMEOUT, true);
            if (result.records() != inputCount || result.undecodable() != 0 || !processor.positions().equals(ends)) {
                throw new IllegalStateException("the statistics processor did not consume the exact input cut");
            }
        }
    }

    private static void verifyStatisticsCheckpoint(Path checkpoint, Map<TopicPartition, Long> ends) throws Exception {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(checkpoint))) {
            if (!in.readUTF().equals("TSXSTATS1") || !in.readUTF().equals(LAYOUT.domain())) {
                throw new IllegalStateException("statistics checkpoint identity differs");
            }
            int count = in.readInt();
            Map<TopicPartition, Long> positions = new HashMap<>();
            for (int i = 0; i < count; i++) positions.put(new TopicPartition(in.readUTF(), in.readInt()), in.readLong());
            if (!positions.equals(ends)) throw new IllegalStateException("statistics checkpoint does not cover the input cut");
            new StatsEngine(STATS).readState(in);
            if (in.read() != -1) throw new IllegalStateException("statistics checkpoint has trailing data");
        }
    }

    static void verifyPositions(JsonNode saved, Map<TopicPartition, Long> ends, boolean topicKeys) {
        if (!saved.isObject() || saved.size() != ends.size()) throw new IllegalStateException("checkpoint partition inventory differs");
        ends.forEach((partition, offset) -> {
            String key = topicKeys ? partition.topic() + "|" + partition.partition() : Integer.toString(partition.partition());
            JsonNode value = saved.get(key);
            if (value == null || !value.isIntegralNumber() || value.longValue() != offset) {
                throw new IllegalStateException("checkpoint does not cover partition " + key);
            }
        });
    }

    private static void verifyWayangCheckpoint(Path path, String hash, Map<TopicPartition, Long> ends) {
        TsxCheckpoint checkpoint = TsxCheckpoint.load(path, hash).orElseThrow(() -> new IllegalStateException("Wayang checkpoint is missing"));
        Map<Integer, Long> expected = new TreeMap<>();
        ends.forEach((partition, offset) -> expected.put(partition.partition(), offset));
        if (!checkpoint.next().equals(expected)) throw new IllegalStateException("Wayang checkpoint differs from the input cut");
    }

    private static Map<TopicPartition, Long> endOffsets(String bootstrap, String topic) {
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            return consumer.endOffsets(partitions);
        }
    }

    private static Map<TopicPartition, long[]> ranges(Map<TopicPartition, Long> ends) {
        Map<TopicPartition, long[]> ranges = new HashMap<>();
        ends.forEach((partition, offset) -> ranges.put(partition, new long[]{0, offset}));
        return ranges;
    }

    private static Map<String, SpecificRecord> read(String bootstrap, String topic) {
        Map<String, SpecificRecord> records = new TreeMap<>();
        OpenTsxAvro avro = new OpenTsxAvro();
        Map<TopicPartition, Long> ends = endOffsets(bootstrap, topic);
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            LogReader.read(consumer, ranges(ends), record -> {
                SpecificRecord decoded = avro.decode(record.value());
                if (records.putIfAbsent(identity(decoded), decoded) != null) throw new IllegalStateException("duplicate output identity " + identity(decoded));
            }, TIMEOUT);
        }
        return records;
    }

    static String identity(SpecificRecord record) {
        if (record instanceof Episode episode) return episode.getEpisodeId();
        if (record instanceof SeriesStats statistics) return statistics.getSeriesId() + "|" + statistics.getWindowType()
                + "|" + Micros.of(statistics.getWindowStart()) + "|" + Micros.of(statistics.getWindowEnd());
        throw new IllegalArgumentException("unsupported evaluation record " + record.getSchema().getFullName());
    }

    static void validate(String chain, ObjectNode document, String hash, List<Episode> input, Map<String, SpecificRecord> actual) {
        Map<String, SpecificRecord> expected = new TreeMap<>();
        if (chain.equals("passthrough")) input.forEach(episode -> expected.put(identity(episode), episode));
        else if (chain.equals("clean")) {
            var job = DagPlan.of(document, LAYOUT, hash).series().get(0);
            SeriesStream reference = new SeriesStream(CleanChain.of(job.nodes()), job.target(), job.seriesIds());
            for (Episode episode : input) for (Episode cleaned : reference.add(episode)) {
                if (expected.putIfAbsent(identity(cleaned), cleaned) != null) throw new IllegalStateException("duplicate clean reference identity");
            }
            if (reference.skippedBlocks() != 0 || !reference.waitingFor().isEmpty()) throw new IllegalStateException("the clean reference skipped input series");
        } else {
            StatsEngine reference = new StatsEngine(STATS);
            for (Episode episode : input) for (SeriesStats statistics : reference.onEpisode(episode)) {
                if (expected.putIfAbsent(identity(statistics), statistics) != null) throw new IllegalStateException("duplicate statistics reference identity");
            }
            for (SeriesStats statistics : reference.flush()) {
                if (expected.putIfAbsent(identity(statistics), statistics) != null) throw new IllegalStateException("duplicate flushed statistics reference identity");
            }
            if (reference.metrics().duplicates() != 0 || reference.metrics().lateValues() != 0) {
                throw new IllegalStateException("the statistics reference received duplicate or late values");
            }
        }
        if (expected.isEmpty() || !expected.keySet().equals(actual.keySet())) throw new IllegalStateException("evaluation output identities differ from the reference");
        OpenTsxAvro avro = new OpenTsxAvro();
        expected.forEach((id, want) -> {
            SpecificRecord got = actual.get(id);
            if (want instanceof Episode episode) {
                if (!(got instanceof Episode other) || !episode.getSeries().equals(other.getSeries())
                        || !Arrays.equals(Episodes.timestampsMicros(episode), Episodes.timestampsMicros(other))
                        || !Arrays.equals(Episodes.values(episode), Episodes.values(other))) {
                    throw new IllegalStateException("evaluation episode differs from reference " + id);
                }
                if (chain.equals("passthrough") && !Arrays.equals(avro.encode(JavaTsxTopicSink.canonical(episode)),
                        avro.encode(JavaTsxTopicSink.canonical((Episode) got)))) {
                    throw new IllegalStateException("passthrough payload bytes differ " + id);
                }
            } else if (!Arrays.equals(avro.encode(want), avro.encode(got))) {
                throw new IllegalStateException("evaluation statistics payload differs from reference " + id);
            }
        });
    }

    static List<Episode> contributors(SpecificRecord record, String chain, List<Episode> input) {
        if (record instanceof Episode episode) {
            if (chain.equals("passthrough")) return input.stream().filter(e -> e.getEpisodeId().equals(episode.getEpisodeId())).toList();
            return IncrementLatency.contributors(episode, input);
        }
        SeriesStats statistics = (SeriesStats) record;
        long start = Micros.of(statistics.getWindowStart());
        long end = Micros.of(statistics.getWindowEnd());
        List<Episode> matches = input.stream().filter(episode -> episode.getSeries().getSeriesId().equals(statistics.getSeriesId())
                && Micros.of(episode.getTStart()) < end && Micros.of(episode.getTEnd()) > start).toList();
        if (matches.isEmpty()) throw new IllegalStateException("statistics output has no input provenance " + identity(record));
        return matches;
    }

    static long cpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().orElseThrow(() -> new IllegalStateException("process CPU duration is unavailable")).toNanos();
    }

    static void write(Path directory, String chain, String runtime, int partitions, String hash,
                              Instant mainEnteredAt, Instant startedAt, Instant jobEndedAt,
                              long started, long ended, long cpu, long fetch, String topic, List<Episode> input,
                              List<LatencyProtocolObserver.RecordEntry> observed, Map<String, SpecificRecord> actual) throws Exception {
        Map<String, LatencyProtocolObserver.RecordEntry> inputs = new HashMap<>();
        Map<String, LatencyProtocolObserver.RecordEntry> outputs = new HashMap<>();
        for (var record : observed) {
            if (!record.topic().equals(LAYOUT.episodes()) && !record.topic().equals(topic)) {
                throw new IllegalStateException("wire observation belongs to an unmeasured topic " + record.topic());
            }
            Map<String, LatencyProtocolObserver.RecordEntry> records = record.topic().equals(LAYOUT.episodes()) ? inputs : outputs;
            if (record.error() != 0 || records.putIfAbsent(identity(record.record()), record) != null) {
                throw new IllegalStateException("duplicate or failed Produce observation " + identity(record.record()));
            }
        }
        Set<String> inputIds = input.stream().map(Episode::getEpisodeId).collect(java.util.stream.Collectors.toSet());
        if (inputIds.size() != input.size() || !inputs.keySet().equals(inputIds) || !outputs.keySet().equals(actual.keySet())) {
            throw new IllegalStateException("wire observations do not cover the complete input and output");
        }
        List<Double> latencies = new ArrayList<>();
        long firstOutput = Long.MAX_VALUE;
        long lastAck = Long.MIN_VALUE;
        try (PrintWriter records = new PrintWriter(Files.newBufferedWriter(directory.resolve("records.csv"), StandardOpenOption.CREATE_NEW));
             PrintWriter attempts = new PrintWriter(Files.newBufferedWriter(directory.resolve("produce-attempts.csv"), StandardOpenOption.CREATE_NEW))) {
            IncrementLatency.row(records, "output_id", "input_ids", "input_request_ns", "input_ack_ns", "output_request_ns", "output_ack_ns", "latency_ms", "output_partition", "output_offset", "output_type");
            IncrementLatency.row(attempts, "topic", "partition", "offset", "identity", "type", "request_ns", "ack_ns", "error_code");
            for (var entry : observed) IncrementLatency.row(attempts, entry.topic(), entry.partition(), entry.offset(), identity(entry.record()),
                    entry.record().getSchema().getName(), entry.requestNanos(), entry.acknowledgedNanos(), entry.error());
            for (String id : new java.util.TreeSet<>(outputs.keySet())) {
                var output = outputs.get(id);
                List<Episode> source = contributors(output.record(), chain, input);
                var latest = source.stream().map(e -> inputs.get(e.getEpisodeId()))
                        .max(Comparator.comparingLong(LatencyProtocolObserver.RecordEntry::requestNanos)).orElseThrow();
                double latency = (output.requestNanos() - latest.requestNanos()) / 1_000_000.0;
                if (latency < 0 || output.requestNanos() < started || output.acknowledgedNanos() > ended) {
                    throw new IllegalStateException("output Produce clock is outside the acknowledged run");
                }
                latencies.add(latency);
                firstOutput = Math.min(firstOutput, output.requestNanos());
                lastAck = Math.max(lastAck, output.acknowledgedNanos());
                IncrementLatency.row(records, id, source.stream().map(Episode::getEpisodeId).collect(java.util.stream.Collectors.joining(";")),
                        latest.requestNanos(), latest.acknowledgedNanos(), output.requestNanos(), output.acknowledgedNanos(), latency,
                        output.partition(), output.offset(), output.record().getSchema().getName());
            }
        }
        long values = input.stream().mapToLong(episode -> Episodes.values(episode).length).sum();
        long outputValues = actual.values().stream().filter(Episode.class::isInstance).map(Episode.class::cast)
                .mapToLong(episode -> Episodes.values(episode).length).sum();
        long elapsed = Math.subtractExact(ended, started);
        int cores = Runtime.getRuntime().availableProcessors();
        if (elapsed <= 0 || cpu < 0 || fetch < started || fetch > ended || firstOutput == Long.MAX_VALUE || lastAck > ended) throw new IllegalStateException("evaluation clock evidence is incomplete");
        double episodesPerSecond = input.size() * 1_000_000_000.0 / elapsed;
        double valuesPerSecond = values * 1_000_000_000.0 / elapsed;
        try (PrintWriter measurement = new PrintWriter(Files.newBufferedWriter(directory.resolve("measurement.csv"), StandardOpenOption.CREATE_NEW))) {
            IncrementLatency.row(measurement, "wall_ms", "episodes_per_second", "values_per_second", "episodes_per_second_per_core", "values_per_second_per_core", "p50_ms", "p95_ms", "p99_ms");
            IncrementLatency.row(measurement, elapsed / 1_000_000.0, episodesPerSecond, valuesPerSecond, episodesPerSecond / cores, valuesPerSecond / cores,
                    IncrementLatency.percentile(latencies, 0.5), IncrementLatency.percentile(latencies, 0.95), IncrementLatency.percentile(latencies, 0.99));
        }
        ObjectNode summary = JSON.createObjectNode().put("schema", "opentsx.streaming-evaluation.v1")
                .put("chain", chain).put("runtime", runtime).put("nativeImplementation", runtime.equals("wayang") ? "TsxIncrement"
                        : chain.equals("passthrough") ? "java-decode" : chain.equals("clean") ? "VectorProcessor" : "StatsProcessor")
                .put("partitions", partitions).put("inputRecords", input.size()).put("inputValues", values)
                .put("inputTopic", LAYOUT.episodes()).put("outputTopic", topic)
                .put("outputRecords", actual.size()).put("outputValues", outputValues).put("correctnessPassed", true)
                .put("checkpointVerified", true).put("elapsedNanos", elapsed).put("cpuNanos", cpu).put("availableProcessors", cores)
                .put("startedNanos", started).put("endedNanos", ended).put("firstFetchNanos", fetch - started)
                .put("firstOutputNanos", firstOutput - started).put("lastOutputAckNanos", lastAck - started)
                .put("processStartedAt", ProcessHandle.current().info().startInstant()
                        .orElseThrow(() -> new IllegalStateException("process start instant is unavailable")).toString())
                .put("mainEnteredAt", mainEnteredAt.toString()).put("startedAt", startedAt.toString())
                .put("jobEndedAt", jobEndedAt.toString()).put("completedAt", Instant.now().toString()).put("operatorSpecHash", hash)
                .put("intervalSeconds", 0).put("inputSchedule", "complete acknowledged W backlog before job invocation")
                .put("clock", "System.nanoTime; producer request and acknowledgement clocks are absolute, other durations are relative")
                .put("checkpointProfile", chain.equals("clean") && runtime.equals("java") ? "VectorProcessor.Timing.standard with final acknowledged shutdown checkpoint" : "one bounded catch-up checkpoint")
                .put("referenceEquality", chain.equals("clean") ? "episode ids, series keys, sample timestamps and values; creation times excluded" : "complete Avro payload bytes by identity");
        Files.write(directory.resolve("case.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary), StandardOpenOption.CREATE_NEW);
        System.out.println("Streaming evaluation completed: " + chain + "/" + runtime + "/" + partitions + ", " + input.size() + " input records");
    }
}
