package org.opentsx.model.contracts;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ExecutionAssignments {

    /** Largest original or fragment DAG text: room for a source inventory of 16,384 series ids. */
    static final int MAX_DAG_BYTES = 524_288;
    /** Largest assignment envelope: two DAG texts that JSON escaping may double, plus a 65,536-byte envelope. */
    static final int MAX_BYTES = 4 * MAX_DAG_BYTES + 65_536;
    private static final String MAX_OFFSET = "9223372036854775807";
    private static final Set<String> FIELDS = Set.of("schema", "deployment", "generation", "assignment", "attempt",
            "fence", "fragment", "site", "node", "runtime", "catalog", "artifactDigest", "specHash", "sourceDagText",
            "fragmentHash", "fragmentText", "mode", "inputs");
    private static final Set<String> WITH_OUTPUTS = withOutputs();
    private static final Set<String> INPUT = Set.of("binding", "topic", "partitionCount", "positions");
    /** An output topic (D-T21): a sink or a repartition topic that a later fragment reads whole. */
    private static final Set<String> OUTPUT = Set.of("binding", "topic", "partitionCount", "port", "kind");
    private static final Set<String> BOUNDED_POSITION = Set.of("partition", "from", "to");
    private static final Set<String> CONTINUOUS_POSITION = Set.of("partition", "from");
    private static final List<String> CONTEXT = List.of("deployment", "generation", "specHash", "mode", "catalog");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ARTIFACT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
    private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern TOPIC = Pattern.compile(
            "tsx\\.[a-z0-9][a-z0-9_-]*\\.[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9][a-z0-9-]*)*\\.v[1-9][0-9]*");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                            .maxNumberLength(MAX_BYTES).maxStringLength(MAX_BYTES).maxNameLength(MAX_BYTES).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ExecutionAssignments() {
    }

    private static Set<String> withOutputs() {
        Set<String> fields = new HashSet<>(FIELDS);
        fields.add("outputs");
        return Set.copyOf(fields);
    }

    public static void validate(String text) {
        assignment(text);
    }

    public static void validateSet(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw invalid("assignment.context");
        }
        List<JsonNode> assignments = new ArrayList<>(texts.size());
        for (String text : texts) {
            assignments.add(assignment(text));
        }
        JsonNode first = assignments.get(0);
        Map<String, String> fragments = new HashMap<>();
        for (JsonNode document : assignments) {
            for (String field : CONTEXT) {
                if (!first.get(field).equals(document.get(field))) {
                    throw invalid("assignment.context");
                }
            }
            String previous = fragments.putIfAbsent(document.get("fragment").textValue(),
                    document.get("fragmentHash").textValue());
            if (previous != null && !previous.equals(document.get("fragmentHash").textValue())) {
                throw invalid("assignment.context");
            }
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode document : assignments) {
            if (!ids.add(document.get("assignment").textValue())) {
                throw invalid("assignment.duplicate");
            }
        }
        Set<Claim> owners = new HashSet<>();
        for (JsonNode document : assignments) {
            for (JsonNode input : document.get("inputs")) {
                Claim claim = new Claim(document.get("fragment").textValue(), inputKey(input));
                if (!owners.add(claim)) {
                    throw invalid("assignment.ownership");
                }
            }
        }
        // D-T21: every output topic has exactly one writer in the set
        Set<Input> writers = new HashSet<>();
        for (JsonNode document : assignments) {
            for (JsonNode output : document.path("outputs")) {
                if (!writers.add(inputKey(output))) {
                    throw invalid("assignment.ownership");
                }
            }
        }
    }

    public static String sha256(String text) {
        if (text == null || !unicode(text)) {
            throw invalid("assignment.json");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static boolean validTopic(String value) {
        return value != null && value.length() <= 249 && TOPIC.matcher(value).matches();
    }

    private static JsonNode assignment(String text) {
        if (text != null && text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("assignment.limit");
        }
        JsonNode document = parse(text, "assignment.json");
        exactFields(document, document.has("outputs") ? WITH_OUTPUTS : FIELDS, "assignment.fields");
        if (!textEquals(document.get("schema"), "opentsx.execution-assignment.v1")) {
            throw invalid("assignment.version");
        }
        for (String field : List.of("deployment", "assignment", "attempt", "fragment", "site", "node")) {
            if (!matches(document.get(field), ID)) {
                throw invalid("assignment.identity");
            }
        }
        decimal(document.get("generation"), MAX_OFFSET, true, "assignment.epoch");
        decimal(document.get("fence"), MAX_OFFSET, true, "assignment.epoch");
        if ((!textEquals(document.get("runtime"), "java") && !textEquals(document.get("runtime"), "rust"))
                || !textEquals(document.get("catalog"), "tsx-catalog/1")
                || !matches(document.get("artifactDigest"), ARTIFACT)) {
            throw invalid("assignment.runtime");
        }
        for (String field : List.of("sourceDagText", "fragmentText")) {
            JsonNode value = document.get(field);
            if (!value.isTextual() || value.textValue().getBytes(StandardCharsets.UTF_8).length > MAX_DAG_BYTES) {
                throw invalid("assignment.dag");
            }
        }
        if (!matches(document.get("specHash"), HASH) || !matches(document.get("fragmentHash"), HASH)
                || !sha256(document.get("sourceDagText").textValue()).equals(document.get("specHash").textValue())
                || !sha256(document.get("fragmentText").textValue()).equals(document.get("fragmentHash").textValue())) {
            throw invalid("assignment.hash");
        }
        boolean bounded = textEquals(document.get("mode"), "bounded");
        if (!bounded && !textEquals(document.get("mode"), "continuous")) {
            throw invalid("assignment.mode");
        }
        JsonNode fragment = parse(document.get("fragmentText").textValue(), "assignment.dag");
        if (!fragment.path("source").isObject() || !fragment.path("nodes").isArray() || fragment.path("nodes").isEmpty()
                || !fragment.path("sinks").isArray() || fragment.path("sinks").isEmpty()) {
            throw invalid("assignment.dag");
        }
        JsonNode inputs = document.get("inputs");
        if (!inputs.isArray() || inputs.isEmpty() || inputs.size() > 128) {
            throw invalid("assignment.input");
        }
        for (JsonNode input : inputs) {
            exactFields(input, INPUT, "assignment.input");
            if (!matches(input.get("binding"), ID) || !input.get("topic").isTextual()
                    || !validTopic(input.get("topic").textValue()) || !input.get("positions").isArray()) {
                throw invalid("assignment.input");
            }
            for (JsonNode position : input.get("positions")) {
                exactFields(position, bounded ? BOUNDED_POSITION : CONTINUOUS_POSITION, "assignment.input");
            }
        }
        for (JsonNode input : inputs) {
            decimal(input.get("partitionCount"), "2147483647", true, "assignment.offset");
            for (JsonNode position : input.get("positions")) {
                decimal(position.get("partition"), "2147483647", false, "assignment.offset");
                long from = decimal(position.get("from"), MAX_OFFSET, false, "assignment.offset");
                if (bounded && decimal(position.get("to"), MAX_OFFSET, false, "assignment.offset") < from) {
                    throw invalid("assignment.offset");
                }
            }
        }
        for (JsonNode input : inputs) {
            JsonNode positions = input.get("positions");
            if (!input.get("partitionCount").textValue().equals(Integer.toString(positions.size()))) {
                throw invalid("assignment.coverage");
            }
            for (int i = 0; i < positions.size(); i++) {
                if (!positions.get(i).get("partition").textValue().equals(Integer.toString(i))) {
                    throw invalid("assignment.coverage");
                }
            }
        }
        Set<Input> claimed = new HashSet<>();
        for (JsonNode input : inputs) {
            if (!claimed.add(inputKey(input))) {
                throw invalid("assignment.ownership");
            }
        }
        if (document.has("outputs")) {
            JsonNode outputs = document.get("outputs");
            if (!outputs.isArray() || outputs.isEmpty() || outputs.size() > 128) {
                throw invalid("assignment.output");
            }
            Set<Input> written = new HashSet<>();
            for (JsonNode output : outputs) {
                exactFields(output, OUTPUT, "assignment.output");
                if (!matches(output.get("binding"), ID) || !output.get("topic").isTextual()
                        || !validTopic(output.get("topic").textValue()) || !matches(output.get("port"), ID)
                        || !(textEquals(output.get("kind"), "sink") || textEquals(output.get("kind"), "repartition"))) {
                    throw invalid("assignment.output");
                }
                decimal(output.get("partitionCount"), "2147483647", true, "assignment.output");
                Input key = inputKey(output);
                if (claimed.contains(key) || !written.add(key)) {
                    throw invalid("assignment.output");
                }
            }
        }
        return document;
    }

    private static JsonNode parse(String text, String code) {
        if (text == null || !unicode(text)) {
            throw invalid(code);
        }
        try {
            JsonNode document = JSON.readTree(text);
            if (document == null || !document.isObject() || !validScalars(document)) {
                throw invalid(code);
            }
            return document;
        } catch (JsonProcessingException e) {
            throw invalid(code);
        }
    }

    private static void exactFields(JsonNode document, Set<String> fields, String code) {
        if (!document.isObject() || document.size() != fields.size()) {
            throw invalid(code);
        }
        for (String field : fields) {
            if (!document.has(field)) {
                throw invalid(code);
            }
        }
    }

    private static long decimal(JsonNode value, String maximum, boolean positive, String code) {
        if (!value.isTextual()) {
            throw invalid(code);
        }
        String number = value.textValue();
        if (!(positive ? POSITIVE_DECIMAL : NONNEGATIVE_DECIMAL).matcher(number).matches()
                || number.length() > maximum.length()
                || (number.length() == maximum.length() && number.compareTo(maximum) > 0)) {
            throw invalid(code);
        }
        return Long.parseLong(number);
    }

    private static boolean matches(JsonNode value, Pattern pattern) {
        return value.isTextual() && pattern.matcher(value.textValue()).matches();
    }

    private static boolean textEquals(JsonNode value, String expected) {
        return value.isTextual() && expected.equals(value.textValue());
    }

    private static boolean validScalars(JsonNode value) {
        if (value.isNumber() && !Double.isFinite(value.doubleValue())) {
            return false;
        }
        if (value.isTextual() && !unicode(value.textValue())) {
            return false;
        }
        if (value.isObject()) {
            for (var field : value.properties()) {
                if (!unicode(field.getKey()) || !validScalars(field.getValue())) {
                    return false;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (!validScalars(item)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static Input inputKey(JsonNode input) {
        return new Input(input.get("binding").textValue(), input.get("topic").textValue());
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private record Input(String binding, String topic) {
    }

    private record Claim(String fragment, Input input) {
    }
}
