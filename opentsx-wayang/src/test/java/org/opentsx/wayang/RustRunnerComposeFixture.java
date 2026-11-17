package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public final class RustRunnerComposeFixture {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();
    private static final long CREATED = 1_790_812_800_000_000L;
    private static final String PRODUCER = "tsx-runner/0.1.0";
    private static final double[] VALUES = {5, -1, 50, 101, 100, 0, Double.NaN, 42};
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private RustRunnerComposeFixture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ready <bootstrap>");
            System.out.println("prepare <bootstrap> <fixture-directory> <sha256:artifact-digest>");
            System.out.println("verify <bootstrap> <fixture-directory> <initial|completed-retry|checkpoint-replay>");
            return;
        }
        if (args.length == 2 && args[0].equals("ready")) {
            try (Admin admin = KafScaleClients.admin(args[1])) {
                admin.listTopics().names().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
            System.out.println("Kafka metadata is ready.");
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("expected a command and three arguments; use --help");
        Path directory = Path.of(args[2]);
        switch (args[0]) {
            case "prepare" -> prepare(args[1], directory, args[3]);
            case "verify" -> verify(args[1], directory, args[3]);
            default -> throw new IllegalArgumentException("unknown fixture command " + args[0]);
        }
    }

    private static void prepare(String bootstrap, Path directory, String artifact) throws Exception {
        if (!artifact.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid artifact digest");
        int port = Integer.parseInt(bootstrap.substring(bootstrap.lastIndexOf(':') + 1));
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid broker port");
        Files.createDirectories(directory.resolve("requests"));
        List<NewTopic> topics = new ArrayList<>();
        for (int worker = 1; worker <= 3; worker++) {
            topics.add(new NewTopic(input(worker), worker, (short) 1));
            topics.add(new NewTopic(output(worker), worker, (short) 1));
        }
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            admin.createTopics(topics).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        ObjectNode manifest = JSON.createObjectNode().put("schema", "opentsx.rust-compose-fixture.v1");
        ArrayNode workers = manifest.putArray("workers");
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            for (int worker = 1; worker <= 3; worker++) {
                ObjectNode dag = dag(worker);
                List<JsonNode> nodes = new ArrayList<>();
                dag.path("nodes").forEach(nodes::add);
                List<String> expectedIds = new ArrayList<>();
                dag.path("source").path("series").forEach(id -> expectedIds.add(id.asText()));
                String text = JSON.writeValueAsString(dag);
                String specHash = ExecutionAssignments.sha256(text);
                SeriesStream stream = new SeriesStream(CleanChain.of(nodes.subList(1, nodes.size())),
                        new SeriesStream.Target("rust-demo", "signal-" + worker, "clean", null,
                                "rust-demo-" + worker, specHash), expectedIds);
                ObjectNode fixture = workers.addObject().put("worker", worker).put("topic", output(worker));
                ArrayNode expected = fixture.putArray("expected");
                for (int partition = 0; partition < worker; partition++) {
                    SeriesKey key = key(worker, partition);
                    for (int block = 0; block < 2; block++) {
                        Episode raw = Episodes.regular(key, CREATED + block * 40_000_000L, 10_000_000L,
                                        Arrays.copyOfRange(VALUES, block * 4, block * 4 + 4))
                                .episodeId("raw-" + worker + "-" + partition + "-" + block)
                                .source("rust-compose-fixture").createdAt(CREATED).build();
                        ProducerRecord<String, byte[]> record = new ProducerRecord<>(input(worker), partition,
                                Math.floorDiv(Micros.of(raw.getTStart()), 1_000L), key.getSeriesId(), AVRO.encode(raw));
                        record.headers().add(TopicLayout.HEADER_SCHEMA,
                                Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
                        var sent = producer.send(record).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        if (sent.partition() != partition || sent.offset() != block) {
                            throw new IllegalStateException("the input topic is not the new fixture inventory");
                        }
                        for (Episode episode : stream.add(raw)) {
                            String javaProducer = episode.getProvenance().getProducer();
                            episode.getProvenance().setProducer(PRODUCER);
                            episode.getProvenance().setCreatedAt(Micros.toInstant(CREATED));
                            byte[] bytes = AVRO.encode(JavaTsxTopicSink.canonical(episode));
                            expected.addObject().put("id", episode.getEpisodeId()).put("key", episode.getSeries().getSeriesId())
                                    .put("timestamp", Math.floorDiv(Micros.of(episode.getTStart()), 1_000L))
                                    .put("payload", HexFormat.of().formatHex(bytes)).put("javaProducer", javaProducer);
                        }
                    }
                }
                ObjectNode assignment = assignment(worker, text, specHash, artifact);
                String assignmentText = JSON.writeValueAsString(assignment);
                ExecutionAssignments.validate(assignmentText);
                ObjectNode request = JSON.createObjectNode().put("schema", "opentsx.local-bounded-run.v1")
                        .put("assignmentText", assignmentText).put("createdAtMicros", Long.toString(CREATED));
                ObjectNode binding = request.putObject("bindings").putObject("local");
                binding.put("bootstrap", "kafscale-broker:" + port);
                binding.putArray("inputs").add(input(worker));
                binding.putArray("outputs").add(output(worker));
                request.putObject("sink").put("name", "signal-" + worker).put("binding", "local")
                        .put("topic", output(worker)).put("partitionCount", Integer.toString(worker));
                request.putObject("limits").put("records", 100).put("payloadBytes", 1_048_576)
                        .put("inputBytes", 16_777_216).put("samples", 100_000).put("outputBytes", 16_777_216)
                        .put("series", 64).put("stateBytes", 1_048_576);
                JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("requests/worker-" + worker + ".json").toFile(), request);
                fixture.put("inputRecords", worker * 2).put("inputSamples", worker * 8);
                fixture.set("retainedJavaState", stream.state());
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("expected.json").toFile(), manifest);
        System.out.println("Prepared three whole-topic requests across six input partitions.");
    }

    private static ObjectNode dag(int worker) {
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "rust-demo-" + worker);
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker").put("start", "earliest");
        source.putArray("topics").add(input(worker));
        ArrayNode series = source.putArray("series");
        for (int partition = 0; partition < worker; partition++) series.add(key(worker, partition).getSeriesId());
        ArrayNode nodes = dag.putArray("nodes");
        nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        ObjectNode range = nodes.addObject().put("id", "range").put("op", "dq.checks");
        range.putArray("in").add("decode");
        ObjectNode checks = range.putObject("params").put("min", 0).put("max", 100).put("mode", "gap");
        checks.putArray("checks").add("PLAUSIBLE_RANGE");
        ObjectNode convert = nodes.addObject().put("id", "convert").put("op", "convert.unit");
        convert.putArray("in").add("range");
        convert.putObject("params").put("factor", 0.001).put("offset", 0).put("unit", "MW");
        ObjectNode resample = nodes.addObject().put("id", "sample").put("op", "resample");
        resample.putArray("in").add("convert");
        resample.putObject("params").put("every", "PT20S").put("agg", "MEAN");
        ObjectNode sink = dag.putArray("sinks").addObject().put("from", "sample")
                .put("topic", output(worker)).put("schema", "opentsx.episode.v2");
        sink.putObject("signal").put("system", "rust-demo").put("id", "signal-" + worker).put("grade", "clean");
        return dag;
    }

    private static ObjectNode assignment(int worker, String dag, String specHash, String artifact) {
        ObjectNode assignment = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                .put("deployment", "rust-compose-" + worker).put("generation", "1").put("assignment", "worker-" + worker)
                .put("attempt", "attempt-1").put("fence", "1").put("fragment", "fragment-" + worker)
                .put("site", "local").put("node", "node-" + worker).put("runtime", "rust")
                .put("catalog", "tsx-catalog/1").put("artifactDigest", artifact).put("specHash", specHash)
                .put("sourceDagText", dag).put("fragmentHash", specHash).put("fragmentText", dag).put("mode", "bounded");
        ObjectNode input = assignment.putArray("inputs").addObject().put("binding", "local").put("topic", input(worker))
                .put("partitionCount", Integer.toString(worker));
        ArrayNode positions = input.putArray("positions");
        for (int partition = 0; partition < worker; partition++) {
            positions.addObject().put("partition", Integer.toString(partition)).put("from", "0").put("to", "2");
        }
        return assignment;
    }

    private static void verify(String bootstrap, Path directory, String phase) throws Exception {
        int multiplier = switch (phase) {
            case "initial", "completed-retry" -> 1;
            case "checkpoint-replay" -> 2;
            default -> throw new IllegalArgumentException("unknown verification phase " + phase);
        };
        JsonNode manifest = JSON.readTree(directory.resolve("expected.json").toFile());
        ObjectNode report = JSON.createObjectNode().put("phase", phase).put("producer", PRODUCER)
                .put("createdAtMicros", Long.toString(CREATED));
        ArrayNode reports = report.putArray("workers");
        int records = 0;
        int distinct = 0;
        for (JsonNode fixture : manifest.path("workers")) {
            int worker = fixture.path("worker").asInt();
            Map<String, JsonNode> expected = new LinkedHashMap<>();
            for (JsonNode value : fixture.path("expected")) {
                if (expected.put(value.path("id").asText(), value) != null) throw new IllegalStateException("duplicate fixture identity");
            }
            Map<String, Integer> seen = new TreeMap<>();
            Map<TopicPartition, long[]> ranges = new LinkedHashMap<>();
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
                List<TopicPartition> partitions = new ArrayList<>();
                for (int partition = 0; partition < worker; partition++) partitions.add(new TopicPartition(output(worker), partition));
                if (consumer.partitionsFor(output(worker), TIMEOUT).size() != worker) throw new IllegalStateException("output partition count differs");
                Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions, TIMEOUT);
                Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, TIMEOUT);
                for (TopicPartition partition : partitions) {
                    if (starts.get(partition) != 0) throw new IllegalStateException("output retention changed the fixture");
                    ranges.put(partition, new long[]{0, ends.get(partition)});
                }
                LogReader.read(consumer, ranges, record -> {
                    Episode actual = AVRO.decode(record.value(), Episode.class);
                    Episodes.validate(actual);
                    JsonNode match = expected.get(actual.getEpisodeId());
                    if (match == null) throw new IllegalStateException("unexpected output episode " + actual.getEpisodeId());
                    if (!Arrays.equals(record.key(), match.path("key").asText().getBytes(StandardCharsets.UTF_8))) {
                        throw new IllegalStateException("output key differs for " + actual.getEpisodeId());
                    }
                    if (record.partition() != Utils.toPositive(Utils.murmur2(record.key())) % worker) {
                        throw new IllegalStateException("output partition differs from the Java key partitioner");
                    }
                    var headers = record.headers().headers(TopicLayout.HEADER_SCHEMA).iterator();
                    if (!headers.hasNext() || !Arrays.equals(headers.next().value(),
                            Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8)) || headers.hasNext()) {
                        throw new IllegalStateException("output schema header differs");
                    }
                    if (record.timestamp() != match.path("timestamp").asLong()) throw new IllegalStateException("output timestamp differs");
                    if (!PRODUCER.equals(actual.getProvenance().getProducer()) || Micros.of(actual.getProvenance().getCreatedAt()) != CREATED) {
                        throw new IllegalStateException("output provenance differs");
                    }
                    if (!Arrays.equals(record.value(), HexFormat.of().parseHex(match.path("payload").asText()))) {
                        throw new IllegalStateException("canonical Avro bytes differ for " + actual.getEpisodeId());
                    }
                    seen.merge(actual.getEpisodeId(), 1, Math::addExact);
                }, TIMEOUT);
            }
            if (!seen.keySet().equals(expected.keySet()) || seen.values().stream().anyMatch(count -> count != multiplier)) {
                throw new IllegalStateException("output multiplicity differs for worker " + worker + ": " + seen);
            }
            int count = seen.values().stream().mapToInt(Integer::intValue).sum();
            records += count;
            distinct += seen.size();
            reports.addObject().put("worker", worker).put("partitions", worker).put("records", count).put("distinctEpisodes", seen.size());
        }
        report.put("records", records).put("distinctEpisodes", distinct);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verify-" + phase + ".json").toFile(), report);
        System.out.println(JSON.writeValueAsString(report));
    }

    private static SeriesKey key(int worker, int partition) {
        return SeriesKeys.of("learning.power", Map.of("worker", Integer.toString(worker), "partition", Integer.toString(partition)), "kW");
    }

    private static String input(int worker) {
        return "tsx.rust.worker-" + worker + ".episodes.v2";
    }

    private static String output(int worker) {
        return "tsx.rust.worker-" + worker + ".clean.v2";
    }
}
