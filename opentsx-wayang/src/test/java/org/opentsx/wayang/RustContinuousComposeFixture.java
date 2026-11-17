package org.opentsx.wayang;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class RustContinuousComposeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    static final String OUTPUT = "tsx.rust-continuous.clean.v2";
    private static final String PRODUCER = "tsx-runner/0.1.0";

    private RustContinuousComposeFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ready <bootstrap>");
            System.out.println("prepare <bootstrap> <fixture-directory>");
            System.out.println("append <bootstrap> <fixture-directory> <through-block-count>");
            System.out.println("verify <bootstrap> <fixture-directory> <deployment.json> <submitted-dag.txt> <through-block-count>");
        } else if (args.length == 2 && args[0].equals("ready")) {
            try (Admin admin = KafScaleClients.admin(args[1])) { admin.listTopics().names().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS); }
            System.out.println("Kafka metadata is ready.");
        } else if (args.length == 3 && args[0].equals("prepare")) prepare(args[1], Path.of(args[2]));
        else if (args.length == 4 && args[0].equals("append")) append(args[1], Path.of(args[2]), blocks(args[3]));
        else if (args.length == 6 && args[0].equals("verify")) verify(args[1], Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), blocks(args[5]));
        else throw new IllegalArgumentException("invalid continuous fixture arguments; use --help");
    }

    static int blocks(String value) {
        int blocks = Integer.parseInt(value);
        if (!Integer.toString(blocks).equals(value) || blocks < 1 || blocks > 5) throw new IllegalArgumentException("through-block-count must be within 1..5");
        return blocks;
    }

    static String input(int worker) { return "tsx.rust-continuous.worker-" + worker + ".episodes.v2"; }

    static Episode raw(int worker, int partition, int block) {
        if (worker < 1 || worker > 3 || partition < 0 || partition >= worker || block < 0 || block >= 5) {
            throw new IllegalArgumentException("invalid continuous fixture block");
        }
        double[] values = new double[3];
        for (int index = 0; index < values.length; index++) values[index] = RustRunnerComposeFixture.VALUES[(block * 3 + index) % RustRunnerComposeFixture.VALUES.length];
        return Episodes.regular(RustRunnerComposeFixture.key(worker, partition), RustRunnerComposeFixture.CREATED + block * 30_000_000L,
                        10_000_000L, values).episodeId("continuous-raw-" + worker + "-" + partition + "-" + block)
                .source("rust-continuous-fixture").createdAt(RustRunnerComposeFixture.CREATED).build();
    }

    private static void prepare(String bootstrap, Path directory) throws Exception {
        List<NewTopic> topics = new ArrayList<>();
        for (int worker = 1; worker <= 3; worker++) topics.add(new NewTopic(input(worker), worker, (short) 1));
        topics.add(new NewTopic(OUTPUT, 3, (short) 1));
        try (Admin admin = KafScaleClients.admin(bootstrap)) { admin.createTopics(topics).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS); }
        ObjectNode fixture = JSON.createObjectNode().put("schema", "opentsx.rust-continuous-fixture.v1")
                .put("outputTopic", OUTPUT).put("outputPartitions", 3).put("expectedInputRecords", 6).put("expectedInputSamples", 18)
                .put("tStartMicros", Long.toString(RustRunnerComposeFixture.CREATED)).put("system", "rust-demo").put("signal", "continuous");
        var inputTopics = fixture.putArray("inputTopics");
        var ids = fixture.putArray("seriesIds");
        var sources = fixture.putArray("sources");
        for (int worker = 1; worker <= 3; worker++) {
            inputTopics.add(input(worker));
            var series = sources.addObject().put("topic", input(worker)).put("partitions", worker).putArray("seriesIds");
            for (int partition = 0; partition < worker; partition++) {
                String id = RustRunnerComposeFixture.key(worker, partition).getSeriesId(); ids.add(id); series.add(id);
            }
        }
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("fixture.json").toFile(), fixture);
        append(bootstrap, directory, 1);
        System.out.println(JSON.writeValueAsString(fixture));
    }

    private static void append(String bootstrap, Path directory, int through) throws Exception {
        JsonNode fixture = JSON.readTree(directory.resolve("fixture.json").toFile());
        if (!fixture.path("schema").asText().equals("opentsx.rust-continuous-fixture.v1")) throw new IllegalStateException("continuous fixture schema mismatch");
        Map<TopicPartition, Long> next = new LinkedHashMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            for (int worker = 1; worker <= 3; worker++) {
                if (consumer.partitionsFor(input(worker), TIMEOUT).size() != worker) throw new IllegalStateException("input topic partition inventory changed");
                List<TopicPartition> partitions = new ArrayList<>();
                for (int partition = 0; partition < worker; partition++) partitions.add(new TopicPartition(input(worker), partition));
                var starts = consumer.beginningOffsets(partitions, TIMEOUT);
                var ends = consumer.endOffsets(partitions, TIMEOUT);
                for (TopicPartition partition : partitions) {
                    if (starts.get(partition) != 0 || ends.get(partition) < 0 || ends.get(partition) > through) throw new IllegalStateException("fixture append would change existing source data");
                    next.put(partition, ends.get(partition));
                }
            }
        }
        int sent = 0;
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            for (int worker = 1; worker <= 3; worker++) {
                for (int partition = 0; partition < worker; partition++) {
                    for (int block = Math.toIntExact(next.get(new TopicPartition(input(worker), partition))); block < through; block++) {
                        Episode episode = raw(worker, partition, block);
                        ProducerRecord<String, byte[]> record = new ProducerRecord<>(input(worker), partition,
                                Math.floorDiv(Micros.of(episode.getTStart()), 1_000), episode.getSeries().getSeriesId(), AVRO.encode(episode));
                        record.headers().add(TopicLayout.HEADER_SCHEMA, Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
                        var metadata = producer.send(record).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        if (metadata.partition() != partition || metadata.offset() != block) throw new IllegalStateException("continuous fixture offset differs");
                        sent++;
                    }
                }
            }
        }
        System.out.println(JSON.writeValueAsString(JSON.createObjectNode().put("throughBlocks", through).put("appendedRecords", sent)));
    }

    static SeriesStream stream(String sourceText, Path catalog) throws Exception {
        return stream(sourceText, catalog, null);
    }

    static SeriesStream stream(String sourceText, Path catalog, JsonNode state) throws Exception {
        JsonNode dag = DagToWayang.parameters(YAML.readTree(sourceText), catalog);
        if (!dag.path("schema").asText().equals("opentsx.dag.v2") || !dag.path("source").path("kind").asText().equals("kafscale-broker")
                || dag.path("sinks").size() != 1 || !dag.path("sinks").get(0).path("topic").asText().equals(OUTPUT)) {
            throw new IllegalStateException("submitted continuous UI DAG has a different output contract");
        }
        Set<String> topics = new HashSet<>(); dag.path("source").path("topics").forEach(topic -> topics.add(topic.asText()));
        if (dag.path("source").path("topics").size() != 3 || !topics.equals(Set.of(input(1), input(2), input(3)))) throw new IllegalStateException("select all three whole fixture topics");
        List<String> ids = new ArrayList<>(); dag.path("source").path("series").forEach(id -> ids.add(id.asText()));
        Set<String> expected = new HashSet<>();
        for (int worker = 1; worker <= 3; worker++) for (int partition = 0; partition < worker; partition++) expected.add(RustRunnerComposeFixture.key(worker, partition).getSeriesId());
        if (ids.size() != expected.size() || !new HashSet<>(ids).equals(expected)) throw new IllegalStateException("select all six fixture series");
        Map<String, JsonNode> nodes = new HashMap<>();
        dag.path("nodes").forEach(node -> { if (nodes.put(node.path("id").asText(), node) != null) throw new IllegalStateException("duplicate fixture node"); });
        JsonNode sink = dag.path("sinks").get(0);
        List<JsonNode> chain = new ArrayList<>(); Set<String> visited = new HashSet<>(); String at = sink.path("from").asText();
        while (true) {
            JsonNode node = nodes.get(at);
            if (node == null || !visited.add(at)) throw new IllegalStateException("missing or repeated fixture node");
            if (node.path("op").asText().equals("decode.avro-soe")) break;
            if (node.path("in").size() != 1) throw new IllegalStateException("fixture requires one cleaning chain");
            chain.add(node); at = node.path("in").get(0).asText();
        }
        if (visited.size() != nodes.size() || chain.isEmpty()) throw new IllegalStateException("unused or missing fixture operators");
        Collections.reverse(chain);
        JsonNode signal = sink.path("signal");
        SeriesStream.Target target = new SeriesStream.Target(signal.path("system").asText(), signal.path("id").asText(),
                signal.path("grade").asText("clean"), sink.path("origin").asText(null), dag.path("dag").asText(), ExecutionAssignments.sha256(sourceText),
                signal.path("metric").asText(null), null, sink.path("madeOf").asText(null));
        CleanChain clean = CleanChain.of(chain);
        return state == null ? new SeriesStream(clean, target, ids) : SeriesStream.restore(clean, target, ids, state);
    }

    static List<Episode> expected(String sourceText, long createdAt, Path catalog, int through) throws Exception {
        blocks(Integer.toString(through));
        SeriesStream stream = stream(sourceText, catalog);
        List<Episode> result = new ArrayList<>();
        for (int worker = 1; worker <= 3; worker++) for (int partition = 0; partition < worker; partition++) for (int block = 0; block < through; block++) {
            for (Episode episode : stream.add(raw(worker, partition, block))) {
                episode.getProvenance().setProducer(PRODUCER);
                episode.getProvenance().setCreatedAt(Micros.toInstant(createdAt));
                result.add(JavaTsxTopicSink.canonical(episode));
            }
        }
        return result;
    }

    private static void verify(String bootstrap, Path directory, Path deploymentPath, Path sourcePath, int through) throws Exception {
        JsonNode deployment = JSON.readTree(deploymentPath.toFile());
        String sourceText = Files.readString(sourcePath, StandardCharsets.UTF_8);
        String specHash = ExecutionAssignments.sha256(sourceText);
        if (!deployment.path("profile").asText().equals("continuous") || !Set.of("idle", "stopped").contains(deployment.path("state").asText())
                || !deployment.path("specHash").asText().equals(specHash) || !deployment.path("planHash").asText().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("continuous deployment is not caught up with the submitted UI graph");
        }
        long createdAt = decimal(deployment.path("createdAtMicros"));
        List<Episode> episodes = expected(sourceText, createdAt, Path.of("systems", "processors"), through);
        Map<String, Episode> expected = new LinkedHashMap<>();
        for (Episode episode : episodes) if (expected.put(episode.getEpisodeId(), episode) != null) throw new IllegalStateException("duplicate expected continuous identity");
        if (decimal(deployment.path("inputRecords")) != 6L * through || decimal(deployment.path("outputRecords")) != expected.size()
                || decimal(deployment.path("completedCycles")) < 1 || decimal(deployment.path("currentCycle")) < decimal(deployment.path("completedCycles"))) {
            throw new IllegalStateException("continuous cumulative counters differ from Java");
        }
        Set<String> nodes = new HashSet<>(); Set<String> topics = new HashSet<>(); Set<String> artifacts = new HashSet<>(); Set<String> requests = new HashSet<>();
        for (JsonNode assignment : deployment.path("assignments")) {
            int worker = 0; for (int index = 1; index <= 3; index++) if (input(index).equals(assignment.path("inputTopic").asText())) worker = index;
            if (worker == 0 || !assignment.path("node").asText().equals("worker-" + worker) || !nodes.add(assignment.path("node").asText())
                    || !topics.add(assignment.path("inputTopic").asText()) || !assignment.path("outputTopic").asText().equals(OUTPUT)
                    || !assignment.path("specHash").asText().equals(specHash) || decimal(assignment.path("partitionCount")) != worker
                    || decimal(assignment.path("cumulativeInputRecords")) != (long) worker * through) throw new IllegalStateException("continuous whole-topic assignment changed");
            int selectedWorker = worker;
            long outputs = episodes.stream().filter(episode -> Integer.toString(selectedWorker).equals(episode.getSeries().getTags().get("worker"))).count();
            if (decimal(assignment.path("cumulativeOutputRecords")) != outputs || assignment.path("positions").size() != worker) throw new IllegalStateException("continuous worker totals differ");
            for (int partition = 0; partition < worker; partition++) {
                JsonNode cursor = assignment.path("positions").get(partition);
                if (decimal(cursor.path("partition")) != partition || decimal(cursor.path("next")) != through) throw new IllegalStateException("continuous whole-topic watermark is incomplete");
            }
            JsonNode receipt = assignment.path("receipt");
            if (!receipt.path("schema").asText().equals("opentsx.local-receipt.v1") || !receipt.path("requestHash").equals(assignment.path("requestHash"))
                    || !receipt.path("requestHash").asText().matches("[0-9a-f]{64}") || !requests.add(receipt.path("requestHash").asText())
                    || !receipt.path("node").equals(assignment.path("node")) || !receipt.path("deployment").equals(deployment.path("id"))
                    || !receipt.path("inputTopic").equals(assignment.path("inputTopic")) || !receipt.path("outputTopic").asText().equals(OUTPUT)
                    || !receipt.path("positions").equals(assignment.path("positions")) || !receipt.path("artifactDigest").asText().matches("sha256:[0-9a-f]{64}")
                    || !receipt.path("outputDigest").asText().matches("[0-9a-f]{64}") || !receipt.path("stateDigest").asText().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("continuous receipt differs from the admitted worker");
            }
            artifacts.add(receipt.path("artifactDigest").asText());
        }
        if (nodes.size() != 3 || topics.size() != 3 || artifacts.size() != 1) throw new IllegalStateException("continuous deployment lacks its three verified workers");
        Set<String> seen = new HashSet<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = List.of(new TopicPartition(OUTPUT, 0), new TopicPartition(OUTPUT, 1), new TopicPartition(OUTPUT, 2));
            if (consumer.partitionsFor(OUTPUT, TIMEOUT).size() != 3) throw new IllegalStateException("continuous output partition inventory changed");
            var starts = consumer.beginningOffsets(partitions, TIMEOUT); var ends = consumer.endOffsets(partitions, TIMEOUT);
            Map<TopicPartition, long[]> ranges = new LinkedHashMap<>(); long total = 0;
            for (TopicPartition partition : partitions) {
                if (starts.get(partition) != 0) throw new IllegalStateException("continuous output retention changed");
                total = Math.addExact(total, ends.get(partition)); ranges.put(partition, new long[]{0, ends.get(partition)});
            }
            if (total != expected.size()) throw new IllegalStateException("continuous retry or restart changed output counts");
            LogReader.read(consumer, ranges, record -> {
                Episode actual = AVRO.decode(record.value(), Episode.class); Episodes.validate(actual);
                Episode wanted = expected.get(actual.getEpisodeId());
                if (wanted == null || !seen.add(actual.getEpisodeId()) || !Arrays.equals(record.value(), AVRO.encode(wanted))
                        || !Arrays.equals(record.key(), wanted.getSeries().getSeriesId().getBytes(StandardCharsets.UTF_8))
                        || record.timestamp() != Math.floorDiv(Micros.of(wanted.getTStart()), 1_000)
                        || record.partition() != Utils.toPositive(Utils.murmur2(record.key())) % 3) throw new IllegalStateException("continuous canonical output differs from Java");
                var headers = record.headers().toArray();
                if (headers.length != 1 || !headers[0].key().equals(TopicLayout.HEADER_SCHEMA)
                        || !Arrays.equals(headers[0].value(), Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8))) throw new IllegalStateException("continuous output schema header differs");
            }, TIMEOUT);
        }
        if (!seen.equals(expected.keySet())) throw new IllegalStateException("continuous output omits an expected episode");
        ObjectNode report = JSON.createObjectNode().put("verifiedRecords", seen.size()).put("distinctEpisodes", seen.size())
                .put("workerReceipts", nodes.size()).put("specHash", specHash).put("planHash", deployment.path("planHash").asText())
                .put("createdAtMicros", Long.toString(createdAt)).put("throughBlocks", through).put("inputRecords", 6 * through);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verified.json").toFile(), report);
        System.out.println(JSON.writeValueAsString(report));
    }

    private static long decimal(JsonNode node) {
        if (!node.isTextual()) throw new IllegalStateException("continuous counters and positions must be decimal strings");
        long value = Long.parseLong(node.asText());
        if (value < 0 || !Long.toString(value).equals(node.asText())) throw new IllegalStateException("noncanonical continuous decimal");
        return value;
    }
}
