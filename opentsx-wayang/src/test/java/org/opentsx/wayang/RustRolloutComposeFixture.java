package org.opentsx.wayang;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

public final class RustRolloutComposeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final String OUTPUT = "tsx.rust.clean.v2";
    private static final String PRODUCER = "tsx-runner/0.1.0";

    private RustRolloutComposeFixture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ready <bootstrap>");
            System.out.println("prepare <bootstrap> <fixture-directory>");
            System.out.println("verify <bootstrap> <fixture-directory> <deployment.json> <submitted-dag.txt>");
            return;
        }
        if (args.length == 2 && args[0].equals("ready")) {
            try (Admin admin = KafScaleClients.admin(args[1])) {
                admin.listTopics().names().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
            System.out.println("Kafka metadata is ready.");
        } else if (args.length == 3 && args[0].equals("prepare")) {
            prepare(args[1], Path.of(args[2]));
        } else if (args.length == 5 && args[0].equals("verify")) {
            verify(args[1], Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
        } else {
            throw new IllegalArgumentException("invalid fixture arguments; use --help");
        }
    }

    private static void prepare(String bootstrap, Path directory) throws Exception {
        List<NewTopic> topics = new ArrayList<>();
        for (int worker = 1; worker <= 3; worker++) topics.add(new NewTopic(RustRunnerComposeFixture.input(worker), worker, (short) 1));
        topics.add(new NewTopic(OUTPUT, 3, (short) 1));
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            admin.createTopics(topics).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        ObjectNode fixture = JSON.createObjectNode().put("schema", "opentsx.rust-rollout-fixture.v1")
                .put("outputTopic", OUTPUT).put("outputPartitions", 3).put("expectedInputRecords", 12)
                .put("expectedInputSamples", 48).put("tStartMicros", Long.toString(RustRunnerComposeFixture.CREATED))
                .put("system", "rust-demo").put("signal", "rollout");
        ArrayNode inputTopics = fixture.putArray("inputTopics");
        ArrayNode seriesIds = fixture.putArray("seriesIds");
        ArrayNode sources = fixture.putArray("sources");
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            for (int worker = 1; worker <= 3; worker++) {
                String topic = RustRunnerComposeFixture.input(worker);
                inputTopics.add(topic);
                ObjectNode source = sources.addObject().put("topic", topic).put("partitions", worker);
                ArrayNode sourceSeries = source.putArray("seriesIds");
                for (int partition = 0; partition < worker; partition++) {
                    String id = RustRunnerComposeFixture.key(worker, partition).getSeriesId();
                    seriesIds.add(id);
                    sourceSeries.add(id);
                    for (int block = 0; block < 2; block++) {
                        Episode raw = raw(worker, partition, block);
                        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, partition,
                                Math.floorDiv(Micros.of(raw.getTStart()), 1_000L), id, AVRO.encode(raw));
                        record.headers().add(TopicLayout.HEADER_SCHEMA,
                                Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
                        var sent = producer.send(record).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        if (sent.partition() != partition || sent.offset() != block) throw new IllegalStateException("input fixture offsets differ");
                    }
                }
            }
        }
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("fixture.json").toFile(), fixture);
        System.out.println(JSON.writeValueAsString(fixture));
    }

    static Episode raw(int worker, int partition, int block) {
        return Episodes.regular(RustRunnerComposeFixture.key(worker, partition),
                        RustRunnerComposeFixture.CREATED + block * 40_000_000L, 10_000_000L,
                        Arrays.copyOfRange(RustRunnerComposeFixture.VALUES, block * 4, block * 4 + 4))
                .episodeId("raw-" + worker + "-" + partition + "-" + block)
                .source("rust-compose-fixture").createdAt(RustRunnerComposeFixture.CREATED).build();
    }

    static List<Episode> expected(String sourceText, long createdAt, Path catalog) throws Exception {
        JsonNode dag = DagToWayang.parameters(YAML.readTree(sourceText), catalog);
        if (!dag.path("schema").asText().equals("opentsx.dag.v2")
                || !dag.path("source").path("kind").asText().equals("kafscale-broker")) {
            throw new IllegalStateException("the submitted UI document is not a v2 Kafka DAG");
        }
        if (dag.path("sinks").size() != 1) throw new IllegalStateException("the UI fixture needs exactly one sink");
        JsonNode sink = dag.path("sinks").get(0);
        if (!sink.path("topic").asText().equals(OUTPUT) || !sink.path("schema").asText().equals("opentsx.episode.v2")) {
            throw new IllegalStateException("the submitted UI DAG has a different output contract");
        }
        Set<String> topics = new HashSet<>();
        dag.path("source").path("topics").forEach(topic -> topics.add(topic.asText()));
        if (dag.path("source").path("topics").size() != topics.size()
                || !topics.equals(Set.of(RustRunnerComposeFixture.input(1), RustRunnerComposeFixture.input(2), RustRunnerComposeFixture.input(3)))) {
            throw new IllegalStateException("the UI DAG must select all three whole input topics");
        }
        List<String> seriesIds = new ArrayList<>();
        dag.path("source").path("series").forEach(id -> seriesIds.add(id.asText()));
        Set<String> required = new HashSet<>();
        for (int worker = 1; worker <= 3; worker++) {
            for (int partition = 0; partition < worker; partition++) required.add(RustRunnerComposeFixture.key(worker, partition).getSeriesId());
        }
        if (seriesIds.size() != required.size() || !new HashSet<>(seriesIds).equals(required)) {
            throw new IllegalStateException("the UI DAG must select all six fixture series");
        }
        Map<String, JsonNode> nodes = new HashMap<>();
        dag.path("nodes").forEach(node -> {
            if (nodes.put(node.path("id").asText(), node) != null) throw new IllegalStateException("duplicate UI node ID");
        });
        List<JsonNode> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String at = sink.path("from").asText();
        while (true) {
            JsonNode node = nodes.get(at);
            if (node == null || !visited.add(at)) throw new IllegalStateException("the UI chain has a missing or repeated node");
            if (node.path("op").asText().equals("decode.avro-soe")) break;
            if (node.path("in").size() != 1) throw new IllegalStateException("the UI fixture needs one per-series chain");
            chain.add(node);
            at = node.path("in").get(0).asText();
        }
        if (visited.size() != nodes.size() || chain.isEmpty()) throw new IllegalStateException("the UI fixture has unused or missing operators");
        Collections.reverse(chain);
        JsonNode signal = sink.path("signal");
        SeriesStream stream = new SeriesStream(CleanChain.of(chain), new SeriesStream.Target(
                signal.path("system").asText(), signal.path("id").asText(), signal.path("grade").asText("clean"),
                sink.path("origin").asText(null), dag.path("dag").asText("dag"), ExecutionAssignments.sha256(sourceText),
                signal.path("metric").asText(null), null, sink.path("madeOf").asText(null)), seriesIds);
        List<Episode> result = new ArrayList<>();
        for (int worker = 1; worker <= 3; worker++) {
            for (int partition = 0; partition < worker; partition++) {
                for (int block = 0; block < 2; block++) {
                    for (Episode episode : stream.add(raw(worker, partition, block))) {
                        episode.getProvenance().setProducer(PRODUCER);
                        episode.getProvenance().setCreatedAt(Micros.toInstant(createdAt));
                        result.add(JavaTsxTopicSink.canonical(episode));
                    }
                }
            }
        }
        return result;
    }

    private static void verify(String bootstrap, Path directory, Path deploymentPath, Path sourcePath) throws Exception {
        JsonNode fixture = JSON.readTree(directory.resolve("fixture.json").toFile());
        JsonNode deployment = JSON.readTree(deploymentPath.toFile());
        String sourceText = Files.readString(sourcePath, StandardCharsets.UTF_8);
        String specHash = ExecutionAssignments.sha256(sourceText);
        if (!deployment.path("state").asText().equals("completed") || !deployment.path("specHash").asText().equals(specHash)
                || !deployment.path("planHash").asText().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("the public deployment is not the completed submitted UI DAG");
        }
        long createdAt = Long.parseLong(deployment.path("createdAtMicros").asText());
        List<Episode> episodes = expected(sourceText, createdAt, Path.of("systems", "processors"));
        Map<String, Episode> expected = new LinkedHashMap<>();
        for (Episode episode : episodes) if (expected.put(episode.getEpisodeId(), episode) != null) throw new IllegalStateException("duplicate expected output identity");
        if (expected.size() != 12) throw new IllegalStateException("the UI fixture did not produce its twelve expected episodes");
        Set<String> nodes = new HashSet<>();
        Set<String> topics = new HashSet<>();
        Set<String> artifacts = new HashSet<>();
        Set<String> requests = new HashSet<>();
        int receiptOutputs = 0;
        for (JsonNode assignment : deployment.path("assignments")) {
            String topic = assignment.path("inputTopic").asText();
            JsonNode source = null;
            for (JsonNode candidate : fixture.path("sources")) if (candidate.path("topic").asText().equals(topic)) source = candidate;
            if (source == null || !topics.add(topic) || !nodes.add(assignment.path("node").asText())
                    || !assignment.path("state").asText().equals("completed") || !assignment.path("outputTopic").asText().equals(OUTPUT)
                    || !assignment.path("specHash").asText().equals(specHash)
                    || !assignment.path("partitionCount").isTextual()
                    || !assignment.path("partitionCount").asText().equals(Integer.toString(source.path("partitions").asInt()))) {
                throw new IllegalStateException("worker assignment does not own its complete fixture topic");
            }
            JsonNode receipt = assignment.path("receipt");
            int partitions = source.path("partitions").asInt();
            if (!receipt.path("schema").asText().equals("opentsx.local-receipt.v1")
                    || !receipt.path("node").asText().equals(assignment.path("node").asText())
                    || !receipt.path("deployment").asText().equals(deployment.path("id").asText())
                    || !receipt.path("inputTopic").asText().equals(topic) || !receipt.path("outputTopic").asText().equals(OUTPUT)
                    || receipt.path("inputRecords").asInt(-1) != partitions * 2 || receipt.path("positions").size() != partitions
                    || receipt.path("outputRecords").asInt(-1) != partitions * 2
                    || !receipt.path("requestHash").asText().matches("[0-9a-f]{64}")
                    || !receipt.path("requestHash").asText().equals(assignment.path("requestHash").asText())
                    || !requests.add(receipt.path("requestHash").asText())
                    || !receipt.path("artifactDigest").asText().matches("sha256:[0-9a-f]{64}")
                    || !receipt.path("outputDigest").asText().matches("[0-9a-f]{64}")
                    || !receipt.path("stateDigest").asText().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("worker receipt differs from the submitted deployment");
            }
            artifacts.add(receipt.path("artifactDigest").asText());
            for (int partition = 0; partition < partitions; partition++) {
                JsonNode position = receipt.path("positions").get(partition);
                if (!position.path("partition").isTextual() || !position.path("next").isTextual()
                        || !position.path("partition").asText().equals(Integer.toString(partition)) || !position.path("next").asText().equals("2")) {
                    throw new IllegalStateException("worker receipt omits part of its whole-topic cut");
                }
            }
            receiptOutputs += receipt.path("outputRecords").asInt(-1);
        }
        if (nodes.size() != 3 || topics.size() != 3 || artifacts.size() != 1 || receiptOutputs != expected.size()) {
            throw new IllegalStateException("the completed rollout is missing qualified worker receipts");
        }
        Set<String> seen = new HashSet<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = List.of(new TopicPartition(OUTPUT, 0), new TopicPartition(OUTPUT, 1), new TopicPartition(OUTPUT, 2));
            if (consumer.partitionsFor(OUTPUT, TIMEOUT).size() != 3) throw new IllegalStateException("the shared output partition inventory differs");
            Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, TIMEOUT);
            Map<TopicPartition, long[]> ranges = new LinkedHashMap<>();
            long records = 0;
            for (TopicPartition partition : partitions) {
                if (starts.get(partition) != 0) throw new IllegalStateException("output fixture retention changed");
                records = Math.addExact(records, ends.get(partition));
                ranges.put(partition, new long[]{0, ends.get(partition)});
            }
            if (records != expected.size()) throw new IllegalStateException("retry or restart changed the output record count");
            LogReader.read(consumer, ranges, record -> {
                Episode actual = AVRO.decode(record.value(), Episode.class);
                Episodes.validate(actual);
                Episode wanted = expected.get(actual.getEpisodeId());
                if (wanted == null || !seen.add(actual.getEpisodeId())) throw new IllegalStateException("unexpected or duplicate output identity");
                if (!Arrays.equals(record.value(), AVRO.encode(wanted)) || !Arrays.equals(record.key(), wanted.getSeries().getSeriesId().getBytes(StandardCharsets.UTF_8))
                        || record.timestamp() != Math.floorDiv(Micros.of(wanted.getTStart()), 1_000L)
                        || record.partition() != Utils.toPositive(Utils.murmur2(record.key())) % 3) {
                    throw new IllegalStateException("canonical UI-to-Rust output differs from Java");
                }
                var headers = record.headers().headers(TopicLayout.HEADER_SCHEMA).iterator();
                if (record.headers().toArray().length != 1 || !headers.hasNext()
                        || !Arrays.equals(headers.next().value(), Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8)) || headers.hasNext()) {
                    throw new IllegalStateException("output schema header differs");
                }
            }, TIMEOUT);
        }
        if (!seen.equals(expected.keySet())) throw new IllegalStateException("the shared output omits an expected episode");
        ObjectNode report = JSON.createObjectNode().put("verifiedRecords", seen.size()).put("distinctEpisodes", seen.size())
                .put("workerReceipts", nodes.size()).put("specHash", specHash).put("planHash", deployment.path("planHash").asText())
                .put("normalizedProducer", PRODUCER).put("createdAtMicros", Long.toString(createdAt))
                .put("javaProducer", raw(1, 0, 0).getProvenance().getProducer());
        report.set("sourceTopics", fixture.path("sources"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verified.json").toFile(), report);
        System.out.println(JSON.writeValueAsString(report));
    }
}
