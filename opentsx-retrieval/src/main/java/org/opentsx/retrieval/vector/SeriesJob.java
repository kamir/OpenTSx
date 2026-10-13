package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One series sink of an {@code opentsx.dag.v2} document: a chain of cleaning operators from the series of the source
 * topic to the clean grade of a signal. See docs/datamodel-2026/14-ENGINEERING-FLOW.md, section 6.
 *
 * <pre>
 * sinks:
 *   - { from: n2, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2,
 *       signal: { system: h2-chain, id: park/T02/WTUR.W, grade: clean }, origin: SYNTHETIC }
 * </pre>
 *
 * A sink may write another signal than the one it reads: {@code signal.metric} then names the metric of the written
 * series (a port of the plan has none of its own), and {@code madeOf} what it was computed from. With
 * {@code grade: features} the sink writes to the topic of the features and the node it reads gives the feature its
 * name; such a DAG reads the topic of the clean series.
 *
 * @param dag       name of the DAG
 * @param name      name of the sink within the DAG, from the signal id
 * @param topic     sink topic, the clean topic of the domain
 * @param seriesIds raw series of the source topic to read
 * @param nodes     the nodes between the source and the sink, in order
 * @param target    system, signal, grade and origin the clean episodes carry
 * @param fromStart begin at the start of the source topic, or with what arrives from now on
 */
public record SeriesJob(String dag, String name, String topic, List<String> seriesIds, List<JsonNode> nodes,
                        SeriesStream.Target target, boolean fromStart, String sourceTopic) {

    public static final String SCHEMA = "opentsx.episode.v2";

    public CleanChain chain() {
        return CleanChain.of(nodes);
    }

    /** True when the sink is one this class reads. */
    static boolean is(JsonNode sink) {
        return SCHEMA.equals(sink.path("schema").asText());
    }

    /**
     * Reads the series sinks of a DAG.
     *
     * @param cleanTopic the topic clean series of the domain go to
     * @param syntheticTopic the topic generated series of the domain live in; a flow may read them as raw series
     * @param specHash   hash of the DAG document, carried by every clean episode
     * @throws IllegalArgumentException with a message for the user when a sink cannot be run here
     */
    static List<SeriesJob> of(JsonNode dag, String episodesTopic, String cleanTopic, String featuresTopic, String syntheticTopic, String specHash) {
        List<SeriesJob> jobs = new ArrayList<>();
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        dag.path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
        List<String> seriesIds = new ArrayList<>();
        dag.path("source").path("series").forEach(s -> seriesIds.add(s.asText()));
        String name = dag.path("dag").asText("dag");
        for (JsonNode sink : dag.path("sinks")) {
            if (!is(sink)) {
                continue;
            }
            List<String> topics = new ArrayList<>();
            dag.path("source").path("topics").forEach(t -> topics.add(t.asText()));
            // raw series come from the episodes topic or, when a generator wrote them, from the topic of the generated
            // series; clean ones from the topic of the clean series
            if (!topics.equals(List.of(episodesTopic)) && !topics.equals(List.of(cleanTopic)) && !topics.equals(List.of(syntheticTopic))) {
                throw new IllegalArgumentException("the DAG reads " + topics + "; this runtime reads " + episodesTopic + ", " + syntheticTopic + " or " + cleanTopic);
            }
            String topic = sink.path("topic").asText();
            JsonNode signal = sink.path("signal");
            String system = signal.path("system").asText("");
            String id = signal.path("id").asText("");
            String grade = signal.path("grade").asText("clean");
            if (system.isBlank() || id.isBlank()) {
                throw new IllegalArgumentException("a series sink names the signal it writes: signal: { system, id, grade }");
            }
            String feature = null;
            if (grade.equals("features")) {
                feature = sink.path("from").asText();
                if (!feature.matches("[a-z][a-z0-9_]*")) {
                    throw new IllegalArgumentException("signal " + id + ": the node of a feature gives it its name; \"" + feature
                            + "\" must be lower-case letters, digits and underscores");
                }
                if (!topic.equals(featuresTopic)) {
                    throw new IllegalArgumentException("features of this domain go to " + featuresTopic + ", not " + topic);
                }
                if (!topics.equals(List.of(cleanTopic))) {
                    throw new IllegalArgumentException("signal " + id + ": features are computed from clean series (" + cleanTopic + ")");
                }
            } else if (!grade.equals("clean")) {
                throw new IllegalArgumentException("signal " + id + ": this runtime writes the grades clean and features, not " + grade);
            } else if (!topic.equals(cleanTopic)) {
                throw new IllegalArgumentException("clean series of this domain go to " + cleanTopic + ", not " + topic);
            }
            if (seriesIds.isEmpty()) {
                throw new IllegalArgumentException("a flow that keeps running needs the ids of its series (source.series)."
                        + " Open the signal from the page Signals, then publish.");
            }
            // from the sink back to the source: every node has one input
            List<JsonNode> chain = new ArrayList<>();
            String at = sink.path("from").asText();
            for (int guard = 0; guard <= nodes.size(); guard++) {
                JsonNode n = nodes.get(at);
                if (n == null) {
                    throw new IllegalArgumentException("node " + at + " is not in the DAG");
                }
                if (n.path("op").asText().startsWith("decode.")) {
                    break;
                }
                if (n.path("in").size() != 1) {
                    throw new IllegalArgumentException("node " + at + " has " + n.path("in").size() + " inputs; a clean series is one"
                            + " chain from the source");
                }
                chain.add(n);
                at = n.path("in").get(0).asText();
            }
            Collections.reverse(chain);
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("signal " + id + ": the sink reads the source without an operator; there is"
                        + " nothing to clean");
            }
            String start = dag.path("source").path("start").asText("earliest");
            if (!start.equals("earliest") && !start.equals("latest")) {
                throw new IllegalArgumentException("source.start must be earliest or latest, not " + start);
            }
            String slug = id.replaceAll("[^A-Za-z0-9._-]", "-") + (feature == null ? "" : "--" + feature);
            SeriesJob job = new SeriesJob(name, slug, topic, List.copyOf(seriesIds), List.copyOf(chain),
                    new SeriesStream.Target(system, id, grade, sink.path("origin").asText(null), name, specHash,
                            signal.path("metric").asText(null), feature, sink.path("madeOf").asText(null)), start.equals("earliest"), topics.get(0));
            job.chain();    // refuses operators this runtime does not run
            jobs.add(job);
        }
        return jobs;
    }
}
