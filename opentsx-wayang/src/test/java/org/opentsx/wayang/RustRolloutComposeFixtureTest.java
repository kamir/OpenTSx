package org.opentsx.wayang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.v2.Episode;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RustRolloutComposeFixtureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CATALOG = Path.of("..", "systems", "processors");

    private static String source() throws Exception {
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "actual-ui-flow");
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker");
        var topics = source.putArray("topics");
        var ids = source.putArray("series");
        for (int worker = 1; worker <= 3; worker++) {
            topics.add(RustRunnerComposeFixture.input(worker));
            for (int partition = 0; partition < worker; partition++) ids.add(RustRunnerComposeFixture.key(worker, partition).getSeriesId());
        }
        var nodes = dag.putArray("nodes");
        nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        var range = nodes.addObject().put("id", "actual-range").put("op", "dq.checks");
        range.putArray("in").add("decode");
        range.putObject("params").put("min", 0).put("max", 100).put("mode", "gap");
        var unit = nodes.addObject().put("id", "actual-unit").put("op", "convert.unit");
        unit.putArray("in").add("actual-range");
        unit.putObject("params").put("factor", 0.001).put("offset", 0).put("unit", "MW");
        var resample = nodes.addObject().put("id", "actual-window").put("op", "resample");
        resample.putArray("in").add("actual-unit");
        resample.putObject("params").put("every", "PT20S").put("agg", "MEAN");
        var sink = dag.putArray("sinks").addObject().put("from", "actual-window")
                .put("topic", "tsx.rust.clean.v2").put("schema", "opentsx.episode.v2").put("origin", "MEASURED");
        sink.putObject("signal").put("system", "actual-lab").put("id", "actual-signal").put("grade", "clean");
        return JSON.writeValueAsString(dag);
    }

    @Test
    void expectedWireRecordsUseTheSubmittedUiTargetHashAndAcceptedClock() throws Exception {
        String source = source();
        long createdAt = RustRunnerComposeFixture.CREATED + 123;
        List<Episode> expected = RustRolloutComposeFixture.expected(source, createdAt, CATALOG);
        assertEquals(12, expected.size());
        assertEquals(24, expected.stream().mapToInt(Episode::getCount).sum());
        OpenTsxAvro avro = new OpenTsxAvro();
        for (Episode episode : expected) {
            Episode decoded = avro.decode(avro.encode(episode), Episode.class);
            Episodes.validate(decoded);
            assertEquals("actual-lab", decoded.getSeries().getTags().get("system"));
            assertEquals("actual-signal", decoded.getSeries().getTags().get("signal"));
            assertEquals("actual-ui-flow", decoded.getSeries().getTags().get("flow"));
            assertEquals("MW", decoded.getSeries().getUnit());
            assertEquals("MEASURED", decoded.getLabels().get("origin"));
            assertEquals(ExecutionAssignments.sha256(source), decoded.getLabels().get("specHash"));
            assertEquals("tsx-runner/0.1.0", decoded.getProvenance().getProducer());
            assertEquals(createdAt, Micros.of(decoded.getProvenance().getCreatedAt()));
            assertEquals(20_000_000L, decoded.getIntervalMicros());
        }
        assertEquals(0.005, expected.get(0).getValues().get(0), 1e-15);
        assertEquals(0.05, expected.get(0).getValues().get(1), 1e-15);
    }

    @Test
    void originalUiWhitespaceRemainsPartOfTheDerivedIdentity() throws Exception {
        String source = source();
        List<Episode> first = RustRolloutComposeFixture.expected(source, RustRunnerComposeFixture.CREATED, CATALOG);
        List<Episode> second = RustRolloutComposeFixture.expected(source + "\n", RustRunnerComposeFixture.CREATED, CATALOG);
        assertEquals(first.size(), second.size());
        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index).getSeries().getSeriesId(), second.get(index).getSeries().getSeriesId());
            assertEquals(first.get(index).getValues(), second.get(index).getValues());
            assertNotEquals(first.get(index).getEpisodeId(), second.get(index).getEpisodeId());
        }
    }

    @Test
    void aUiDagThatOmitsAWholeSourceTopicIsRefused() throws Exception {
        ObjectNode dag = (ObjectNode) JSON.readTree(source());
        ((ObjectNode) dag.path("source")).putArray("topics").add(RustRunnerComposeFixture.input(1));
        assertThrows(IllegalStateException.class, () -> RustRolloutComposeFixture.expected(
                JSON.writeValueAsString(dag), RustRunnerComposeFixture.CREATED, CATALOG));
    }
}
