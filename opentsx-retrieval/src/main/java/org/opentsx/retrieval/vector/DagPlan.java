package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentsx.kafka.TopicLayout;

import java.util.List;

/**
 * What this runtime executes of a DAG document: its vector sinks ({@code opentsx.dag.v1}) and its series sinks
 * ({@code opentsx.dag.v2}). Anything else in the document is listed as skipped.
 */
public record DagPlan(List<VectorJob> vectors, List<SeriesJob> series, List<String> skipped) {

    /**
     * @throws IllegalArgumentException with a message for the user when the DAG cannot be run here
     */
    public static DagPlan of(JsonNode dag, TopicLayout layout, String specHash) {
        if (dag == null || !dag.isObject() || !dag.path("nodes").isArray()) {
            throw new IllegalArgumentException("the document is no opentsx.dag DAG (no nodes)");
        }
        List<SeriesJob> series = SeriesJob.of(dag, layout.episodes(), layout.clean(), layout.features(), specHash);
        boolean vectors = false;
        for (JsonNode sink : dag.path("sinks")) {
            vectors |= VectorJob.SCHEMA.equals(sink.path("schema").asText());
        }
        if (!vectors && !series.isEmpty()) {
            List<String> skipped = new java.util.ArrayList<>();
            for (JsonNode sink : dag.path("sinks")) {
                if (!SeriesJob.is(sink)) {
                    skipped.add(sink.path("topic").asText() + ": neither a vector nor a series sink");
                }
            }
            return new DagPlan(List.of(), series, skipped);
        }
        // with a vector sink, or with no sink this runtime writes: VectorJob says what is wrong
        VectorJob.Plan plan = VectorJob.of(dag, layout.domain(), layout.episodes());
        for (VectorJob job : plan.jobs()) {
            if (job.seriesIds().isEmpty()) {
                throw new IllegalArgumentException("a vector that keeps running needs the ids of its series (source.series)."
                        + " Load the series from the TS inventory, then build the vector.");
            }
        }
        List<String> skipped = plan.skipped().stream().filter(s -> series.isEmpty() || !s.startsWith(layout.clean() + ":")).toList();
        return new DagPlan(plan.jobs(), series, skipped);
    }
}
