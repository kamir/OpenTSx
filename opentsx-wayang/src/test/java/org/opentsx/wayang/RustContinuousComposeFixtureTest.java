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
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RustContinuousComposeFixtureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CATALOG = Path.of("..", "systems", "processors");

    private static String source() throws Exception {
        ObjectNode dag = JSON.createObjectNode().put("schema", "opentsx.dag.v2").put("dag", "continuous-ui-fixture");
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker");
        var topics = source.putArray("topics"); var ids = source.putArray("series");
        for (int worker = 1; worker <= 3; worker++) {
            topics.add(RustContinuousComposeFixture.input(worker));
            for (int partition = 0; partition < worker; partition++) ids.add(RustRunnerComposeFixture.key(worker, partition).getSeriesId());
        }
        var nodes = dag.putArray("nodes"); nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        var range = nodes.addObject().put("id", "range").put("op", "dq.checks"); range.putArray("in").add("decode");
        range.putObject("params").put("min", 0).put("max", 100).put("mode", "gap");
        var unit = nodes.addObject().put("id", "unit").put("op", "convert.unit"); unit.putArray("in").add("range");
        unit.putObject("params").put("factor", 0.001).put("offset", 0).put("unit", "MW");
        var window = nodes.addObject().put("id", "window").put("op", "resample"); window.putArray("in").add("unit");
        window.putObject("params").put("every", "PT20S").put("agg", "MEAN");
        var sink = dag.putArray("sinks").addObject().put("from", "window").put("schema", "opentsx.episode.v2")
                .put("topic", RustContinuousComposeFixture.OUTPUT).put("origin", "MEASURED");
        sink.putObject("signal").put("system", "actual-system").put("id", "actual-signal").put("grade", "clean");
        return JSON.writeValueAsString(dag);
    }

    @Test
    void eachNewWaveProducesJavaDerivedCanonicalRecordsWithoutFlushingItsTail() throws Exception {
        String source = source(); long clock = RustRunnerComposeFixture.CREATED + 123;
        for (int blocks : List.of(1, 2, 4, 5, 6, 7)) {
            List<Episode> expected = RustContinuousComposeFixture.expected(source, clock, CATALOG, blocks);
            assertEquals(6 * blocks, expected.size());
            assertEquals(6 * (3 * blocks / 2), expected.stream().mapToInt(Episode::getCount).sum());
            var ids = new HashSet<String>(); var avro = new OpenTsxAvro();
            for (Episode episode : expected) {
                Episodes.validate(avro.decode(avro.encode(episode), Episode.class));
                ids.add(episode.getEpisodeId());
                assertEquals("actual-system", episode.getSeries().getTags().get("system"));
                assertEquals("actual-signal", episode.getSeries().getTags().get("signal"));
                assertEquals("MW", episode.getSeries().getUnit());
                assertEquals(ExecutionAssignments.sha256(source), episode.getLabels().get("specHash"));
                assertEquals("tsx-runner/0.1.0", episode.getProvenance().getProducer());
                assertEquals(clock, Micros.of(episode.getProvenance().getCreatedAt()));
            }
            assertEquals(expected.size(), ids.size());
        }
    }

    @Test
    void anOpenWindowUsesItsPredecessorSamplesAfterJavaStateRestore() throws Exception {
        String source = source();
        var stream = RustContinuousComposeFixture.stream(source, CATALOG);
        List<Episode> first = stream.add(RustContinuousComposeFixture.raw(1, 0, 0));
        assertEquals(1, first.size()); assertEquals(List.of(0.005), first.get(0).getValues());
        var restored = RustContinuousComposeFixture.stream(source, CATALOG, stream.state());
        List<Episode> next = restored.add(RustContinuousComposeFixture.raw(1, 0, 1));
        assertEquals(1, next.size()); assertEquals(List.of(0.05, 0.05), next.get(0).getValues());
        assertEquals(RustRunnerComposeFixture.CREATED + 20_000_000L, Micros.of(next.get(0).getTStart()));
        assertEquals(2, next.get(0).getCount());
        assertEquals(stream.add(RustContinuousComposeFixture.raw(1, 0, 1)).get(0).getEpisodeId(), next.get(0).getEpisodeId());
    }

    @Test
    void theFixtureRefusesIncompleteTopicSelectionAndUnboundedAppendCounts() throws Exception {
        ObjectNode source = (ObjectNode) JSON.readTree(source());
        ((ObjectNode) source.path("source")).putArray("topics").add(RustContinuousComposeFixture.input(1));
        assertThrows(IllegalStateException.class, () -> RustContinuousComposeFixture.expected(JSON.writeValueAsString(source), 1, CATALOG, 1));
        for (String count : List.of("0", "8", "01", "-1")) assertThrows(IllegalArgumentException.class, () -> RustContinuousComposeFixture.blocks(count));
    }
}
