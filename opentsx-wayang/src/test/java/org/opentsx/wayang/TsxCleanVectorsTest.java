package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.clean.SeriesStream;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-020 A2.1: the clean operators on Wayang reproduce docs/datamodel-2026/testvectors/clean-ops.v1.json with the
 * comparison of CleanChainTest: within 1e-12 relative to the value, at least 1e-12; a gap must be a gap.
 */
class TsxCleanVectorsTest {

    private static final Path VECTORS = Path.of("..", "docs", "datamodel-2026", "testvectors", "clean-ops.v1.json");
    private static final long SECOND = 1_000_000L;
    static final SeriesKey RAW = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p1", "turbine", "T01"), "m/s");
    static final SeriesStream.Target TARGET = new SeriesStream.Target("sys", "wind", "clean", "MEASURED", "flow-1",
            "a".repeat(64));

    static Episode episode(JsonNode c) {
        long t0 = Instant.parse(c.path("t0").asText()).toEpochMilli() * 1000;
        long every = c.path("intervalSeconds").asLong() * SECOND;
        JsonNode a = c.path("values");
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = a.get(i).isNull() ? Double.NaN : a.get(i).asDouble();
        }
        return Episodes.regular(RAW, t0, every, v).build();
    }

    static TsxSeriesChain chain(JsonNode c) {
        List<JsonNode> nodes = new ArrayList<>();
        c.path("nodes").forEach(nodes::add);
        return new TsxSeriesChain(nodes.get(nodes.size() - 1).path("id").asText(), nodes, TARGET, List.of(),
                new TsxState(Map.of()));
    }

    static void assertCase(JsonNode c, List<Episode> out) {
        String name = c.path("name").asText();
        long t0 = Instant.parse(c.path("t0").asText()).toEpochMilli() * 1000;
        List<Long> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Episode e : out) {
            for (long t : Episodes.timestampsMicros(e)) {
                times.add(t);
            }
            for (double v : Episodes.values(e)) {
                values.add(v);
            }
        }
        JsonNode et = c.path("expect").path("times");
        JsonNode ev = c.path("expect").path("values");
        assertEquals(et.size(), times.size(), name);
        for (int i = 0; i < et.size(); i++) {
            assertEquals(t0 + et.get(i).asLong() * SECOND, (long) times.get(i), name + " time " + i);
            if (ev.get(i).isNull()) {
                assertTrue(Double.isNaN(values.get(i)), name + " gap at " + i);
            } else {
                double e = ev.get(i).asDouble();
                assertEquals(e, values.get(i), 1e-12 * Math.max(1, Math.abs(e)), name + " at " + i);
            }
        }
        if (c.path("expect").has("unit") && !out.isEmpty()) {
            assertEquals(c.path("expect").path("unit").asText(), out.get(0).getSeries().getUnit(), name + " unit");
        }
    }

    @Test
    void everyCaseMatchesThroughTheJavaExecutionOperator() throws Exception {
        int cases = 0;
        for (JsonNode c : new ObjectMapper().readTree(VECTORS.toFile()).path("cases")) {
            assertCase(c, JavaTsxSeriesChain.run(chain(c), List.of(episode(c))));
            cases++;
        }
        assertEquals(15, cases);
    }

    @Test
    void everyCaseMatchesThroughAWayangPlan() throws Exception {
        int cases = 0;
        for (JsonNode c : new ObjectMapper().readTree(VECTORS.toFile()).path("cases")) {
            List<SpecificRecord> out = new ArrayList<>();
            CollectionSource<SpecificRecord> source = new CollectionSource<>(List.of(episode(c)), SpecificRecord.class);
            TsxSeriesChain chain = chain(c);
            LocalCallbackSink<SpecificRecord> sink = LocalCallbackSink.createCollectingSink(out, SpecificRecord.class);
            source.connectTo(0, chain, 0);
            chain.connectTo(0, sink, 0);
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
            assertCase(c, out.stream().map(r -> (Episode) r).sorted((a, b) -> a.getTStart().compareTo(b.getTStart())).toList());
            cases++;
        }
        assertEquals(15, cases);
    }

    @Test
    void progressIncludesRestoredWatermarkWaitingSeriesAndDuplicateBlocks() throws Exception {
        JsonNode c = new ObjectMapper().readTree(VECTORS.toFile()).path("cases").get(0);
        TsxSeriesChain first = chain(c);
        Episode input = episode(c);
        JavaTsxSeriesChain.run(first, List.of(input));
        TsxState restored = new TsxState(first.state().snapshot());
        TsxSeriesChain next = new TsxSeriesChain(first.node(), first.nodes(), first.target(),
                List.of(RAW.getSeriesId(), "missing"), restored);
        JavaTsxSeriesChain.run(next, List.of());
        assertEquals(org.opentsx.model.Micros.of(input.getTEnd()), restored.progress().watermark());
        assertEquals(List.of("missing"), restored.progress().waitingFor());
        assertEquals(0, restored.progress().skippedBlocks());
        JavaTsxSeriesChain.run(next, List.of(input));
        assertEquals(1, restored.progress().skippedBlocks());
        assertEquals(List.of("missing"), restored.progress().waitingFor());
    }
}
