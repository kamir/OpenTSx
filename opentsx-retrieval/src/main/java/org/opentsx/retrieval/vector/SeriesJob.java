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
 * @param dag       name of the DAG
 * @param name      name of the sink within the DAG, from the signal id
 * @param topic     sink topic, the clean topic of the domain
 * @param seriesIds raw series of the source topic to read
 * @param nodes     the nodes between the source and the sink, in order
 * @param target    system, signal, grade and origin the clean episodes carry
 * @param fromStart begin at the start of the source topic, or with what arrives from now on
 */
public record SeriesJob(String dag, String name, String topic, List<String> seriesIds, List<JsonNode> nodes,
                        SeriesStream.Target target, boolean fromStart) {

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
     * @param specHash   hash of the DAG document, carried by every clean episode
     * @throws IllegalArgumentException with a message for the user when a sink cannot be run here
     */
    static List<SeriesJob> of(JsonNode dag, String episodesTopic, String cleanTopic, String specHash) {
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
            if (!topics.equals(List.of(episodesTopic))) {
                throw new IllegalArgumentException("the DAG reads " + topics + "; this runtime reads " + episodesTopic);
            }
            String topic = sink.path("topic").asText();
            JsonNode signal = sink.path("signal");
            String system = signal.path("system").asText("");
            String id = signal.path("id").asText("");
            String grade = signal.path("grade").asText("clean");
            if (system.isBlank() || id.isBlank()) {
                throw new IllegalArgumentException("a series sink names the signal it writes: signal: { system, id, grade }");
            }
            if (!grade.equals("clean")) {
                throw new IllegalArgumentException("signal " + id + ": this runtime writes the grade clean, not " + grade);
            }
            if (!topic.equals(cleanTopic)) {
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
            SeriesJob job = new SeriesJob(name, id.replaceAll("[^A-Za-z0-9._-]", "-"), topic, List.copyOf(seriesIds), List.copyOf(chain),
                    new SeriesStream.Target(system, id, grade, sink.path("origin").asText(null), name, specHash), start.equals("earliest"));
            job.chain();    // refuses operators this runtime does not run
            jobs.add(job);
        }
        return jobs;
    }
}
