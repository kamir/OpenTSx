package org.opentsx.wayang;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.XxHash64;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.site.SignedJws;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class RustB1RunFixture {
    static final TopicLayout LAYOUT = new TopicLayout("b1run");
    static final String STATUS = LAYOUT.dagStatus();
    static final String INPUT = LAYOUT.repartition("normal", "input");
    static final String EPISODE_FINGERPRINT = XxHash64.hex(OpenTsxAvro.fingerprint(Episode.getClassSchema()));
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final List<String> CASES = List.of("normal", "crash-1", "crash-150", "crash-299", "unknown", "malformed");

    private RustB1RunFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("prepare <proxy-bootstrap> <directory> <sha256:runner-digest>");
            System.out.println("verify <proxy-bootstrap> <directory> <case>");
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("expected command, Proxy bootstrap, directory and argument");
        Path directory = Path.of(args[2]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("fixture directory must not be a symbolic link");
        switch (args[0]) {
            case "prepare" -> prepare(args[1], directory, args[3]);
            case "verify" -> verify(args[1], directory, args[3]);
            default -> throw new IllegalArgumentException("unknown signed-run fixture command");
        }
    }

    static List<Episode> firstW() {
        List<Episode> complete = KafScaleFixture.scenarioW();
        if (complete.size() < 300) throw new IllegalStateException("W contains fewer than 300 episodes");
        List<Episode> records = List.copyOf(complete.subList(0, 300));
        long series = records.stream().map(episode -> episode.getSeries().getSeriesId()).distinct().count();
        long samples = records.stream().mapToLong(episode -> episode.getValues().size()).sum();
        if (records.size() != 300 || series != 100 || samples != 180_000
                || records.stream().map(Episode::getEpisodeId).distinct().count() != 300) {
            throw new IllegalStateException("the original W crash fixture differs from 300 episodes, 100 series and 180000 values");
        }
        records.forEach(Episodes::validate);
        return records;
    }

    static ObjectNode metadata(String name) {
        if (!CASES.contains(name) && !name.equals("stdin")) throw new IllegalArgumentException("unknown signed-run case");
        boolean bad = name.equals("unknown") || name.equals("malformed");
        boolean empty = name.equals("stdin");
        Integer failAfter = name.startsWith("crash-") ? Integer.valueOf(name.substring(6)) : null;
        String input = bad || empty ? LAYOUT.repartition(name, "input") : INPUT;
        ObjectNode value = JSON.createObjectNode().put("name", name).put("plan", name + "/plan.jws")
                .put("config", name + "/config.json").put("inputTopic", input)
                .put("outputTopic", LAYOUT.repartition(name, "out")).put("partitions", bad || empty ? 1 : 3)
                .put("inputCount", empty ? 0 : bad ? 2 : 300).put("expectedDistinct", bad || empty ? 0 : 300)
                .put("expectedRecords", bad || empty ? 0 : 300 + (failAfter == null ? 0 : failAfter));
        if (failAfter == null) value.putNull("failAfter"); else value.put("failAfter", failAfter);
        if (name.equals("unknown")) {
            value.put("expectedError", "record " + input + "/0@0 has schema fingerprint "
                    + XxHash64.hex(OpenTsxAvro.fingerprint(SeriesStats.getClassSchema())) + ", not in plan b1-unknown version 1");
        } else if (name.equals("malformed")) {
            value.put("expectedError", "record " + input + "/0@0 is not Avro single-object encoded");
        } else value.putNull("expectedError");
        return value;
    }

    static ObjectNode plan(JsonNode test, String artifact, Instant now) {
        if (!artifact.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid runner artifact digest");
        String name = test.path("name").asText();
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "b1-" + name);
        dag.putObject("source").put("kind", "kafscale-broker").putArray("topics").add(test.path("inputTopic").asText());
        ((ObjectNode) dag.get("source")).putArray("series");
        dag.putArray("nodes").addObject().put("id", "decode").put("op", "decode.avro-soe");
        dag.putArray("sinks").addObject().put("name", "out").put("from", "decode")
                .put("schema", "opentsx.episode.v2").put("topic", test.path("outputTopic").asText());
        String text = dag.toString();
        String hash = ExecutionAssignments.sha256(text);
        ObjectNode assignment = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                .put("deployment", "b1-" + name).put("generation", "1").put("assignment", "b1-" + name)
                .put("attempt", "attempt-1").put("fence", "1").put("fragment", "fragment-1")
                .put("site", "s1").put("node", "b1-runner").put("runtime", "rust").put("catalog", "tsx-catalog/1")
                .put("artifactDigest", artifact).put("specHash", hash).put("sourceDagText", text)
                .put("fragmentHash", hash).put("fragmentText", text).put("mode", "continuous");
        ObjectNode input = assignment.putArray("inputs").addObject().put("binding", "site")
                .put("topic", test.path("inputTopic").asText()).put("partitionCount", test.path("partitions").asText());
        var positions = input.putArray("positions");
        for (int partition = 0; partition < test.path("partitions").asInt(); partition++) {
            positions.addObject().put("partition", Integer.toString(partition)).put("from", "0");
        }
        ExecutionAssignments.validate(assignment.toString());
        ObjectNode plan = JSON.createObjectNode().put("schema", "opentsx.runner-plan.v1").put("job", "b1-" + name)
                .put("planVersion", 1).put("issued", now.minusSeconds(60).toString())
                .put("expires", now.plusSeconds(3_600).toString()).put("site", "s1").put("catalog", "tsx-catalog/1")
                .put("specHash", hash).put("assignmentText", assignment.toString());
        plan.putArray("inputContracts").addObject().put("binding", "site").put("topic", test.path("inputTopic").asText())
                .putArray("fingerprints").add(EPISODE_FINGERPRINT);
        plan.putArray("outputs").addObject().put("sink", "out").put("cluster", "site")
                .put("topic", test.path("outputTopic").asText()).put("partitionCount", test.path("partitions").asText())
                .putArray("fingerprints").add(EPISODE_FINGERPRINT);
        return plan;
    }

    private static ObjectNode configuration(JsonNode test, String bootstrap) {
        ObjectNode config = JSON.createObjectNode().put("schema", "opentsx.rust-run-config.v1")
                .put("site", "s1").put("node", "b1-runner").put("driverPublicKey", "../driver-public.pem")
                .put("checkpoint", "checkpoint.json");
        ObjectNode binding = config.putObject("bindings").putObject("site").put("bootstrap", bootstrap);
        binding.putArray("inputs").add(test.path("inputTopic").asText());
        binding.putArray("outputs").add(test.path("outputTopic").asText()).add(STATUS);
        ObjectNode policy = config.putObject("policy");
        policy.putArray("domains").add(LAYOUT.domain());
        policy.putArray("catalogs").add("tsx-catalog/1");
        config.putObject("limits").put("records", 10_000).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                .put("samples", 1_000_000).put("outputBytes", 16_777_216).put("series", 1_024).put("stateBytes", 1_048_576);
        return config;
    }

    private static void prepare(String bootstrap, Path directory, String artifact) throws Exception {
        if (!artifact.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid runner artifact digest");
        Files.createDirectories(directory);
        for (String file : List.of("run-fixture.json", "expected.json", "driver-public.pem")) {
            if (Files.exists(directory.resolve(file))) throw new IllegalArgumentException("signed-run fixture evidence already exists");
        }
        List<Episode> source = firstW();
        List<ObjectNode> cases = new ArrayList<>();
        CASES.forEach(name -> cases.add(metadata(name)));
        ObjectNode stdin = metadata("stdin");
        List<ObjectNode> all = new ArrayList<>(cases);
        all.add(stdin);
        Set<String> names = new HashSet<>();
        List<NewTopic> topics = new ArrayList<>();
        for (JsonNode test : all) {
            for (String field : List.of("inputTopic", "outputTopic")) {
                String topic = test.path(field).asText();
                if (names.add(topic)) topics.add(new NewTopic(topic, test.path("partitions").asInt(), (short) 1)
                        .configs(Map.of("compression.type", "zstd", "cleanup.policy", "delete")));
            }
        }
        topics.add(new NewTopic(STATUS, 1, (short) 1).configs(Map.of("cleanup.policy", "delete")));
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            admin.createTopics(topics).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        List<ProducerRecord<String, byte[]>> records = source.stream().map(episode -> JavaTsxTopicSink.toRecord(AVRO, INPUT, episode)).toList();
        ObjectNode expected = JSON.createObjectNode().put("schema", "opentsx.rust-b1-run-expected.v1");
        var expectedRows = expected.putArray("records");
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            List<Future<RecordMetadata>> sent = new ArrayList<>();
            records.forEach(record -> sent.add(producer.send(record)));
            producer.flush();
            for (int index = 0; index < records.size(); index++) {
                RecordMetadata stored = sent.get(index).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                ProducerRecord<String, byte[]> record = records.get(index);
                expectedRows.addObject().put("id", source.get(index).getEpisodeId()).put("key", record.key())
                        .put("timestamp", record.timestamp()).put("payload", HexFormat.of().formatHex(record.value()))
                        .put("inputPartition", stored.partition()).put("inputOffset", stored.offset());
            }
            Episode first = source.get(0);
            SeriesStats stray = SeriesStats.newBuilder().setSeriesId(first.getSeries().getSeriesId()).setWindowType("PT10M")
                    .setWindowStart(Instant.EPOCH).setWindowEnd(Instant.EPOCH).setValidCount(0).setMin(0).setMax(0).setMean(0).setM2(0).build();
            for (String name : List.of("unknown", "malformed")) {
                String topic = metadata(name).path("inputTopic").asText();
                ProducerRecord<String, byte[]> bad = name.equals("unknown") ? JavaTsxTopicSink.toRecord(AVRO, topic, stray)
                        : new ProducerRecord<>(topic, 0, records.get(0).timestamp(), records.get(0).key(), new byte[]{(byte) 0xc3, 1, (byte) 0x95});
                if (name.equals("malformed")) bad.headers().add(TopicLayout.HEADER_SCHEMA, Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
                RecordMetadata badPosition = producer.send(bad).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (badPosition.partition() != 0 || badPosition.offset() != 0) throw new IllegalStateException("bad input is not the first record");
                ProducerRecord<String, byte[]> valid = new ProducerRecord<>(topic, 0, records.get(0).timestamp(), records.get(0).key(), records.get(0).value());
                valid.headers().add(TopicLayout.HEADER_SCHEMA, Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8));
                if (producer.send(valid).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).offset() != 1) throw new IllegalStateException("valid input does not follow the bad record");
            }
        }
        expected(expected);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("expected.json").toFile(), expected);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(directory.resolve("driver-public.pem"), publicPem, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Instant now = Instant.now();
        for (JsonNode test : all) {
            Path caseDirectory = directory.resolve(test.path("name").asText());
            Files.createDirectory(caseDirectory);
            Files.writeString(caseDirectory.resolve("plan.jws"), SignedJws.sign(JSON.writeValueAsBytes(plan(test, artifact, now)), pair.getPrivate()),
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            JSON.writerWithDefaultPrettyPrinter().writeValue(caseDirectory.resolve("config.json").toFile(), configuration(test, bootstrap));
        }
        ObjectNode manifest = JSON.createObjectNode().put("schema", "opentsx.rust-b1-run-fixture.v1").put("artifactDigest", artifact)
                .put("inputRecords", source.size()).put("series", source.stream().map(episode -> episode.getSeries().getSeriesId()).distinct().count())
                .put("samples", source.stream().mapToLong(episode -> episode.getValues().size()).sum()).put("statusTopic", STATUS);
        var rows = manifest.putArray("cases");
        cases.forEach(rows::add);
        manifest.set("stdin", stdin);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("run-fixture.json").toFile(), manifest);
    }

    record Expected(String id, String key, long timestamp, byte[] payload, int inputPartition, long inputOffset) { }

    static List<Expected> expected(JsonNode document) {
        if (!document.isObject() || document.size() != 2 || !document.path("schema").asText().equals("opentsx.rust-b1-run-expected.v1")
                || !document.path("records").isArray() || document.path("records").size() != 300) throw new IllegalStateException("invalid W reference inventory");
        Map<String, Expected> result = new HashMap<>();
        Set<String> positions = new HashSet<>();
        for (JsonNode row : document.path("records")) {
            Set<String> fields = Set.of("id", "key", "timestamp", "payload", "inputPartition", "inputOffset");
            if (!row.isObject() || row.size() != fields.size() || fields.stream().anyMatch(field -> !row.has(field))
                    || !row.path("id").isTextual() || !row.path("key").isTextual() || !row.path("payload").isTextual()
                    || !row.path("timestamp").isIntegralNumber() || !row.path("timestamp").canConvertToLong()
                    || !row.path("inputPartition").isInt() || row.path("inputPartition").intValue() < 0 || row.path("inputPartition").intValue() > 2
                    || !row.path("inputOffset").isIntegralNumber() || !row.path("inputOffset").canConvertToLong() || row.path("inputOffset").longValue() < 0) {
                throw new IllegalStateException("invalid W reference row");
            }
            byte[] payload = HexFormat.of().parseHex(row.path("payload").textValue());
            Episode decoded = AVRO.decode(payload, Episode.class);
            Episodes.validate(decoded);
            if (!row.path("id").textValue().equals(decoded.getEpisodeId()) || !row.path("key").textValue().equals(decoded.getSeries().getSeriesId())
                    || row.path("timestamp").longValue() != Math.floorDiv(Micros.of(decoded.getTStart()), 1_000)) {
                throw new IllegalStateException("W reference metadata differs from its payload");
            }
            Expected value = new Expected(decoded.getEpisodeId(), row.path("key").textValue(), row.path("timestamp").longValue(), payload,
                    row.path("inputPartition").intValue(), row.path("inputOffset").longValue());
            if (result.put(value.id(), value) != null || !positions.add(value.inputPartition() + ":" + value.inputOffset())) {
                throw new IllegalStateException("duplicate W reference identity or input position");
            }
        }
        List<Expected> ordered = new ArrayList<>(result.values());
        ordered.sort(Comparator.comparingInt(Expected::inputPartition).thenComparingLong(Expected::inputOffset));
        return ordered;
    }

    static void requireRecord(ConsumerRecord<byte[], byte[]> record, Expected expected, int partitions) {
        if (!Arrays.equals(record.key(), expected.key().getBytes(StandardCharsets.UTF_8))
                || !Arrays.equals(record.value(), expected.payload()) || record.timestamp() != expected.timestamp()
                || record.partition() != Utils.toPositive(Utils.murmur2(record.key())) % partitions) {
            throw new IllegalStateException("signed-run output differs from the canonical W record");
        }
        var headers = record.headers().headers(TopicLayout.HEADER_SCHEMA).iterator();
        if (!headers.hasNext() || !Arrays.equals(headers.next().value(), Episode.getClassSchema().getFullName().getBytes(StandardCharsets.UTF_8)) || headers.hasNext()) {
            throw new IllegalStateException("signed-run output schema header differs");
        }
    }

    private static JsonNode manifest(Path directory) throws Exception {
        JsonNode value = JSON.readTree(directory.resolve("run-fixture.json").toFile());
        Set<String> fields = Set.of("schema", "artifactDigest", "inputRecords", "series", "samples", "statusTopic", "cases", "stdin");
        if (!value.isObject() || value.size() != fields.size() || fields.stream().anyMatch(field -> !value.has(field))
                || !value.path("schema").asText().equals("opentsx.rust-b1-run-fixture.v1")
                || !value.path("artifactDigest").isTextual() || !value.path("artifactDigest").asText().matches("sha256:[0-9a-f]{64}")
                || !value.path("inputRecords").isInt() || value.path("inputRecords").intValue() != 300
                || !value.path("series").isIntegralNumber() || value.path("series").longValue() != 100
                || !value.path("samples").isIntegralNumber() || value.path("samples").longValue() != 180_000
                || !value.path("statusTopic").asText().equals(STATUS) || !value.path("cases").isArray() || value.path("cases").size() != CASES.size()) {
            throw new IllegalStateException("invalid signed-run fixture manifest");
        }
        for (int index = 0; index < CASES.size(); index++) if (!metadata(CASES.get(index)).equals(value.path("cases").get(index))) throw new IllegalStateException("signed-run case metadata differs");
        if (!metadata("stdin").equals(value.get("stdin"))) throw new IllegalStateException("signed stdin metadata differs");
        return value;
    }

    private static List<ConsumerRecord<byte[], byte[]>> readAll(String bootstrap, String topic, int partitions) {
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            if (consumer.partitionsFor(topic, TIMEOUT).size() != partitions) throw new IllegalStateException("signed-run partition inventory differs");
            List<TopicPartition> inventory = new ArrayList<>();
            for (int partition = 0; partition < partitions; partition++) inventory.add(new TopicPartition(topic, partition));
            var low = consumer.beginningOffsets(inventory, TIMEOUT);
            var high = consumer.endOffsets(inventory, TIMEOUT);
            Map<TopicPartition, long[]> ranges = new LinkedHashMap<>();
            inventory.forEach(partition -> {
                if (low.get(partition) != 0) throw new IllegalStateException("signed-run evidence retention changed");
                ranges.put(partition, new long[]{0, high.get(partition)});
            });
            LogReader.read(consumer, ranges, records::add, TIMEOUT);
        }
        return records;
    }

    private static void verify(String bootstrap, Path directory, String name) throws Exception {
        ObjectNode test = metadata(name);
        JsonNode manifest = manifest(directory);
        List<Expected> ordered = expected(JSON.readTree(directory.resolve("expected.json").toFile()));
        Map<String, Expected> byId = new HashMap<>();
        Map<String, Integer> multiplicities = new HashMap<>();
        int failAfter = test.path("failAfter").isNull() ? 0 : test.path("failAfter").intValue();
        for (int index = 0; index < ordered.size(); index++) {
            Expected expected = ordered.get(index);
            byId.put(expected.id(), expected);
            multiplicities.put(expected.id(), index < failAfter ? 2 : 1);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<ConsumerRecord<byte[], byte[]>> records = readAll(bootstrap, test.path("outputTopic").asText(), test.path("partitions").intValue());
        if (records.size() != test.path("expectedRecords").intValue()) throw new IllegalStateException("signed-run output record count differs");
        for (ConsumerRecord<byte[], byte[]> record : records) {
            Episode episode = AVRO.decode(record.value(), Episode.class);
            Episodes.validate(episode);
            Expected expected = byId.get(episode.getEpisodeId());
            if (expected == null) throw new IllegalStateException("signed-run output has an unexpected identity");
            requireRecord(record, expected, test.path("partitions").intValue());
            seen.merge(expected.id(), 1, Integer::sum);
        }
        if (seen.size() != test.path("expectedDistinct").intValue()
                || !seen.isEmpty() && !seen.equals(multiplicities)) throw new IllegalStateException("signed-run duplicate identities or multiplicities differ");
        boolean checkpoint = Files.isRegularFile(directory.resolve(name).resolve("checkpoint.json"));
        if (checkpoint != (test.path("expectedDistinct").intValue() != 0)) throw new IllegalStateException("signed-run progress checkpoint presence differs");
        JsonNode latest = null;
        for (ConsumerRecord<byte[], byte[]> record : readAll(bootstrap, STATUS, 1)) {
            JsonNode status = JSON.readTree(record.value());
            if (status.path("job").asText().equals("b1-" + name)) latest = status;
        }
        boolean failed = !test.path("expectedError").isNull();
        String state = failed ? "failed" : "stopped";
        long statusRecords = test.path("expectedDistinct").longValue();
        if (latest == null || !latest.path("schema").asText().equals("opentsx.dag-status.v1") || !latest.path("track").asText().equals("B")
                || !latest.path("site").asText().equals("s1") || latest.path("planVersion").asInt() != 1
                || !latest.path("artifact").path("digest").equals(manifest.get("artifactDigest"))
                || !latest.path("state").asText().equals(state) || !latest.path("records").isIntegralNumber()
                || latest.path("records").longValue() != statusRecords
                || failed && !latest.path("error").equals(test.get("expectedError"))) {
            throw new IllegalStateException("signed-run terminal status differs");
        }
        ObjectNode result = JSON.createObjectNode().put("schema", "opentsx.rust-b1-run-verified.v1").put("case", name)
                .put("records", records.size()).put("distinct", seen.size()).put("duplicates", records.size() - seen.size())
                .put("checkpoint", checkpoint).put("statusState", state).put("statusRecords", statusRecords).put("matched", seen.size());
        result.set("error", test.get("expectedError"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verified-" + name + ".json").toFile(), result);
        System.out.println(result);
    }
}
