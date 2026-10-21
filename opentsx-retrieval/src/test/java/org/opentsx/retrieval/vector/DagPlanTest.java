package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.kafka.TopicLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagPlanTest {

    private static final TopicLayout LAYOUT = new TopicLayout("wind");

    private static DagPlan plan(String text) throws Exception {
        return DagPlan.of(VectorJobTest.YAML.readTree(text), LAYOUT, "test-spec");
    }

    private static void refused(String text, String reason) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> plan(text));
        assertTrue(error.getMessage().contains(reason), error.getMessage());
    }

    @Test
    void acceptsEverySupportedSink() throws Exception {
        String clean = """
                - id: clean
                  op: dq.checks
                  in: [decode]
                  params: { checks: [PLAUSIBLE_RANGE], min: 0, max: 150 }
                """;
        String sink = "- { from: clean, topic: tsx.wind.clean.v2, schema: opentsx.episode.v2, signal: { system: lab, id: speed, grade: clean } }\n";
        DagPlan mixed = plan(VectorJobTest.dag(VectorJobTest.VECTOR_NODE + clean, VectorJobTest.VECTOR_SINK + sink));
        assertEquals(1, mixed.vectors().size());
        assertEquals(1, mixed.series().size());
        assertEquals(0, mixed.skipped().size());
        assertEquals(1, plan(VectorProcessorTest.cleanDag("dq.checks", "")).series().size());
    }

    @Test
    void rejectsAnUnsupportedSinkBesideAVector() {
        refused(VectorJobTest.dag(VectorJobTest.VECTOR_NODE,
                VectorJobTest.VECTOR_SINK + "- { from: decode, topic: tsx.wind.smart.raw.metrics.v2 }\n"), "unsupported sink");
    }

    @Test
    void rejectsAnUnsupportedSinkBesideASeries() {
        refused(VectorProcessorTest.cleanDag("dq.checks",
                "  - { from: decode, topic: tsx.wind.smart.raw.metrics.v2 }\n"), "unsupported sink");
    }

    @Test
    void rejectsAVectorSinkThatReadsTheWrongOperator() {
        refused(VectorJobTest.dag(VectorJobTest.VECTOR_NODE,
                VectorJobTest.VECTOR_SINK + "- { from: decode, topic: tsx.wind.smart.other.vectors.v1, schema: opentsx.vector.v1 }\n"),
                "unsupported sink");
    }

    @Test
    void rejectsDuplicateNodeIds() {
        refused(VectorJobTest.dag(VectorJobTest.VECTOR_NODE + VectorJobTest.VECTOR_NODE, VectorJobTest.VECTOR_SINK),
                "duplicate node id n1");
    }

    @Test
    void rejectsMissingInputNodesEvenOnAnUnusedBranch() {
        String broken = """
                - id: orphan
                  op: resample
                  in: [missing]
                  params: { every: PT1M }
                """;
        refused(VectorJobTest.dag(VectorJobTest.VECTOR_NODE + broken, VectorJobTest.VECTOR_SINK), "missing input missing");
    }

    @Test
    void rejectsACycleInASeriesChain() {
        refused(VectorProcessorTest.cleanDag("dq.checks", "").replace("in: [decode]", "in: [n2]"), "cycle");
    }

    @Test
    void rejectsADecoderTheRuntimeDoesNotExecute() {
        refused(VectorJobTest.dag(VectorJobTest.VECTOR_NODE, VectorJobTest.VECTOR_SINK)
                .replace("decode.avro-soe", "decode.other"), "unsupported decoder");
    }

    @Test
    void rejectsDuplicateSinkIdentities() {
        refused(VectorJobTest.dag(VectorJobTest.VECTOR_NODE, VectorJobTest.VECTOR_SINK + VectorJobTest.VECTOR_SINK),
                "duplicate sink");
    }

    @Test
    void rejectsMalformedNodeAndSinkCollections() throws Exception {
        JsonNode valid = VectorJobTest.YAML.readTree(VectorJobTest.dag(VectorJobTest.VECTOR_NODE, VectorJobTest.VECTOR_SINK));
        ((com.fasterxml.jackson.databind.node.ObjectNode) valid).putObject("sinks");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> DagPlan.of(valid, LAYOUT, "test-spec"));
        assertTrue(error.getMessage().contains("sinks must be a non-empty array"), error.getMessage());
    }
}
