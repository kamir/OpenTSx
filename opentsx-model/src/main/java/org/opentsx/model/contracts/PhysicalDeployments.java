package org.opentsx.model.contracts;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PhysicalDeployments {
    public static final String SCHEMA = "opentsx.physical-deployment.v1";
    public static final String COMPUTATION_SCHEMA = "opentsx.computation.v1";
    public static final int MAX_BYTES = 4_194_304;
    private static final Set<String> FIELDS = Set.of("schema", "deployment", "generation", "sourceDagText", "specHash",
            "computationText", "computationHash", "createdAtMicros", "expiresAtMicros", "assignmentTexts", "placements",
            "ownership", "channels", "capabilities", "planHash");
    private static final Set<String> TOPIC = Set.of("binding", "cluster", "topic", "incarnation", "partitionCount");
    private static final Set<String> CHANNEL = Set.of("assignment", "from", "fromPort", "to", "toPort", "dataType",
            "transport", "binding", "cluster", "topic", "incarnation", "partitionCount", "residency", "schema", "key",
            "partitioner", "bufferRecords", "backpressure", "completion");
    private static final Set<String> CAPABILITY = Set.of("assignment", "digest", "observedAtMicros", "validUntilMicros", "reportText");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                            .maxStringLength(MAX_BYTES).maxNumberLength(1024).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Comparator<String> KEYS = (left, right) -> {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            int x = left.codePointAt(a), y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x);
            b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    };

    private PhysicalDeployments() { }

    public static final class Refused extends IllegalArgumentException {
        private final String code;
        private final String node;
        private final String field;

        public Refused(String code, String node, String field) {
            super("Physical deployment refused: " + code + ".");
            this.code = code;
            this.node = node;
            this.field = field;
        }

        public String code() { return code; }
        public String node() { return node; }
        public String field() { return field; }
        public ObjectNode document() {
            ObjectNode result = JSON.createObjectNode().put("schema", "opentsx.plan-refusal.v1").put("code", code)
                    .put("field", field).put("message", getMessage());
            if (node == null) result.putNull("node"); else result.put("node", node);
            return result;
        }
    }

    public static void validate(String text) { validated(text); }

    public static void admit(String text, JsonNode facts, String nowMicros) {
        JsonNode document = validated(text);
        exact(facts, Set.of("observedAtMicros", "validUntilMicros", "topics", "capabilities", "residency"), "facts", "/facts");
        noNumbers(facts, "/facts");
        long now = decimal(JSON.getNodeFactory().textNode(nowMicros), false, "freshness", "/nowMicros");
        long observed = decimal(facts.path("observedAtMicros"), false, "freshness", "/facts/observedAtMicros");
        long until = decimal(facts.path("validUntilMicros"), false, "freshness", "/facts/validUntilMicros");
        long created = decimal(document.path("createdAtMicros"), false, "freshness", "/createdAtMicros");
        long expires = decimal(document.path("expiresAtMicros"), false, "freshness", "/expiresAtMicros");
        require(observed <= created && created <= now && now <= expires && expires <= until,
                "freshness", null, "/facts/validUntilMicros");
        require(facts.path("capabilities").equals(document.path("capabilities")), "capability", null, "/facts/capabilities");
        Map<String, JsonNode> topics = new HashMap<>();
        require(facts.path("topics").isArray() && facts.path("topics").size() <= 256, "facts", null, "/facts/topics");
        for (JsonNode topic : facts.path("topics")) {
            exact(topic, TOPIC, "facts", "/facts/topics");
            topic(topic, "/facts/topics");
            require(topics.putIfAbsent(topicKey(topic), topic) == null, "facts", null, "/facts/topics");
        }
        Set<String> used = new HashSet<>();
        for (JsonNode channel : document.path("channels")) {
            if (!channel.path("transport").asText().equals("topic")) continue;
            String key = topicKey(channel);
            JsonNode fact = topics.get(key);
            require(fact != null, "incarnation", endpoint(channel), "/channels");
            for (String field : TOPIC) require(channel.path(field).equals(fact.path(field)),
                    field.equals("incarnation") ? "incarnation" : "facts", endpoint(channel), "/channels/" + field);
            used.add(key);
        }
        require(used.size() == topics.size(), "facts", null, "/facts/topics");
        require(facts.path("residency").isArray(), "facts", null, "/facts/residency");
        Map<String, JsonNode> residency = new HashMap<>();
        for (JsonNode entry : facts.path("residency")) {
            exact(entry, Set.of("assignment", "sites"), "facts", "/facts/residency");
            require(residency.putIfAbsent(entry.path("assignment").asText(), entry.path("sites")) == null,
                    "facts", null, "/facts/residency");
        }
        require(residency.size() == document.path("assignmentTexts").size(), "facts", null, "/facts/residency");
        for (JsonNode channel : document.path("channels")) require(channel.path("residency")
                .equals(residency.get(channel.path("assignment").asText())), "facts", endpoint(channel), "/channels/residency");
    }

    public static String encodeComputation(JsonNode value) {
        return canonical(JSON.createArrayNode().add(COMPUTATION_SCHEMA).add(encode(value)));
    }

    public static JsonNode decodeComputation(String text) {
        JsonNode encoded = parse(text, "computation", "/computationText");
        require(encoded.isArray() && encoded.size() == 2 && encoded.get(0).isTextual()
                && encoded.get(0).asText().equals(COMPUTATION_SCHEMA), "computation", null, "/computationText");
        JsonNode decoded = decode(encoded.get(1));
        require(text.equals(encodeComputation(decoded)), "computation", null, "/computationText");
        return decoded;
    }

    public static String canonical(JsonNode value) {
        StringBuilder result = new StringBuilder();
        append(value, result);
        return result.toString();
    }

    public static String planHash(JsonNode plan) {
        require(plan != null && plan.isObject(), "json", null, "/");
        ObjectNode payload = plan.deepCopy();
        payload.remove("planHash");
        return ExecutionAssignments.sha256(canonical(payload));
    }

    private static JsonNode validated(String text) {
        JsonNode document = parse(text, "json", "/");
        exact(document, FIELDS, "fields", "/");
        require(document.path("schema").asText().equals(SCHEMA), "version", null, "/schema");
        noNumbers(document, "");
        id(document.path("deployment"), "/deployment");
        decimal(document.path("generation"), true, "identity", "/generation");
        long created = decimal(document.path("createdAtMicros"), false, "freshness", "/createdAtMicros");
        long expires = decimal(document.path("expiresAtMicros"), false, "freshness", "/expiresAtMicros");
        require(created <= expires, "freshness", null, "/expiresAtMicros");
        for (String field : List.of("specHash", "computationHash", "planHash"))
            require(document.path(field).isTextual() && document.path(field).asText().matches("[0-9a-f]{64}"), "hash", null, "/" + field);
        require(document.path("sourceDagText").isTextual() && document.path("sourceDagText").asText().getBytes(StandardCharsets.UTF_8).length <= ExecutionAssignments.MAX_DAG_BYTES,
                "limit", null, "/sourceDagText");
        require(document.path("specHash").asText().equals(ExecutionAssignments.sha256(document.path("sourceDagText").asText())), "hash", null, "/specHash");
        require(document.path("computationText").isTextual() && document.path("computationHash").asText()
                .equals(ExecutionAssignments.sha256(document.path("computationText").asText())), "hash", null, "/computationHash");
        require(document.path("planHash").asText().equals(planHash(document)), "hash", null, "/planHash");
        JsonNode dag = decodeComputation(document.path("computationText").asText());
        require(dag.isObject() && dag.path("nodes").isArray() && !dag.path("nodes").isEmpty()
                && dag.path("source").path("topics").isArray() && !dag.path("source").path("topics").isEmpty()
                && dag.path("sinks").isArray() && !dag.path("sinks").isEmpty(), "computation", null, "/computationText");
        Map<String, JsonNode> logical = nodes(dag);
        require(document.path("assignmentTexts").isArray() && !document.path("assignmentTexts").isEmpty()
                && document.path("assignmentTexts").size() <= 128, "assignment", null, "/assignmentTexts");
        List<String> rawAssignments = new ArrayList<>();
        for (JsonNode raw : document.path("assignmentTexts")) {
            require(raw.isTextual(), "assignment", null, "/assignmentTexts");
            rawAssignments.add(raw.asText());
        }
        try { ExecutionAssignments.validateSet(rawAssignments); }
        catch (IllegalArgumentException error) { throw refused("assignment", null, "/assignmentTexts"); }
        Map<String, JsonNode> assignments = new LinkedHashMap<>();
        Map<String, JsonNode> fragments = new HashMap<>();
        for (String raw : rawAssignments) {
            JsonNode assignment = parse(raw, "assignment", "/assignmentTexts");
            for (String field : List.of("deployment", "generation", "sourceDagText", "specHash"))
                require(assignment.path(field).equals(document.path(field)), "assignment", null, "/assignmentTexts/" + field);
            require(assignment.path("mode").asText().equals("bounded") && assignment.path("runtime").asText().equals("rust"),
                    "assignment", null, "/assignmentTexts");
            String name = assignment.path("assignment").asText();
            assignments.put(name, assignment);
            JsonNode fragment = parse(assignment.path("fragmentText").asText(), "assignment", "/assignmentTexts/fragmentText");
            encodeComputation(fragment);
            fragments.put(name, fragment);
            require(fragment.path("source").isObject(), "coverage", null, "/assignmentTexts/fragmentText/source");
            ObjectNode fragmentSource = fragment.path("source").deepCopy();
            ObjectNode logicalSource = dag.path("source").deepCopy();
            fragmentSource.remove("topics"); logicalSource.remove("topics");
            require(encodeComputation(fragmentSource).equals(encodeComputation(logicalSource)),
                    "coverage", null, "/assignmentTexts/fragmentText/source");
            ArrayNode inputTopics = JSON.createArrayNode(); assignment.path("inputs").forEach(input -> inputTopics.add(input.path("topic").asText()));
            require(fragment.path("source").path("topics").equals(inputTopics), "coverage", null, "/assignmentTexts/fragmentText/source/topics");
            require(encodeComputation(fragment.path("sinks")).equals(encodeComputation(dag.path("sinks"))),
                    "coverage", null, "/assignmentTexts/fragmentText/sinks");
            for (JsonNode node : fragment.path("nodes")) {
                String original = node.path("id").asText();
                require(logical.containsKey(original) && encodeComputation(node).equals(encodeComputation(logical.get(original))),
                        "coverage", original, "/assignmentTexts/fragmentText/nodes");
            }
        }
        placements(document, assignments, fragments, logical);
        Map<String, JsonNode> capabilities = capabilities(document, assignments, created, expires);
        ownership(document, assignments, dag);
        channels(document, assignments, fragments, dag, capabilities);
        return document;
    }

    private static void placements(JsonNode document, Map<String, JsonNode> assignments, Map<String, JsonNode> fragments,
                                   Map<String, JsonNode> logical) {
        require(document.path("placements").isArray() && document.path("placements").size() == assignments.size(),
                "coverage", null, "/placements");
        Set<String> seen = new HashSet<>(), covered = new HashSet<>();
        for (JsonNode placement : document.path("placements")) {
            exact(placement, Set.of("assignment", "nodes"), "coverage", "/placements");
            String assignment = placement.path("assignment").asText();
            require(assignments.containsKey(assignment) && seen.add(assignment), "coverage", null, "/placements/assignment");
            ArrayNode expected = JSON.createArrayNode();
            fragments.get(assignment).path("nodes").forEach(node -> expected.add(node.path("id").asText()));
            require(expected.equals(placement.path("nodes")), "coverage", null, "/placements/nodes");
            expected.forEach(node -> covered.add(node.asText()));
        }
        require(covered.equals(logical.keySet()), "coverage", null, "/placements/nodes");
    }

    private static Map<String, JsonNode> capabilities(JsonNode document, Map<String, JsonNode> assignments,
                                                       long created, long expires) {
        require(document.path("capabilities").isArray() && document.path("capabilities").size() == assignments.size(),
                "capability", null, "/capabilities");
        Map<String, JsonNode> reports = new HashMap<>();
        for (JsonNode capability : document.path("capabilities")) {
            exact(capability, CAPABILITY, "capability", "/capabilities");
            String name = capability.path("assignment").asText();
            JsonNode assignment = assignments.get(name);
            require(assignment != null && !reports.containsKey(name) && capability.path("reportText").isTextual(),
                    "capability", null, "/capabilities/assignment");
            require(capability.path("digest").asText().equals(ExecutionAssignments.sha256(capability.path("reportText").asText())),
                    "capability", null, "/capabilities/digest");
            long observed = decimal(capability.path("observedAtMicros"), false, "freshness", "/capabilities/observedAtMicros");
            long until = decimal(capability.path("validUntilMicros"), false, "freshness", "/capabilities/validUntilMicros");
            require(observed <= created && expires <= until, "freshness", null, "/capabilities/validUntilMicros");
            JsonNode report = parse(capability.path("reportText").asText(), "capability", "/capabilities/reportText");
            for (String field : List.of("node", "site", "artifactDigest", "catalog"))
                require(report.path(field).equals(assignment.path(field)), "capability", null, "/capabilities/reportText/" + field);
            require(report.path("schema").asText().equals("opentsx.local-worker.v1") && report.path("limits").path("records").isIntegralNumber()
                    && report.path("limits").path("records").canConvertToInt() && report.path("limits").path("records").asInt() > 0,
                    "capability", null, "/capabilities/reportText/limits/records");
            reports.put(name, report);
        }
        return reports;
    }

    private static void ownership(JsonNode document, Map<String, JsonNode> assignments, JsonNode dag) {
        require(document.path("ownership").isArray(), "coverage", null, "/ownership");
        Map<String, JsonNode> expected = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : assignments.entrySet()) for (JsonNode input : entry.getValue().path("inputs"))
            expected.put(entry.getKey() + "\n" + topicKey(input), input);
        Set<String> claims = new HashSet<>(), sources = new HashSet<>();
        for (JsonNode owner : document.path("ownership")) {
            Set<String> fields = new HashSet<>(TOPIC); fields.add("assignment");
            exact(owner, fields, "coverage", "/ownership");
            topic(owner, "/ownership");
            String name = owner.path("assignment").asText();
            JsonNode input = expected.remove(name + "\n" + topicKey(owner));
            require(input != null && owner.path("partitionCount").equals(input.path("partitionCount")), "coverage", null, "/ownership");
            String claim = canonical(JSON.createArrayNode().add(assignments.get(name).path("fragment").asText())
                    .add(owner.path("cluster").asText()).add(owner.path("incarnation").asText()).add(owner.path("topic").asText()));
            require(claims.add(claim), "coverage", null, "/ownership");
            sources.add(owner.path("topic").asText());
        }
        Set<String> logical = new HashSet<>(); dag.path("source").path("topics").forEach(topic -> logical.add(topic.asText()));
        require(expected.isEmpty() && sources.equals(logical), "coverage", null, "/ownership");
    }

    private record Edge(String assignment, String from, String fromPort, String to, String toPort) { }
    private record Expected(String topic, boolean external) { }

    private static void channels(JsonNode document, Map<String, JsonNode> assignments, Map<String, JsonNode> fragments,
                                 JsonNode dag, Map<String, JsonNode> capabilities) {
        Map<Edge, Expected> expected = new HashMap<>();
        Set<String> originalNodes = nodes(dag).keySet();
        List<String> sourceTopics = new ArrayList<>(); dag.path("source").path("topics").forEach(t -> sourceTopics.add(t.asText()));
        for (Map.Entry<String, JsonNode> entry : fragments.entrySet()) {
            String name = entry.getKey();
            Set<String> ids = nodes(entry.getValue()).keySet();
            for (JsonNode node : entry.getValue().path("nodes")) {
                String to = node.path("id").asText();
                for (int port = 0; port < node.path("in").size(); port++) {
                    String from = node.path("in").get(port).asText();
                    require(ids.contains(from), "channel", to, "/channels");
                    expected.put(new Edge(name, from, "0", to, Integer.toString(port)), new Expected(null, false));
                }
                if (node.path("op").asText().equals("decode.avro-soe")) for (JsonNode input : assignments.get(name).path("inputs")) {
                    String topic = input.path("topic").asText();
                    int port = sourceTopics.indexOf(topic);
                    require(port >= 0, "channel", to, "/channels");
                    expected.put(new Edge(name, "$source", Integer.toString(port), to, "0"), new Expected(topic, true));
                }
            }
            for (int port = 0; port < dag.path("sinks").size(); port++) {
                JsonNode sink = dag.path("sinks").get(port);
                if (ids.contains(sink.path("from").asText())) expected.put(new Edge(name, sink.path("from").asText(), "0", "$sink", Integer.toString(port)),
                        new Expected(sink.path("topic").asText(), true));
            }
        }
        require(document.path("channels").isArray(), "channel", null, "/channels");
        Map<String, JsonNode> owners = new HashMap<>();
        document.path("ownership").forEach(owner -> owners.put(owner.path("assignment").asText() + "\n" + topicKey(owner), owner));
        for (int index = 0; index < document.path("channels").size(); index++) {
            JsonNode channel = document.path("channels").get(index);
            String at = "/channels/" + index;
            exact(channel, CHANNEL, "channel", at);
            String name = channel.path("assignment").asText();
            String candidate = endpoint(channel);
            String node = originalNodes.contains(candidate) ? candidate : null;
            JsonNode assignment = assignments.get(name);
            Edge edge = new Edge(name, channel.path("from").asText(), channel.path("fromPort").asText(),
                    channel.path("to").asText(), channel.path("toPort").asText());
            Expected target = expected.remove(edge);
            require(assignment != null && target != null, "channel", node, at);
            decimal(channel.path("fromPort"), false, "channel", at + "/fromPort");
            decimal(channel.path("toPort"), false, "channel", at + "/toPort");
            require(channel.path("key").asText().equals("seriesId") && channel.path("backpressure").asText().equals("block")
                    && channel.path("completion").asText().equals("closed-cut"), "channel", node, at);
            long buffer = decimal(channel.path("bufferRecords"), true, "channel", at + "/bufferRecords");
            require(buffer <= capabilities.get(name).path("limits").path("records").asLong(), "channel", node, at + "/bufferRecords");
            require(channel.path("residency").isArray() && !channel.path("residency").isEmpty(), "channel", node, at + "/residency");
            Set<String> sites = new HashSet<>();
            for (JsonNode site : channel.path("residency")) {
                id(site, at + "/residency");
                require(sites.add(site.asText()), "channel", node, at + "/residency");
            }
            require(sites.contains(assignment.path("site").asText()), "channel", node, at + "/residency");
            if (target.external()) {
                topic(channel, at);
                require(channel.path("transport").asText().equals("topic") && channel.path("dataType").asText().equals("episode")
                        && channel.path("schema").asText().equals("opentsx.episode.v2") && channel.path("partitioner").asText().equals("murmur2")
                        && channel.path("topic").asText().equals(target.topic()), "channel", node, at);
                if (edge.from().equals("$source")) {
                    JsonNode owner = owners.get(name + "\n" + topicKey(channel));
                    require(owner != null, "channel", node, at);
                    for (String field : TOPIC) require(channel.path(field).equals(owner.path(field)), "channel", node, at + "/" + field);
                }
            } else {
                require(channel.path("transport").asText().equals("memory") && channel.path("dataType").asText().equals("series")
                        && channel.path("schema").asText().equals("opentsx.series.v1") && channel.path("partitioner").asText().equals("none")
                        && channel.path("partitionCount").asText().equals("0"), "channel", node, at);
                for (String field : List.of("binding", "cluster", "topic", "incarnation")) require(channel.path(field).isNull(), "channel", node, at + "/" + field);
            }
        }
        if (!expected.isEmpty()) {
            Edge missing = expected.keySet().stream().sorted(Comparator.comparing(e -> e.assignment() + "/" + e.to() + "/" + e.toPort())).findFirst().orElseThrow();
            throw refused("channel", missing.to().equals("$sink") ? missing.from() : missing.to(), "/channels");
        }
    }

    private static Map<String, JsonNode> nodes(JsonNode dag) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode node : dag.path("nodes")) {
            require(node.isObject() && node.path("id").isTextual() && node.path("id").asText().matches("[a-z0-9][a-z0-9-]{0,62}") && node.path("op").isTextual()
                    && result.putIfAbsent(node.path("id").asText(), node) == null, "computation", null, "/computationText/nodes");
        }
        return result;
    }

    private static void topic(JsonNode topic, String at) {
        id(topic.path("binding"), at + "/binding");
        for (String field : List.of("cluster", "incarnation")) require(topic.path(field).isTextual() && !topic.path(field).asText().isBlank()
                && topic.path(field).asText().getBytes(StandardCharsets.UTF_8).length <= 256, "incarnation", null, at + "/" + field);
        require(ExecutionAssignments.validTopic(topic.path("topic").asText()), "channel", null, at + "/topic");
        require(decimal(topic.path("partitionCount"), true, "channel", at + "/partitionCount") <= 128, "channel", null, at + "/partitionCount");
    }

    private static String topicKey(JsonNode node) { return node.path("binding").asText() + "\n" + node.path("topic").asText(); }
    private static String endpoint(JsonNode channel) {
        String to = channel.path("to").asText();
        return to.equals("$sink") ? channel.path("from").asText() : to;
    }

    private static ArrayNode encode(JsonNode value) {
        require(value != null, "computation", null, "/computationText");
        ArrayNode result = JSON.createArrayNode();
        if (value.isNull()) return result.add("null");
        if (value.isBoolean()) return result.add("boolean").add(value.booleanValue());
        if (value.isTextual()) return result.add("string").add(value.textValue());
        if (value.isNumber()) {
            double number = value.doubleValue();
            require(Double.isFinite(number) && (!value.isIntegralNumber()
                    || new BigDecimal(number).toBigInteger().equals(value.bigIntegerValue())), "computation", null, "/computationText");
            return result.add("f64").add(HexFormat.of().toHexDigits(Double.doubleToRawLongBits(number)));
        }
        if (value.isArray()) {
            result.add("array"); ArrayNode entries = result.addArray(); value.forEach(item -> entries.add(encode(item))); return result;
        }
        require(value.isObject(), "computation", null, "/computationText");
        result.add("object"); ArrayNode entries = result.addArray();
        List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); keys.sort(KEYS);
        for (String key : keys) entries.addArray().add(key).add(encode(value.get(key)));
        return result;
    }

    private static JsonNode decode(JsonNode value) {
        require(value.isArray() && !value.isEmpty() && value.get(0).isTextual(), "computation", null, "/computationText");
        String type = value.get(0).asText();
        if (type.equals("null")) { require(value.size() == 1, "computation", null, "/computationText"); return JSON.nullNode(); }
        require(value.size() == 2, "computation", null, "/computationText");
        JsonNode item = value.get(1);
        switch (type) {
            case "string": require(item.isTextual(), "computation", null, "/computationText"); return item;
            case "boolean": require(item.isBoolean(), "computation", null, "/computationText"); return item;
            case "f64":
                require(item.isTextual() && item.asText().matches("[0-9a-f]{16}"), "computation", null, "/computationText");
                double number = Double.longBitsToDouble(Long.parseUnsignedLong(item.asText(), 16));
                require(Double.isFinite(number), "computation", null, "/computationText"); return DoubleNode.valueOf(number);
            case "array":
                require(item.isArray(), "computation", null, "/computationText");
                ArrayNode array = JSON.createArrayNode(); item.forEach(entry -> array.add(decode(entry))); return array;
            case "object":
                require(item.isArray(), "computation", null, "/computationText");
                ObjectNode object = JSON.createObjectNode(); String previous = null;
                for (JsonNode pair : item) {
                    require(pair.isArray() && pair.size() == 2 && pair.get(0).isTextual(), "computation", null, "/computationText");
                    String key = pair.get(0).asText();
                    require(previous == null || KEYS.compare(previous, key) < 0, "computation", null, "/computationText");
                    previous = key; object.set(key, decode(pair.get(1)));
                }
                return object;
            default: throw refused("computation", null, "/computationText");
        }
    }

    private static void append(JsonNode value, StringBuilder out) {
        require(value != null, "json", null, "/");
        if (value.isObject()) {
            out.append('{'); List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); keys.sort(KEYS);
            boolean first = true;
            for (String key : keys) { if (!first) out.append(','); first = false; quoted(key, out); out.append(':'); append(value.get(key), out); }
            out.append('}');
        } else if (value.isArray()) {
            out.append('['); for (int i = 0; i < value.size(); i++) { if (i != 0) out.append(','); append(value.get(i), out); } out.append(']');
        } else if (value.isTextual()) quoted(value.textValue(), out);
        else if (value.isBoolean()) out.append(value.booleanValue());
        else if (value.isNull()) out.append("null");
        else throw refused("json", null, "/");
    }

    private static void quoted(String value, StringBuilder out) {
        require(unicode(value), "json", null, "/");
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append("\\u00").append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
            else out.append(c);
        }
        out.append('"');
    }

    private static JsonNode parse(String text, String code, String at) {
        require(text != null && unicode(text), code, null, at);
        require(text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "limit", null, at);
        try {
            JsonNode value = JSON.readTree(text);
            require(value != null, code, null, at);
            return value;
        } catch (JsonProcessingException error) { throw refused(code, null, at); }
    }

    private static boolean unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static void noNumbers(JsonNode value, String at) {
        require(!value.isNumber(), "fields", null, at);
        if (value.isObject()) value.fields().forEachRemaining(entry -> {
            require(unicode(entry.getKey()), "json", null, "/");
            noNumbers(entry.getValue(), at + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"));
        });
        else if (value.isArray()) for (int i = 0; i < value.size(); i++) noNumbers(value.get(i), at + "/" + i);
        else if (value.isTextual()) require(unicode(value.asText()), "json", null, "/");
    }

    private static long decimal(JsonNode value, boolean positive, String code, String at) {
        String text = value.asText();
        require(value.isTextual() && text.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")
                && (text.length() < 19 || text.length() == 19 && text.compareTo("9223372036854775807") <= 0), code, null, at);
        return Long.parseLong(text);
    }
    private static void id(JsonNode value, String at) {
        require(value.isTextual() && value.asText().matches("[a-z0-9][a-z0-9-]{0,62}"), "identity", null, at);
    }
    private static void exact(JsonNode value, Set<String> fields, String code, String at) {
        require(value != null && value.isObject() && value.size() == fields.size(), code, null, at);
        for (String field : fields) require(value.has(field), code, null, at);
    }
    private static void require(boolean allowed, String code, String node, String at) { if (!allowed) throw refused(code, node, at); }
    private static Refused refused(String code, String node, String at) { return new Refused("deployment." + code, node, at); }
}
