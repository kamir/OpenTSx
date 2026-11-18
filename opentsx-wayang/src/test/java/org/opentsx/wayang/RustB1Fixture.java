package org.opentsx.wayang;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class RustB1Fixture {
    static final int KEYS = 10_000;
    static final int PARTITIONS = 12;
    static final String JAVA_TOPIC = "tsx.b1placement.java.v2";
    static final String RUST_TOPIC = "tsx.b1placement.rust.v2";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> MANIFEST_FIELDS = Set.of("schema", "count", "partitions", "javaTopic", "rustTopic", "records");
    private static final Set<String> RECORD_FIELDS = Set.of("key", "index", "partition");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private RustB1Fixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("placement-prepare <proxy-bootstrap> <directory>");
            System.out.println("placement-verify <proxy-bootstrap> <directory>");
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("expected command, Proxy bootstrap and directory");
        Path directory = Path.of(args[2]);
        switch (args[0]) {
            case "placement-prepare" -> prepare(args[1], directory);
            case "placement-verify" -> verify(args[1], directory);
            default -> throw new IllegalArgumentException("unknown B1 fixture command");
        }
    }

    static List<String> keys() {
        Random random = new Random(42);
        List<String> keys = new ArrayList<>(KEYS);
        for (int index = 0; index < KEYS; index++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            keys.add(HexFormat.of().formatHex(bytes));
        }
        if (new HashSet<>(keys).size() != KEYS) throw new IllegalStateException("the placement fixture contains duplicate keys");
        return keys;
    }

    private static void prepare(String bootstrap, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path manifest = directory.resolve("placement.json");
        if (Files.exists(manifest)) throw new IllegalArgumentException("placement evidence already exists");
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            admin.createTopics(List.of(new NewTopic(JAVA_TOPIC, PARTITIONS, (short) 1),
                    new NewTopic(RUST_TOPIC, PARTITIONS, (short) 1))).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        ObjectNode fixture = JSON.createObjectNode().put("schema", "opentsx.rust-b1-placement.v1")
                .put("count", KEYS).put("partitions", PARTITIONS).put("javaTopic", JAVA_TOPIC).put("rustTopic", RUST_TOPIC);
        var rows = fixture.putArray("records");
        List<String> keys = keys();
        List<Future<RecordMetadata>> sent = new ArrayList<>(KEYS);
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            for (int index = 0; index < KEYS; index++) {
                sent.add(producer.send(new ProducerRecord<>(JAVA_TOPIC, keys.get(index), ByteBuffer.allocate(4).putInt(index).array())));
            }
            producer.flush();
            for (int index = 0; index < KEYS; index++) {
                RecordMetadata metadata = sent.get(index).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (metadata.partition() < 0 || metadata.partition() >= PARTITIONS) throw new IllegalStateException("Java returned an invalid partition");
                rows.addObject().put("key", keys.get(index)).put("index", index).put("partition", metadata.partition());
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), fixture);
    }

    record Placement(int index, int partition) { }

    static Map<String, Placement> validateManifest(JsonNode fixture) {
        requireFields(fixture, MANIFEST_FIELDS);
        if (!fixture.get("schema").isTextual() || !fixture.get("schema").textValue().equals("opentsx.rust-b1-placement.v1")
                || requireInt(fixture, "count") != KEYS || requireInt(fixture, "partitions") != PARTITIONS
                || !fixture.get("javaTopic").isTextual() || !fixture.get("javaTopic").textValue().equals(JAVA_TOPIC)
                || !fixture.get("rustTopic").isTextual() || !fixture.get("rustTopic").textValue().equals(RUST_TOPIC)
                || !fixture.get("records").isArray() || fixture.get("records").size() != KEYS) {
            throw new IllegalStateException("invalid placement fixture inventory");
        }
        Map<String, Placement> expected = new HashMap<>();
        int position = 0;
        for (JsonNode row : fixture.get("records")) {
            requireFields(row, RECORD_FIELDS);
            int index = requireInt(row, "index");
            int partition = requireInt(row, "partition");
            if (!row.get("key").isTextual() || !KEY.matcher(row.get("key").textValue()).matches()
                    || index != position || partition < 0 || partition >= PARTITIONS) {
                throw new IllegalStateException("invalid placement fixture record");
            }
            if (expected.put(row.get("key").textValue(), new Placement(index, partition)) != null) {
                throw new IllegalStateException("duplicate expected placement key");
            }
            position++;
        }
        return Map.copyOf(expected);
    }

    private static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()
                || fields.stream().anyMatch(field -> !node.has(field))) {
            throw new IllegalStateException("invalid placement fixture fields");
        }
    }

    private static int requireInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("invalid placement fixture integer: " + field);
        }
        return value.intValue();
    }

    private static void verify(String bootstrap, Path directory) throws Exception {
        Map<String, Placement> expected = validateManifest(JSON.readTree(directory.resolve("placement.json").toFile()));
        for (String topic : List.of(JAVA_TOPIC, RUST_TOPIC)) {
            Map<String, Integer> seen = new HashMap<>();
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
                var inventory = consumer.partitionsFor(topic, TIMEOUT);
                if (inventory == null || inventory.size() != PARTITIONS) throw new IllegalStateException("placement topic partition count differs");
                List<TopicPartition> partitions = new ArrayList<>();
                for (int partition = 0; partition < PARTITIONS; partition++) partitions.add(new TopicPartition(topic, partition));
                var low = consumer.beginningOffsets(partitions, TIMEOUT);
                var high = consumer.endOffsets(partitions, TIMEOUT);
                Map<TopicPartition, long[]> ranges = new TreeMap<>((a, b) -> Integer.compare(a.partition(), b.partition()));
                long records = 0;
                for (TopicPartition partition : partitions) {
                    if (low.get(partition) != 0) throw new IllegalStateException("placement retention differs");
                    records = Math.addExact(records, high.get(partition));
                    ranges.put(partition, new long[]{0, high.get(partition)});
                }
                if (records != KEYS) throw new IllegalStateException("placement record count differs");
                LogReader.read(consumer, ranges, record -> {
                    String key = new String(record.key(), StandardCharsets.UTF_8);
                    Placement expectedRow = expected.get(key);
                    if (expectedRow == null || record.partition() != expectedRow.partition()
                            || record.value().length != 4 || ByteBuffer.wrap(record.value()).getInt() != expectedRow.index()
                            || seen.put(key, record.partition()) != null) throw new IllegalStateException("observed placement differs for a fixture key");
                }, TIMEOUT);
            }
            if (!seen.keySet().equals(expected.keySet())) throw new IllegalStateException("placement did not observe every key");
        }
        ObjectNode result = JSON.createObjectNode().put("schema", "opentsx.rust-b1-placement-result.v1")
                .put("keys", KEYS).put("partitions", PARTITIONS).put("matched", KEYS);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("placement-verified.json").toFile(), result);
        System.out.println(result);
    }
}
