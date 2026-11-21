package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.retrieval.clean.SeriesStream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagToWayangCatalogTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path CATALOG = Path.of("..", "systems", "processors");
    private static final TopicLayout LAYOUT = new TopicLayout("wind");
    private static final String HASH = "a".repeat(64);
    private static final List<Episode> INPUT = List.of(
            Episodes.regular(SeriesKeys.of("wind.a", Map.of(), "m/s"), 0, 1_000_000,
                    new double[]{1, 2, 1, 4, 1, 2, 1, 5, 1, 2, 1, 3}).episodeId("a").build(),
            Episodes.regular(SeriesKeys.of("wind.b", Map.of(), "m/s"), 0, 1_000_000,
                    new double[]{1, 1, 2, 1, 4, 1, 2, 1, 5, 1, 2, 1}).episodeId("b").build());
    private static final List<String> SERIES = INPUT.stream().map(e -> e.getSeries().getSeriesId()).toList();

    private static Map<String, String> cases() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("decode.avro-soe", "{}");
        cases.put("dq.checks", "{min: 0, max: 10}");
        cases.put("convert.unit", "{factor: 2, offset: 1, unit: km/h}");
        cases.put("resample", "{every: PT2S}");
        cases.put("model.wind-shear", "{fromM: 10, toM: 100, alpha: 0.2}");
        cases.put("model.power-curve", "{ratedKw: 1000, cutIn: 1, ratedWind: 5, cutOut: 25}");
        cases.put("feature.lag", "{steps: 1}");
        cases.put("feature.rolling", "{window: PT2S, fn: MEAN}");
        cases.put("stats.welford-ddsketch", "{windows: [PT4S, PT8S], alpha: 0.01}");
        cases.put("align.vector", "{every: PT2S, key: all}");
        cases.put("segment.threshold", "{direction: ABOVE, enter: 3, exit: 2}");
        cases.put("segment.extremum", "{feature: MAX, prominence: 1, window: PT4S, distance: PT1S, pre: PT1S, post: PT1S}");
        cases.put("sax.word", "{w: 4, a: 4}");
        cases.put("pair.xcorr", "{lags: 2, every: 1}");
        cases.put("pair.es", "{sigma: 0, shuffles: 2, seed: 1, every: 1}");
        cases.put("dq.result", "{min: 0, max: 10, expectedInterval: PT1S}");
        return cases;
    }

    private static JsonNode dag(String op, String params) throws Exception {
        String schema = op.equals("align.vector") ? "opentsx.vector.v1" : op.equals("stats.welford-ddsketch")
                ? "org.opentsx.model.v2.SeriesStats" : op.equals("dq.result") ? "opentsx.signal.v1" : "opentsx.episode.v2";
        String from = op.equals("decode.avro-soe") ? "decode" : "n1";
        String prior = op.equals("dq.result") ? "  - {id: statistics, op: stats.welford-ddsketch, in: [decode], params: {windows: [PT4S, PT8S]}}\n" : "";
        return YAML.readTree("""
                schema: opentsx.dag.v2
                dag: catalog-test
                source: {topics: [tsx.wind.episodes.v2], series: [%s]}
                nodes:
                  - {id: decode, op: decode.avro-soe}
                %s%s
                sinks:
                  - {from: %s, topic: tsx.wind.%s.v2, schema: %s}
                """.formatted(String.join(", ", SERIES), prior,
                op.equals("decode.avro-soe") ? "" : "  - {id: n1, op: " + op + ", in: [" + (op.equals("dq.result") ? "statistics" : "decode") + "], params: " + params + "}",
                from, op.equals("stats.welford-ddsketch") ? "aggregates" : op.equals("dq.result") ? "signals" : "features", schema));
    }

    private static TsxCompileContext context(Map<String, TsxCompileContext.Boundary> boundaries) {
        return new TsxCompileContext(Map.of(), boundaries, OptionalLong.of(100_000), Map.of("n1", 120L), Map.of(),
                Map.of("n1", new SeriesStream.Target("lab", "wind", "clean", null, "catalog-test", HASH)),
                "site-a", OptionalLong.empty(), true);
    }

    private static List<Object> execute(DagToWayang compiled, List<Episode> input) {
        CollectionSource<SpecificRecord> source = new CollectionSource<>(new ArrayList<SpecificRecord>(input), SpecificRecord.class);
        Map<String, Operator> output = compiled.outputsBySink(Map.of(LAYOUT.episodes(), source), new TsxState(Map.of()));
        String from = DagToWayangValidation.identity(compiled.dag().path("sinks").get(0), LAYOUT);
        List<Object> out = new ArrayList<>();
        LocalCallbackSink<Object> sink = LocalCallbackSink.createCollectingSink(out, Object.class);
        output.get(from).connectTo(0, sink, 0);
        new WayangContext(configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
        return out;
    }

    @TestFactory
    Stream<DynamicTest> allSixteenCatalogBindingsExecuteThroughCompiledWayangPlans() {
        assertEquals(16, cases().size());
        return cases().entrySet().stream().map(c -> DynamicTest.dynamicTest(c.getKey(), () -> {
            DagToWayang compiled = DagToWayang.compile(dag(c.getKey(), c.getValue()), LAYOUT, HASH, CATALOG, context(Map.of()));
            List<Object> output = execute(compiled, INPUT);
            assertFalse(output.isEmpty());
            for (Object record : output) {
                if (c.getKey().equals("align.vector")) assertInstanceOf(TsxVectorRecord.class, record);
                else if (c.getKey().equals("stats.welford-ddsketch")) assertInstanceOf(SeriesStats.class, record);
                else if (c.getKey().equals("dq.result")) assertEquals("opentsx.signal.v1", assertInstanceOf(JsonNode.class, record).path("schema").asText());
                else assertInstanceOf(Episode.class, record);
            }
        }));
    }

    @Test
    void aFederatedBoundaryKeepsBothConfiguredTumblingWidthsAndDropsGlobal() throws Exception {
        JsonNode dag = dag("stats.welford-ddsketch", cases().get("stats.welford-ddsketch"));
        TsxCompileContext context = context(Map.of("tsx.wind.aggregates.v2", new TsxCompileContext.Boundary(Set.of())));
        List<Object> out = execute(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context), INPUT);
        Set<String> windows = new java.util.HashSet<>();
        out.forEach(r -> windows.add(((SeriesStats) r).getWindowType()));
        assertEquals(Set.of("PT4S", "PT8S"), windows);
    }

    @Test
    void anUnboundIntermediateCleanRunNeedsExplicitTargetMetadata() throws Exception {
        JsonNode dag = YAML.readTree("""
                dag: unbound
                source: {topics: [tsx.wind.episodes.v2]}
                nodes:
                  - {id: decode, op: decode.avro-soe}
                  - {id: clean, op: convert.unit, in: [decode]}
                  - {id: stats, op: stats.welford-ddsketch, in: [clean]}
                sinks: [{from: stats, topic: tsx.wind.aggregates.v2, schema: org.opentsx.model.v2.SeriesStats}]
                """);
        DagToWayang compiled = DagToWayang.compile(dag, LAYOUT, HASH, CATALOG);
        assertEquals("node clean: bind a clean target before building the plan", assertThrows(IllegalArgumentException.class,
                () -> execute(compiled, INPUT)).getMessage());
    }

    @Test
    void aTwoTopicMultiSinkGraphBuildsOneSourcePerWholeTopic() throws Exception {
        JsonNode dag = YAML.readTree("""
                dag: multi
                source: {topics: [tsx.wind.episodes.v2, tsx.wind.synthetic.v2]}
                nodes:
                  - {id: decode, op: decode.avro-soe}
                  - {id: stats, op: stats.welford-ddsketch, in: [decode]}
                  - {id: sax, op: sax.word, in: [decode], params: {w: 2, a: 3}}
                sinks:
                  - {from: stats, topic: tsx.wind.aggregates.v2, schema: org.opentsx.model.v2.SeriesStats}
                  - {from: sax, topic: tsx.wind.features.v2, schema: opentsx.episode.v2}
                """);
        DagToWayang compiled = DagToWayang.compile(dag, LAYOUT, HASH, CATALOG);
        Map<String, TsxRead> reads = new LinkedHashMap<>();
        for (String topic : List.of(LAYOUT.episodes(), LAYOUT.synthetic())) reads.put(topic,
                new TsxRead("localhost:9092", topic, Map.of(0, new long[]{0, 1}), Set.of(), "multi", 1));
        WayangPlan plan = compiled.build(reads, new TsxState(Map.of()), "localhost:9092");
        assertEquals(2, plan.getSinks().size());
        assertTrue(plan.getSinks().stream().allMatch(TsxTopicSink.class::isInstance));
        Set<Operator> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Operator> pending = new java.util.ArrayDeque<>(plan.getSinks());
        Set<String> topics = new java.util.HashSet<>();
        int sourceCount = 0;
        while (!pending.isEmpty()) {
            Operator operator = pending.removeFirst();
            if (!visited.add(operator)) continue;
            if (operator instanceof TsxTopicSource source) {
                sourceCount++;
                topics.add(source.read().topic());
            }
            for (var input : operator.getAllInputs()) if (input.getOccupant() != null) pending.add(input.getOccupant().getOwner());
        }
        assertEquals(2, sourceCount);
        assertEquals(reads.keySet(), topics);
        assertNotNull(compiled.dag());
    }

    @Test
    void runtimeAdmissionChecksEveryReplicaAndOnlyItsAssignedOperators() throws Exception {
        JsonNode dag = dag("feature.lag", "{steps: 1}");
        TsxCompileContext.Runtime decoder = new TsxCompileContext.Runtime("A", Map.of("decode.avro-soe", 1), Set.of("none"));
        TsxCompileContext.Runtime feature = new TsxCompileContext.Runtime("B", Map.of("feature.lag", 1), Set.of("keyed"));
        TsxCompileContext.Runtime missing = new TsxCompileContext.Runtime("B", Map.of(), Set.of("keyed"));
        TsxCompileContext base = context(Map.of());
        TsxCompileContext supported = assigned(base, Map.of("decode", List.of(decoder), "n1", List.of(feature)));
        assertNotNull(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, supported));
        TsxCompileContext replicated = assigned(base, Map.of("decode", List.of(decoder), "n1", List.of(feature, missing)));
        assertEquals("node n1: operator feature.lag version 1 has no runtime for track B", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, replicated)).getMessage());
        TsxCompileContext.Runtime stateless = new TsxCompileContext.Runtime("B", Map.of("feature.lag", 1), Set.of("none"));
        assertEquals("node n1: operator feature.lag version 1 has no runtime for track B", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG,
                        assigned(base, Map.of("decode", List.of(decoder), "n1", List.of(stateless))))).getMessage());
    }

    private static TsxCompileContext assigned(TsxCompileContext base, Map<String, List<TsxCompileContext.Runtime>> assignments) {
        return new TsxCompileContext(assignments, base.federatedSinks(), base.xcorrProductLimit(), base.xcorrEstimates(),
                base.seriesByNode(), base.cleanTargets(), base.site(), base.nowMicros(), base.complete());
    }

    @Test
    void xcorrAdmissionRequiresAnExplicitTrustedBudgetAndEstimate() throws Exception {
        JsonNode dag = dag("pair.xcorr", "{lags: 2}");
        assertEquals("node n1: pair.xcorr needs an admitted product limit and estimate", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG)).getMessage());
        TsxCompileContext exact = new TsxCompileContext(Map.of(), Map.of(), OptionalLong.of(120), Map.of("n1", 120L),
                Map.of(), Map.of(), "site", OptionalLong.empty(), false);
        assertNotNull(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, exact));
    }

    @Test
    void typedEdgesRejectAStatisticsRecordAsASeriesInput() throws Exception {
        JsonNode dag = YAML.readTree("""
                dag: wrong-edge
                source: {topics: [tsx.wind.episodes.v2]}
                nodes:
                  - {id: decode, op: decode.avro-soe}
                  - {id: stats, op: stats.welford-ddsketch, in: [decode]}
                  - {id: sax, op: sax.word, in: [stats], params: {w: 2, a: 3}}
                sinks: [{from: sax, topic: tsx.wind.features.v2, schema: opentsx.episode.v2}]
                """);
        assertEquals("node sax: input stats must be series, not stats", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG)).getMessage());
    }

    @Test
    void localCleaningBeforeFederatedStatisticsIsAllowed() throws Exception {
        JsonNode dag = YAML.readTree("""
                dag: catalog-test
                source: {topics: [tsx.wind.episodes.v2]}
                nodes:
                  - {id: decode, op: decode.avro-soe}
                  - {id: n1, op: convert.unit, in: [decode], params: {factor: 2}}
                  - {id: stats, op: stats.welford-ddsketch, in: [n1], params: {windows: [PT4S, PT8S]}}
                sinks: [{from: stats, topic: tsx.wind.aggregates.v2, schema: org.opentsx.model.v2.SeriesStats}]
                """);
        TsxCompileContext context = context(Map.of("tsx.wind.aggregates.v2", new TsxCompileContext.Boundary(Set.of())));
        List<Object> out = execute(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context), INPUT);
        assertFalse(out.isEmpty());
        assertTrue(out.stream().map(SeriesStats.class::cast).noneMatch(s -> s.getWindowType().equals("GLOBAL")));
    }

    @Test
    void oneStatsNodeKeepsGlobalForTheLocalSinkAndFiltersOnlyTheFederatedEdge() throws Exception {
        JsonNode dag = dag("stats.welford-ddsketch", cases().get("stats.welford-ddsketch"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) dag.path("sinks")).addObject().put("from", "n1")
                .put("topic", LAYOUT.seriesStats()).put("schema", "org.opentsx.model.v2.SeriesStats");
        TsxCompileContext context = context(Map.of("tsx.wind.aggregates.v2", new TsxCompileContext.Boundary(Set.of())));
        DagToWayang compiled = DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context);
        CollectionSource<SpecificRecord> source = new CollectionSource<>(new ArrayList<SpecificRecord>(INPUT), SpecificRecord.class);
        Map<String, Operator> outputs = compiled.outputsBySink(Map.of(LAYOUT.episodes(), source), new TsxState(Map.of()));
        List<SpecificRecord> local = new ArrayList<>();
        List<SpecificRecord> federated = new ArrayList<>();
        LocalCallbackSink<SpecificRecord> localSink = LocalCallbackSink.createCollectingSink(local, SpecificRecord.class);
        LocalCallbackSink<SpecificRecord> federatedSink = LocalCallbackSink.createCollectingSink(federated, SpecificRecord.class);
        outputs.get(LAYOUT.seriesStats()).connectTo(0, localSink, 0);
        outputs.get("tsx.wind.aggregates.v2").connectTo(0, federatedSink, 0);
        new WayangContext(configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(localSink, federatedSink));
        assertEquals(2, local.stream().map(SeriesStats.class::cast).filter(s -> s.getWindowType().equals("GLOBAL")).count());
        assertTrue(federated.stream().map(SeriesStats.class::cast).noneMatch(s -> s.getWindowType().equals("GLOBAL")));
        assertEquals(local.size() - 2, federated.size());
    }

    /** TASK-020 U01 data edges, S2.2 step 8: raw pairs and vectors cannot cross a site boundary without a movement contract. */
    @Test
    void rawPairsAndVectorsCannotFeedAFederatedSink() throws Exception {
        for (String op : List.of("pair.xcorr", "pair.es", "align.vector")) {
            JsonNode dag = dag(op, cases().get(op));
            String sink = dag.path("sinks").get(0).path("topic").asText();
            TsxCompileContext context = context(Map.of(sink, new TsxCompileContext.Boundary(Set.of())));
            assertEquals("node n1: operator " + op + " is not mergeable and cannot feed the federated sink " + sink,
                    assertThrows(IllegalArgumentException.class, () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context)).getMessage(), op);
            assertNotNull(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context(Map.of())), op + " compiles without a site boundary");
        }
    }

    @Test
    void aSignalCollectorPreservesLocalDqFindingsWithoutMergingUpstreamStatistics() throws Exception {
        JsonNode dag = dag("dq.result", cases().get("dq.result"));
        List<Object> local = execute(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context(Map.of())), INPUT);
        TsxCompileContext context = context(Map.of("tsx.wind.signals.v2", new TsxCompileContext.Boundary(Set.of())));
        List<Object> collected = execute(DagToWayang.compile(dag, LAYOUT, HASH, CATALOG, context), INPUT);
        assertFalse(local.isEmpty());
        assertEquals(local, collected);
    }

    private static Configuration configuration() {
        Configuration configuration = new Configuration();
        configuration.setProperty("wayang.core.log.enabled", "false");
        return configuration;
    }

    @Test
    void refusesUnknownStatisticsParametersInsteadOfSelectingDefaultWindows() throws Exception {
        for (String name : List.of("window", "field")) {
            JsonNode dag = dag("stats.welford-ddsketch", name.equals("window") ? "{window: 4}" : "{field: STD}");
            assertEquals("node n1: parameter " + name + " is not in catalog tsx-catalog/1", assertThrows(IllegalArgumentException.class,
                    () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG)).getMessage());
        }
    }

    @Test
    void refusesAnUnknownDocumentSchema() throws Exception {
        JsonNode dag = dag("sax.word", "{w: 2, a: 3}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) dag).put("schema", "opentsx.dag.v3");
        assertEquals("unsupported DAG schema opentsx.dag.v3", assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG)).getMessage());
    }

    @Test
    void sourceTopicsMustBeDistinctWholeTopicReads() throws Exception {
        JsonNode dag = dag("stats.welford-ddsketch", cases().get("stats.welford-ddsketch"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) dag.path("source").path("topics")).add(LAYOUT.episodes());
        assertEquals("source topic appears more than once: " + LAYOUT.episodes(), assertThrows(IllegalArgumentException.class,
                () -> DagToWayang.compile(dag, LAYOUT, HASH, CATALOG)).getMessage());
    }

    @Test
    void publicSinkIdentityUsesTheExistingEpisodeFeatureAndVectorRules() throws Exception {
        assertEquals("park-T02", DagToWayang.sinkIdentity(YAML.readTree(
                "{schema: opentsx.episode.v2, signal: {id: park/T02}}"), LAYOUT));
        assertEquals("park-T02--lag", DagToWayang.sinkIdentity(YAML.readTree(
                "{from: lag, schema: opentsx.episode.v2, signal: {id: park/T02, grade: features}}"), LAYOUT));
        assertEquals("wind", DagToWayang.sinkIdentity(YAML.readTree(
                "{schema: opentsx.vector.v1, topic: tsx.wind.smart.wind.vectors.v1}"), LAYOUT));
        assertEquals("tsx.wind.aggregates.v2", DagToWayang.sinkIdentity(YAML.readTree(
                "{schema: org.opentsx.model.v2.SeriesStats, topic: tsx.wind.aggregates.v2}"), LAYOUT));
    }
}
