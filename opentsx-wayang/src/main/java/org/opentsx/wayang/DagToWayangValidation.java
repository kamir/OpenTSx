package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.retrieval.vector.DagPlan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class DagToWayangValidation {

    static final String CATALOG = "tsx-catalog/1";
    static final List<String> SCHEMAS = List.of("opentsx.vector.v1", "opentsx.episode.v2", "org.opentsx.model.v2.SeriesStats", "opentsx.signal.v1");

    private DagToWayangValidation() { }

    static Map<String, JsonNode> structure(JsonNode dag, TopicLayout layout) {
        if (dag == null || !dag.isObject() || !dag.path("nodes").isArray()) {
            throw new IllegalArgumentException("the document is no opentsx.dag DAG (no nodes)");
        }
        if (dag.has("schema") && (!dag.path("schema").isTextual()
                || !Set.of("opentsx.dag.v1", "opentsx.dag.v2").contains(dag.path("schema").asText()))) {
            throw new IllegalArgumentException("unsupported DAG schema " + dag.path("schema").asText());
        }
        Map<String, JsonNode> nodes = DagPlan.validateNodes(dag.path("nodes"));
        if (!dag.path("sinks").isArray() || dag.path("sinks").isEmpty()) {
            throw new IllegalArgumentException("sinks must be a non-empty array");
        }
        for (JsonNode sink : dag.path("sinks")) {
            if (!SCHEMAS.contains(sink.path("schema").asText())) {
                throw new IllegalArgumentException("unsupported sink " + sink.path("topic").asText()
                        + ": schema must be " + String.join(" or ", SCHEMAS));
            }
            if (sink.path("schema").asText().equals("opentsx.vector.v1")) {
                JsonNode from = nodes.get(sink.path("from").asText());
                if (from == null || !from.path("op").asText().equals("align.vector")) {
                    throw new IllegalArgumentException("unsupported sink " + sink.path("topic").asText()
                            + ": a vector sink must read align.vector");
                }
            }
        }
        Set<String> identities = new HashSet<>();
        for (JsonNode sink : dag.path("sinks")) {
            String identity = identity(sink, layout);
            if (!identities.add(identity)) throw new IllegalArgumentException("duplicate sink identity " + identity);
        }
        return nodes;
    }

    static String identity(JsonNode sink, TopicLayout layout) {
        String signal = sink.path("signal").path("id").asText();
        if (sink.path("schema").asText().equals("opentsx.episode.v2") && !signal.isBlank()) {
            String id = signal.replaceAll("[^A-Za-z0-9._-]", "-");
            return sink.path("signal").path("grade").asText().equals("features") ? id + "--" + sink.path("from").asText() : id;
        }
        String topic = sink.path("topic").asText();
        String vector = layout.smartVectorName(topic);
        return vector == null ? topic : vector;
    }

    static void runtimes(Map<String, JsonNode> nodes, Map<String, JsonNode> catalog, TsxCompileContext context) {
        for (JsonNode node : nodes.values()) {
            String id = node.path("id").asText();
            String op = node.path("op").asText();
            JsonNode card = catalog.get(op);
            if (card == null) throw new IllegalArgumentException("node " + id + ": operator " + op + " is not in catalog " + CATALOG);
            List<TsxCompileContext.Runtime> assigned = context.assignments().get(id);
            if (context.assignments().isEmpty()) {
                if (!card.path("runtimes").path("wayang").path("status").asText().equals("available")) unsupported(id, op, card, "A");
            } else if (assigned == null) {
                unsupported(id, op, card, "A");
            } else {
                for (TsxCompileContext.Runtime runtime : assigned) {
                    if (runtime.operators().getOrDefault(op, -1) != card.path("version").asInt()
                            || !runtime.states().contains(card.path("state").path("kind").asText())) {
                        unsupported(id, op, card, runtime.track());
                    }
                }
            }
        }
    }

    private static void unsupported(String id, String op, JsonNode card, String track) {
        throw new IllegalArgumentException("node " + id + ": operator " + op + " version "
                + card.path("version").asText() + " has no runtime for track " + track);
    }

    static void parameterNames(JsonNode dag, Map<String, JsonNode> catalog) {
        for (JsonNode node : dag.path("nodes")) {
            JsonNode params = node.path("params");
            if (params.isMissingNode()) continue;
            String id = node.path("id").asText();
            if (!params.isObject()) throw new IllegalArgumentException("node " + id + ": parameters must be an object");
            JsonNode card = catalog.get(node.path("op").asText());
            Set<String> allowed = new HashSet<>();
            card.path("params").forEach(p -> allowed.add(p.path("name").asText()));
            card.path("dag").path("fixed").fieldNames().forEachRemaining(allowed::add);
            var names = params.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!allowed.contains(name)) throw new IllegalArgumentException("node " + id + ": parameter " + name + " is not in catalog " + CATALOG);
            }
        }
    }

    static void topics(JsonNode dag, TopicLayout layout) {
        Pattern topic = Pattern.compile("tsx\\." + Pattern.quote(layout.domain()) + "\\.[a-z0-9][a-z0-9._-]*\\.v[1-9][0-9]*");
        List<JsonNode> all = new ArrayList<>();
        if (!dag.path("source").path("topics").isArray() || dag.path("source").path("topics").isEmpty()) {
            throw new IllegalArgumentException("source topics must be a non-empty array");
        }
        Set<String> sources = new HashSet<>();
        for (JsonNode source : dag.path("source").path("topics")) {
            if (source.isTextual() && !sources.add(source.asText())) {
                throw new IllegalArgumentException("source topic appears more than once: " + source.asText());
            }
            all.add(source);
        }
        dag.path("sinks").forEach(s -> all.add(s.path("topic")));
        for (JsonNode value : all) {
            if (!value.isTextual() || !topic.matcher(value.asText()).matches()) {
                throw new IllegalArgumentException("topic " + value.asText() + " does not follow tsx.<domain>.<kind>.v<n> for domain " + layout.domain());
            }
        }
    }

    static void edges(JsonNode dag, Map<String, JsonNode> nodes, Map<String, JsonNode> catalog) {
        for (JsonNode node : nodes.values()) {
            String op = node.path("op").asText();
            if (op.equals("decode.avro-soe")) continue;
            JsonNode inputs = catalog.get(op).path("inputs");
            int count = node.path("in").size();
            boolean many = inputs.size() == 1 && inputs.get(0).path("cardinality").asText().equals("many");
            boolean pair = op.equals("pair.xcorr") || op.equals("pair.es");
            if ((!many && !pair && count != inputs.size()) || pair && count != 1 && count != 2) {
                throw new IllegalArgumentException("node " + node.path("id").asText() + ": wrong number of inputs for " + op);
            }
            String expected = inputs.get(0).path("type").asText();
            for (JsonNode input : node.path("in")) {
                String actual = output(catalog.get(nodes.get(input.asText()).path("op").asText()));
                if (!actual.equals(expected)) throw new IllegalArgumentException("node " + node.path("id").asText()
                        + ": input " + input.asText() + " must be " + expected + ", not " + actual);
            }
        }
        Map<String, String> schemaTypes = Map.of("opentsx.vector.v1", "vector", "opentsx.episode.v2", "series",
                "org.opentsx.model.v2.SeriesStats", "stats", "opentsx.signal.v1", "events");
        for (JsonNode sink : dag.path("sinks")) {
            JsonNode from = nodes.get(sink.path("from").asText());
            if (from == null) throw new IllegalArgumentException("sink " + sink.path("topic").asText() + ": missing input " + sink.path("from").asText());
            String actual = output(catalog.get(from.path("op").asText()));
            String expected = schemaTypes.get(sink.path("schema").asText());
            if (!actual.equals(expected)) throw new IllegalArgumentException("sink " + sink.path("topic").asText()
                    + ": schema " + sink.path("schema").asText() + " cannot read " + actual);
        }
    }

    private static String output(JsonNode card) {
        return card.path("outputs").get(0).path("type").asText();
    }

    static void federation(JsonNode dag, Map<String, JsonNode> nodes, Map<String, JsonNode> catalog, TsxCompileContext context) {
        Set<String> sinks = new HashSet<>();
        for (JsonNode sink : dag.path("sinks")) {
            String topic = sink.path("topic").asText();
            sinks.add(topic);
            TsxCompileContext.Boundary boundary = context.federatedSinks().get(topic);
            if (boundary == null) continue;
            federated(sink.path("from").asText(), topic, nodes, catalog, boundary);
        }
        if (!sinks.containsAll(context.federatedSinks().keySet())) throw new IllegalArgumentException("federated sink is not in the DAG");
        for (JsonNode node : nodes.values()) {
            if (!node.path("op").asText().equals("pair.xcorr")) continue;
            String id = node.path("id").asText();
            if (context.xcorrProductLimit().isEmpty() || !context.xcorrEstimates().containsKey(id)) {
                throw new IllegalArgumentException("node " + id + ": pair.xcorr needs an admitted product limit and estimate");
            }
            long limit = context.xcorrProductLimit().getAsLong();
            if (context.xcorrEstimates().get(id) > limit) throw new IllegalArgumentException("node " + id
                    + ": pair.xcorr exceeds the limit of this site (" + limit + " products per window)");
        }
    }

    private static void federated(String id, String topic, Map<String, JsonNode> nodes, Map<String, JsonNode> catalog,
                                   TsxCompileContext.Boundary boundary) {
        JsonNode node = nodes.get(id);
        String op = node.path("op").asText();
        if (op.equals("dq.result")) {
            if (!boundary.statisticsWindows().isEmpty()) throw new IllegalArgumentException("a signal boundary cannot select statistic windows: " + topic);
            if (!topic.matches("tsx\\.[^.]+\\.signals\\.v[1-9][0-9]*")) throw new IllegalArgumentException("federated findings need a signals sink: " + topic);
            return;
        }
        if (op.equals("stats.welford-ddsketch")) {
            if (boundary.statisticsWindows().contains("GLOBAL")) throw new IllegalArgumentException("node " + id
                    + ": a federated statistic needs a tumbling window, not GLOBAL");
            Set<String> configured = new HashSet<>();
            node.path("params").path("windows").forEach(w -> configured.add(java.time.Duration.parse(w.asText()).toString()));
            for (String selected : boundary.statisticsWindows()) {
                if (!configured.contains(java.time.Duration.parse(selected).toString())) {
                    throw new IllegalArgumentException("node " + id + ": federated window " + selected + " is not configured");
                }
            }
            if (!topic.matches("tsx\\.[^.]+\\.(aggregates|signals)\\.v[1-9][0-9]*")) {
                throw new IllegalArgumentException("federated statistics need an aggregates or signals sink: " + topic);
            }
            return;
        }
        JsonNode card = catalog.get(op);
        boolean raw = output(card).equals("series") || output(card).equals("vector");
        if (raw || card.path("merge").path("class").asText().equals("none")) {
            throw new IllegalArgumentException("node " + id + ": operator " + op + " is not mergeable and cannot feed the federated sink " + topic);
        }
        if (!raw) for (JsonNode input : node.path("in")) federated(input.asText(), topic, nodes, catalog, boundary);
    }
}
