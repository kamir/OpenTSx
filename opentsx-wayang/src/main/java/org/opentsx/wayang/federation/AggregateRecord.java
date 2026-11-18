package org.opentsx.wayang.federation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.TsxStatsMerge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class AggregateRecord {

    public static final int MAX_DELIVERY_RECORDS = 256;
    public static final int MAX_DELIVERY_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RECORD_BYTES = 1024 * 1024;
    public static final int MAX_EVIDENCE_BYTES = 128 * 1024;
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_DELIVERY_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> PARTIAL = Set.of("tsx-schema", "tsx-job", "tsx-site", "tsx-plan-version", "tsx-revision",
            "tsx-generation", "tsx-assignment", "tsx-fence", "tsx-spec-hash", "tsx-fragment-hash", "tsx-evidence");
    private static final Set<String> MERGED = Set.of("tsx-schema", "tsx-job", "tsx-plan-version", "tsx-revision",
            "tsx-generation", "tsx-spec-hash", "tsx-final", "tsx-missing-sites");
    private final String key;
    private final byte[] value;
    private final Map<String, String> headers;

    private AggregateRecord(String key, byte[] value, Map<String, String> headers) {
        if (key == null || value == null || headers == null) throw new IllegalArgumentException("aggregate key, value and headers are required");
        this.key = key;
        this.value = value.clone();
        this.headers = Collections.unmodifiableMap(new TreeMap<>(headers));
        boolean merged = headers.containsKey("tsx-final");
        if (!(merged ? MERGED : PARTIAL).equals(headers.keySet())) throw new IllegalArgumentException("aggregate header set does not match");
        long size = key.getBytes(StandardCharsets.UTF_8).length + value.length;
        for (var header : headers.entrySet()) {
            if (header.getValue() == null) throw new IllegalArgumentException("aggregate header values must be text");
            size += header.getKey().getBytes(StandardCharsets.UTF_8).length + header.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (size > MAX_RECORD_BYTES) throw new IllegalArgumentException("aggregate record exceeds byte limit");
        if (!"org.opentsx.model.v2.SeriesStats".equals(header("tsx-schema"))) throw new IllegalArgumentException("aggregate schema must be SeriesStats");
        id(header("tsx-job"));
        decimal(header("tsx-plan-version"), true);
        if (!header("tsx-plan-version").equals(header("tsx-generation"))) throw new IllegalArgumentException("aggregate generation must equal plan version");
        decimal(header("tsx-revision"), false);
        hash(header("tsx-spec-hash"));
        if (merged) {
            if (!Set.of("true", "false").contains(header("tsx-final"))) throw new IllegalArgumentException("aggregate finality must be true or false");
            String missing = header("tsx-missing-sites");
            List<String> sites = missing.isEmpty() ? List.of() : Arrays.asList(missing.split(",", -1));
            sites.forEach(AggregateRecord::id);
            if (!sites.equals(sites.stream().distinct().sorted().toList()) || header("tsx-final").equals("true") != sites.isEmpty()) {
                throw new IllegalArgumentException("aggregate missing sites and finality do not match");
            }
        } else {
            id(header("tsx-site"));
            id(header("tsx-assignment"));
            decimal(header("tsx-fence"), true);
            hash(header("tsx-fragment-hash"));
            parse(header("tsx-evidence").getBytes(StandardCharsets.UTF_8), MAX_EVIDENCE_BYTES);
        }
        SeriesStats statistics = statistics();
        if (statistics.getQuantileSketch() == null || statistics.getQuantileSketch().remaining() < 9) {
            throw new IllegalArgumentException("aggregate sketch is required");
        }
        ByteBuffer sketch = statistics.getQuantileSketch().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        sketch.get();
        double alpha = sketch.getDouble();
        TsxStatsMerge.merge(statistics.getSeriesId(), statistics.getWindowType(), Micros.of(statistics.getWindowStart()),
                Micros.of(statistics.getWindowEnd()), alpha, List.of(new TsxStatsMerge.Partial("validation", statistics)));
        if (!java.time.Duration.parse(statistics.getWindowType()).toString().equals(statistics.getWindowType())) {
            throw new IllegalArgumentException("aggregate window duration must be canonical");
        }
        if (!key.equals(key(header("tsx-job"), merged ? null : header("tsx-site"), statistics))) {
            throw new IllegalArgumentException("aggregate key does not match its statistics");
        }
    }

    public static AggregateRecord partial(AggregateInventory inventory, String assignmentId, long revision, SeriesStats statistics) {
        if (inventory == null || statistics == null) throw new IllegalArgumentException("aggregate inventory and statistics are required");
        if (revision < 0) throw new IllegalArgumentException("aggregate revision must be nonnegative");
        JsonNode assignment = inventory.assignment(assignmentId);
        byte[] value = new OpenTsxAvro().encode(statistics);
        ObjectNode evidence = JSON.createObjectNode().put("schema", "opentsx.aggregate-evidence.v1").put("kind", "bounded-source-cut")
                .put("job", inventory.job()).put("site", assignment.path("site").textValue()).put("planVersion", inventory.planVersion())
                .put("generation", assignment.path("generation").textValue()).put("assignment", assignmentId)
                .put("fence", assignment.path("fence").textValue()).put("specHash", inventory.specHash())
                .put("fragmentHash", assignment.path("fragmentHash").textValue()).put("payloadSha256", sha256(value));
        evidence.set("inputs", assignment.path("inputs"));
        Map<String, String> headers = common(inventory, revision);
        headers.put("tsx-site", assignment.path("site").textValue());
        headers.put("tsx-assignment", assignmentId);
        headers.put("tsx-fence", assignment.path("fence").textValue());
        headers.put("tsx-fragment-hash", assignment.path("fragmentHash").textValue());
        headers.put("tsx-evidence", evidence.toString());
        AggregateRecord result = new AggregateRecord(key(inventory.job(), assignment.path("site").textValue(), statistics), value, headers);
        inventory.validate(result);
        return result;
    }

    static AggregateRecord merged(AggregateInventory inventory, long revision, SeriesStats statistics, List<String> missing) {
        Map<String, String> headers = common(inventory, revision);
        headers.put("tsx-final", Boolean.toString(missing.isEmpty()));
        headers.put("tsx-missing-sites", String.join(",", missing));
        return new AggregateRecord(key(inventory.job(), null, statistics), new OpenTsxAvro().encode(statistics), headers);
    }

    private static Map<String, String> common(AggregateInventory inventory, long revision) {
        Map<String, String> headers = new TreeMap<>();
        headers.put("tsx-schema", "org.opentsx.model.v2.SeriesStats");
        headers.put("tsx-job", inventory.job());
        headers.put("tsx-plan-version", Integer.toString(inventory.planVersion()));
        headers.put("tsx-generation", Integer.toString(inventory.planVersion()));
        headers.put("tsx-revision", Long.toString(revision));
        headers.put("tsx-spec-hash", inventory.specHash());
        return headers;
    }

    private static String key(String job, String site, SeriesStats s) {
        return job + "|" + (site == null ? "" : site + "|") + s.getSeriesId() + "|" + s.getWindowType() + "|" + Micros.of(s.getWindowStart());
    }

    public String key() { return key; }
    public byte[] value() { return value.clone(); }
    public Map<String, String> headers() { return headers; }
    public String header(String name) {
        String value = headers.get(name);
        if (value == null) throw new IllegalArgumentException("missing aggregate header " + name);
        return value;
    }
    public boolean merged() { return headers.containsKey("tsx-final"); }
    public long revision() { return decimal(header("tsx-revision"), false); }
    public SeriesStats statistics() {
        try { return new OpenTsxAvro().decode(value, SeriesStats.class); }
        catch (RuntimeException e) { throw new IllegalArgumentException("invalid aggregate Avro payload", e); }
    }

    public ProducerRecord<String, byte[]> producerRecord(String topic) {
        RecordHeaders encoded = new RecordHeaders();
        headers.forEach((name, value) -> encoded.add(name, value.getBytes(StandardCharsets.UTF_8)));
        return new ProducerRecord<>(topic, null, Math.floorDiv(Micros.of(statistics().getWindowEnd()), 1000), key, value(), encoded);
    }

    public static AggregateRecord fromConsumerRecord(ConsumerRecord<byte[], byte[]> record) {
        if (record == null || record.key() == null) throw new IllegalArgumentException("aggregate Kafka key is required");
        Map<String, String> headers = new TreeMap<>();
        record.headers().forEach(header -> {
            if (headers.putIfAbsent(header.key(), utf8(header.value())) != null) throw new IllegalArgumentException("duplicate aggregate Kafka header");
        });
        return new AggregateRecord(utf8(record.key()), record.value(), headers);
    }

    static ObjectNode json(AggregateRecord record) {
        ObjectNode value = JSON.createObjectNode().put("key", record.key).put("value", Base64.getEncoder().encodeToString(record.value));
        ObjectNode headers = value.putObject("headers");
        record.headers.forEach(headers::put);
        return value;
    }

    static AggregateRecord read(JsonNode record) {
        fields(record, Set.of("key", "headers", "value"));
        JsonNode h = record.path("headers");
        if (!h.isObject()) throw new IllegalArgumentException("aggregate headers must be an object");
        Map<String, String> headers = new TreeMap<>();
        h.fieldNames().forEachRemaining(name -> headers.put(name, text(h, name)));
        String encoded = text(record, "value");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("aggregate value must be base64", e); }
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new IllegalArgumentException("aggregate value must use canonical base64");
        return new AggregateRecord(text(record, "key"), bytes, headers);
    }

    public static JsonNode toDelivery(List<AggregateRecord> records) {
        if (records == null || records.size() > MAX_DELIVERY_RECORDS) throw new IllegalArgumentException("aggregate delivery record limit exceeded");
        ObjectNode delivery = JSON.createObjectNode().put("schema", "opentsx.aggregate-delivery.v1");
        var output = delivery.putArray("records");
        records.forEach(r -> {
            if (r == null) throw new IllegalArgumentException("aggregate delivery record must not be null");
            if (r.merged()) throw new IllegalArgumentException("aggregate delivery requires site partials");
            output.add(json(r));
        });
        if (delivery.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DELIVERY_BYTES) throw new IllegalArgumentException("aggregate delivery byte limit exceeded");
        return delivery;
    }

    public static List<AggregateRecord> fromDelivery(JsonNode delivery) {
        fields(delivery, Set.of("schema", "records"));
        if (!"opentsx.aggregate-delivery.v1".equals(delivery.path("schema").textValue()) || !delivery.path("records").isArray()
                || delivery.path("records").size() > MAX_DELIVERY_RECORDS
                || delivery.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DELIVERY_BYTES) {
            throw new IllegalArgumentException("invalid aggregate delivery");
        }
        List<AggregateRecord> records = new ArrayList<>();
        delivery.path("records").forEach(n -> {
            AggregateRecord record = read(n);
            if (record.merged()) throw new IllegalArgumentException("aggregate delivery requires site partials");
            records.add(record);
        });
        return List.copyOf(records);
    }

    public static JsonNode parseDelivery(byte[] bytes) { return parse(bytes, MAX_DELIVERY_BYTES); }

    static JsonNode parse(byte[] bytes, int limit) {
        if (bytes == null || bytes.length > limit) throw new IllegalArgumentException("aggregate JSON byte limit exceeded");
        try {
            JsonNode result = JSON.readTree(utf8(bytes));
            if (result == null) throw new IllegalArgumentException("aggregate JSON is empty");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("invalid aggregate JSON", e); }
    }

    static String utf8(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("aggregate UTF-8 bytes are required");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("aggregate text must be UTF-8", e); }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("aggregate field must be text: " + field);
        return value.textValue();
    }

    static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw new IllegalArgumentException("aggregate fields do not match");
        for (String name : expected) if (!node.has(name)) throw new IllegalArgumentException("aggregate fields do not match");
    }

    static void id(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid aggregate identity");
    }

    static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid aggregate hash");
    }

    static long decimal(String value, boolean positive) {
        if (value == null || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")) throw new IllegalArgumentException("aggregate integer must be canonical decimal");
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("aggregate integer exceeds int64 bounds", e); }
    }

    static long signedDecimal(String value) {
        if (value == null || !value.matches("0|-?[1-9][0-9]*")) throw new IllegalArgumentException("aggregate timestamp must be canonical signed decimal");
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("aggregate timestamp exceeds int64 bounds", e); }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AggregateRecord r && key.equals(r.key) && headers.equals(r.headers) && Arrays.equals(value, r.value);
    }

    @Override
    public int hashCode() { return key.hashCode() * 31 + headers.hashCode() * 17 + Arrays.hashCode(value); }
}
