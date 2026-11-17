package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.retrieval.vector.DagPlan;
import org.opentsx.retrieval.vector.SeriesJob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DagToWayang {

    private static final String CATALOG = "tsx-catalog/1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final JsonNode dag;
    private final List<SeriesJob> series;

    private DagToWayang(JsonNode dag, List<SeriesJob> series) {
        this.dag = dag;
        this.series = List.copyOf(series);
    }

    public static DagToWayang compile(JsonNode dag, TopicLayout layout, String specHash, Path catalogDirectory)
            throws IOException {
        DagPlan.validateStructure(dag, layout);
        JsonNode normalized = parameters(dag, catalogDirectory);
        DagPlan plan = DagPlan.of(normalized, layout, specHash);
        if (!plan.vectors().isEmpty()) throw new IllegalArgumentException("this runtime translates clean series sinks only");
        return new DagToWayang(normalized, plan.series());
    }

    public JsonNode dag() {
        return dag.deepCopy();
    }

    public List<SeriesJob> series() {
        return series;
    }

    public TsxIncrement.Plan plan(String bootstrap, String sinkName) {
        SeriesJob job = series.stream().filter(s -> s.name().equals(sinkName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown series sink " + sinkName));
        return (source, state) -> {
            if (!source.read().topic().equals(job.sourceTopic())) {
                throw new IllegalArgumentException("the plan reads " + job.sourceTopic() + ", not " + source.read().topic());
            }
            List<JsonNode> nodes = job.nodes();
            TsxSeriesChain chain = new TsxSeriesChain(nodes.get(nodes.size() - 1).path("id").asText(), nodes,
                    job.target(), job.seriesIds(), state);
            TsxTopicSink sink = new TsxTopicSink(bootstrap, job.topic());
            source.connectTo(0, chain, 0);
            chain.connectTo(0, sink, 0);
            return sink;
        };
    }

    public static JsonNode parameters(JsonNode dag, Path catalogDirectory) throws IOException {
        if (dag == null || !dag.isObject() || !dag.path("nodes").isArray()) {
            throw new IllegalArgumentException("the document is no opentsx.dag DAG (no nodes)");
        }
        Map<String, JsonNode> catalog = catalog(catalogDirectory);
        ObjectNode normalized = dag.deepCopy();
        for (JsonNode node : normalized.path("nodes")) {
            String id = node.path("id").asText();
            if (!node.isObject() || !node.path("id").isTextual() || id.isBlank()) {
                throw new IllegalArgumentException("every node needs a non-empty string id");
            }
            String op = node.path("op").asText();
            if (op.equals("decode.avro-soe")) continue;
            JsonNode description = catalog.get(op);
            if (description == null) {
                throw new IllegalArgumentException("node " + id + ": operator " + op + " is not in catalog " + CATALOG);
            }
            if (!description.path("runtimes").path("wayang").path("status").asText().equals("available")) {
                throw new IllegalArgumentException("node " + id + ": operator " + op + " version "
                        + description.path("version").asText() + " has no runtime for track A");
            }
        }
        for (JsonNode node : normalized.path("nodes")) {
            String id = node.path("id").asText();
            String op = node.path("op").asText();
            if (op.equals("decode.avro-soe")) continue;
            JsonNode description = catalog.get(op);
            JsonNode given = node.path("params");
            if (!given.isMissingNode() && !given.isObject()) {
                throw new IllegalArgumentException("node " + id + ": parameters must be an object");
            }
            ObjectNode params = given.isMissingNode() ? ((ObjectNode) node).putObject("params") : (ObjectNode) given;
            for (JsonNode param : description.path("params")) {
                String name = param.path("name").asText();
                String at = "node " + id + ": parameter " + name;
                if (!params.has(name)) {
                    if (param.path("required").asBoolean()) throw new IllegalArgumentException(at + " is required");
                    if (!param.has("default")) continue;
                    params.set(name, param.get("default").deepCopy());
                }
                JsonNode value = params.get(name);
                String type = param.path("type").asText();
                boolean typed = type.equals("duration")
                        ? validDuration(value, param.has("min") && param.path("min").isNumber()
                                && param.path("min").decimalValue().signum() == 0)
                        : hasType(value, type);
                if (!typed) throw new IllegalArgumentException(at + " must be a " + type);
                if (value.isNumber()) {
                    boolean below = param.has("min") && value.decimalValue().compareTo(param.path("min").decimalValue()) < 0;
                    boolean above = param.has("max") && value.decimalValue().compareTo(param.path("max").decimalValue()) > 0;
                    if (op.equals("stats.welford-ddsketch") && name.equals("alpha")) {
                        below |= value.asDouble() <= 0;
                        above |= value.asDouble() >= 1;
                    }
                    if (below || above) {
                        throw new IllegalArgumentException(at + " must be between " + param.path("min").asText("-Infinity")
                                + " and " + param.path("max").asText("Infinity"));
                    }
                }
                if (type.equals("enum") || type.equals("list") && param.has("values")) {
                    List<String> values = new ArrayList<>();
                    param.path("values").forEach(v -> values.add(v.asText()));
                    Iterable<JsonNode> entries = type.equals("list") ? value : List.of(value);
                    for (JsonNode entry : entries) {
                        if (!entry.isTextual() || !values.contains(entry.asText())) {
                            throw new IllegalArgumentException(at + " must be one of " + values);
                        }
                    }
                }
                if (op.equals("stats.welford-ddsketch") && name.equals("windows")) {
                    if (value.isEmpty()) throw new IllegalArgumentException(at + " must be a list");
                    for (JsonNode window : value) {
                        if (!hasType(window, "duration")) throw new IllegalArgumentException(at + " must be a list");
                    }
                }
            }
            description.path("dag").path("fixed").fields().forEachRemaining(f -> {
                if (!params.has(f.getKey())) params.set(f.getKey(), f.getValue().deepCopy());
            });
        }
        return normalized;
    }

    private static Map<String, JsonNode> catalog(Path directory) throws IOException {
        Map<String, JsonNode> catalog = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                JsonNode description = YAML.readTree(file.toFile());
                if (!description.path("schema").asText().equals("opentsx.processor.v1")) {
                    throw new IllegalArgumentException(file + " is no opentsx.processor.v1 description");
                }
                String op = description.path("dag").path("op").asText(description.path("id").asText());
                if (catalog.putIfAbsent(op, description) != null) throw new IllegalArgumentException("duplicate catalog operator " + op);
            }
        }
        return catalog;
    }

    private static boolean hasType(JsonNode value, String type) {
        return switch (type) {
            case "number" -> value.isNumber() && Double.isFinite(value.asDouble());
            case "integer" -> value.isNumber() && Double.isFinite(value.asDouble())
                    && value.decimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> value.isBoolean();
            case "string", "enum" -> value.isTextual();
            case "list" -> value.isArray();
            case "duration" -> validDuration(value, false);
            default -> throw new IllegalArgumentException("unknown catalog parameter type " + type);
        };
    }

    private static boolean validDuration(JsonNode value, boolean allowZero) {
        if (!value.isTextual()) return false;
        try {
            Duration duration = Duration.parse(value.asText());
            return !duration.isNegative() && (allowZero || !duration.isZero()) && duration.toNanos() % 1000 == 0;
        } catch (DateTimeParseException | ArithmeticException e) {
            return false;
        }
    }
}
