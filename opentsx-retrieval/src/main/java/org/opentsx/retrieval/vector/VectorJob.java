package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One vector sink of an {@code opentsx.dag.v1} document, as far as this runtime executes it: an
 * {@code align.vector} node that reads the series of the source topic directly.
 *
 * <p>Nodes between the source and the vector (resampling, normalisation, anything else) are not executed here;
 * {@link #of} refuses such a DAG and says which node stands in the way.
 *
 * @param dag        name of the DAG
 * @param topic      sink topic, {@code tsx.<domain>.smart.<name>.vectors.v1}
 * @param name       name of the vector, the {@code <name>} part of the topic
 * @param seriesIds  series of the source topic to read; empty for all
 * @param inputNames one name per input of the node
 */
public record VectorJob(String dag, String topic, String name, List<String> seriesIds, List<String> inputNames,
                        VectorAligner.Params params) {

    public static final String SCHEMA = "opentsx.vector.v1";
    public static final String OP = "align.vector";

    /** What {@link #of} found in a DAG. */
    public record Plan(List<VectorJob> jobs, List<String> skipped) {
    }

    /**
     * Reads the vector sinks of a DAG.
     *
     * @param domain        domain of this server; sinks must be vector topics of it
     * @param episodesTopic the topic this server holds; the DAG must read it
     * @throws IllegalArgumentException with a message for the user when the DAG cannot be run here
     */
    public static Plan of(JsonNode dag, String domain, String episodesTopic) {
        if (dag == null || !dag.isObject() || !dag.path("nodes").isArray()) {
            throw new IllegalArgumentException("the document is no opentsx.dag.v1 DAG (no nodes)");
        }
        List<String> topics = new ArrayList<>();
        dag.path("source").path("topics").forEach(t -> topics.add(t.asText()));
        if (!topics.equals(List.of(episodesTopic))) {
            throw new IllegalArgumentException("the DAG reads " + topics + "; this server holds " + episodesTopic);
        }
        List<String> seriesIds = new ArrayList<>();
        dag.path("source").path("series").forEach(s -> seriesIds.add(s.asText()));
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        dag.path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
        Pattern vectorTopic = Pattern.compile("tsx\\." + Pattern.quote(domain) + "\\.smart\\.([a-z0-9][a-z0-9-]*)\\.vectors\\.v1");

        List<VectorJob> jobs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (JsonNode sink : dag.path("sinks")) {
            String topic = sink.path("topic").asText();
            String from = sink.path("from").asText();
            JsonNode node = nodes.get(from);
            if (!SCHEMA.equals(sink.path("schema").asText()) || node == null || !OP.equals(node.path("op").asText())) {
                skipped.add(topic + ": not a vector sink (this runtime writes " + SCHEMA + " only)");
                continue;
            }
            Matcher m = vectorTopic.matcher(topic);
            if (!m.matches()) {
                throw new IllegalArgumentException("sink topic " + topic + " must be tsx." + domain + ".smart.<name>.vectors.v1"
                        + " (lower-case letters, digits and hyphens in <name>)");
            }
            List<String> names = new ArrayList<>();
            int i = 0;
            for (JsonNode in : node.path("in")) {
                JsonNode input = nodes.get(in.asText());
                String op = input == null ? "missing" : input.path("op").asText();
                if (!op.startsWith("decode.")) {
                    throw new IllegalArgumentException("node " + in.asText() + " (" + op + ") is not available in this runtime: it"
                            + " executes align.vector on the series of the source topic only. Build the vector from the source bucket.");
                }
                JsonNode given = node.path("params").path("names").path(i);
                names.add(given.isTextual() && !given.asText().isBlank() ? given.asText() : "in" + (i + 1));
                i++;
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException("node " + from + " has no input");
            }
            jobs.add(new VectorJob(dag.path("dag").asText("dag"), topic, m.group(1), List.copyOf(seriesIds), names,
                    params(from, node.path("params"))));
        }
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("the DAG has no vector sink. Publish an \"Align to vector\" node."
                    + (skipped.isEmpty() ? "" : " Skipped: " + skipped));
        }
        return new Plan(jobs, skipped);
    }

    private static VectorAligner.Params params(String node, JsonNode p) {
        try {
            return new VectorAligner.Params(Duration.parse(p.path("every").asText("")), p.path("key").asText("series"),
                    VectorAligner.Agg.valueOf(p.path("agg").asText("MEAN")), "as-of".equals(p.path("fill").asText()),
                    p.hasNonNull("maxAge") ? Duration.parse(p.path("maxAge").asText()) : null,
                    "complete".equals(p.path("emit").asText()));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("node " + node + ": every and maxAge must be durations like PT60S");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("node " + node + ": " + e.getMessage());
        }
    }
}
