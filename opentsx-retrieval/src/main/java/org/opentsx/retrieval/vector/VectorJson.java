package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Micros;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code opentsx.vector.v1} records as JSON (docs/datamodel-2026/schemas/opentsx.vector.v1.schema.json). */
public final class VectorJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A record read back from a topic. {@code values} holds NaN where the record says null. */
    public record Row(String vector, Map<String, String> key, long fromMicros, long clockMicros, List<String> components,
                      double[] values) {
    }

    private VectorJson() {
    }

    /** The Kafka key of a measuring point: the key of the vector as sorted {@code k=v} pairs. */
    public static String keyText(Map<String, String> key) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(key).forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ",").append(k).append('=').append(v));
        return sb.toString();
    }

    /** Clock as the schema wants it: seconds, also where {@code Duration.toString} would say PT1M. */
    static String clock(long micros) {
        return micros % 1_000_000L == 0 ? "PT" + micros / 1_000_000L + "S" : "PT" + micros / 1e6 + "S";
    }

    public static ObjectNode record(String vector, VectorAligner.Point p, String dag, String specHash) {
        ObjectNode n = JSON.createObjectNode();
        n.put("schema", VectorJob.SCHEMA);
        n.put("vector", vector);
        ObjectNode key = n.putObject("key");
        p.key().forEach(key::put);
        n.put("ts", Micros.toInstant(p.toMicros()).toString());
        ObjectNode w = n.putObject("window");
        w.put("from", Micros.toInstant(p.fromMicros()).toString());
        w.put("to", Micros.toInstant(p.toMicros()).toString());
        n.put("clock", clock(p.toMicros() - p.fromMicros()));
        ArrayNode components = n.putArray("components");
        p.components().forEach(components::add);
        ArrayNode values = n.putArray("values");
        for (Double v : p.values()) {
            if (v == null) {
                values.addNull();
            } else {
                values.add(v);
            }
        }
        n.put("missing", p.missing());
        ObjectNode prov = n.putObject("provenance");
        prov.put("dag", dag);
        prov.put("specHash", specHash);
        return n;
    }

    public static byte[] bytes(JsonNode record) {
        try {
            return JSON.writeValueAsBytes(record);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses a record; null when the bytes are no {@code opentsx.vector.v1} record. */
    public static Row parse(byte[] value) {
        try {
            JsonNode n = JSON.readTree(value);
            if (n == null || !VectorJob.SCHEMA.equals(n.path("schema").asText()) || !n.path("values").isArray()
                    || n.path("values").size() != n.path("components").size() || n.path("values").size() == 0) {
                return null;
            }
            long from = Micros.of(Instant.parse(n.path("window").path("from").asText()));
            long to = Micros.of(Instant.parse(n.path("window").path("to").asText()));
            if (to <= from) {
                return null;
            }
            Map<String, String> key = new TreeMap<>();
            n.path("key").fields().forEachRemaining(e -> key.put(e.getKey(), e.getValue().asText()));
            List<String> components = new ArrayList<>();
            n.path("components").forEach(c -> components.add(c.asText()));
            double[] values = new double[components.size()];
            for (int i = 0; i < values.length; i++) {
                JsonNode v = n.path("values").get(i);
                values[i] = v.isNumber() ? v.asDouble() : Double.NaN;
            }
            return new Row(n.path("vector").asText(), key, from, to - from, components, values);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
