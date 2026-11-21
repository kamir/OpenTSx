package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.basic.operators.FilterOperator;
import org.apache.wayang.basic.operators.MapOperator;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.retrieval.clean.CleanChain;
import org.opentsx.retrieval.clean.SeriesStream;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.retrieval.vector.VectorAligner;
import org.opentsx.stats.DataQuality;
import org.opentsx.stats.StatsEngine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DagToWayangBuild {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode dag;
    private final String specHash;
    private final TopicLayout layout;
    private final TsxCompileContext context;
    private final Map<String, Operator> sources;
    private final TsxState state;
    private final Map<String, JsonNode> nodes = new LinkedHashMap<>();
    private final Map<String, Integer> consumers = new LinkedHashMap<>();
    private final Map<String, Operator> built = new LinkedHashMap<>();

    DagToWayangBuild(JsonNode dag, String specHash, TopicLayout layout, TsxCompileContext context,
                     Map<String, Operator> sources, TsxState state) {
        this.dag = dag;
        this.specHash = specHash;
        this.layout = layout;
        this.context = context;
        this.sources = sources;
        this.state = state;
        dag.path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
        nodes.values().forEach(n -> n.path("in").forEach(i -> consumers.merge(i.asText(), 1, Integer::sum)));
        dag.path("sinks").forEach(s -> consumers.merge(s.path("from").asText(), 1, Integer::sum));
    }

    static void validateCleanRuns(JsonNode dag) {
        for (JsonNode node : dag.path("nodes")) {
            if (CleanChain.OPS.contains(node.path("op").asText())) CleanChain.of(List.of(node));
        }
    }

    static void validateVectorNames(JsonNode dag) {
        for (JsonNode node : dag.path("nodes")) {
            if (node.path("op").asText().equals("align.vector")) vectorNames(node);
        }
    }

    private static List<String> vectorNames(JsonNode node) {
        String id = node.path("id").asText();
        List<String> names = new ArrayList<>();
        node.path("params").path("names").forEach(n -> names.add(n.asText()));
        if (names.isEmpty()) for (int i = 0; i < node.path("in").size(); i++) names.add("in" + (i + 1));
        if (names.size() != node.path("in").size() || names.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("node " + id + ": names must uniquely name every input");
        }
        String clash = VectorAligner.inputNameClash(names);
        if (clash != null) throw new IllegalArgumentException("node " + id + ": " + clash);
        return names;
    }

    Map<String, Operator> outputs() {
        for (JsonNode sink : dag.path("sinks")) node(sink.path("from").asText());
        return Map.copyOf(built);
    }

    Map<String, Operator> sinks() {
        outputs();
        Map<String, Operator> result = new LinkedHashMap<>();
        for (JsonNode sink : dag.path("sinks")) {
            String from = sink.path("from").asText();
            Operator output = built.get(from);
            TsxCompileContext.Boundary boundary = context.federatedSinks().get(sink.path("topic").asText());
            if (boundary != null && nodes.get(from).path("op").asText().equals("stats.welford-ddsketch")) {
                Set<String> selected = new LinkedHashSet<>();
                if (boundary.statisticsWindows().isEmpty()) nodes.get(from).path("params").path("windows")
                        .forEach(w -> selected.add(Duration.parse(w.asText()).toString()));
                else boundary.statisticsWindows().forEach(w -> selected.add(Duration.parse(w).toString()));
                FilterOperator<SpecificRecord> filter = new FilterOperator<>(r -> selected.contains(((SeriesStats) r).getWindowType()), SpecificRecord.class);
                output.connectTo(0, filter, 0);
                output = filter;
            }
            result.put(DagToWayangValidation.identity(sink, layout), output);
        }
        return result;
    }

    private Operator node(String id) {
        Operator existing = built.get(id);
        if (existing != null) return existing;
        JsonNode node = nodes.get(id);
        JsonNode p = node.path("params");
        String op = node.path("op").asText();
        Operator result;
        if (op.equals("decode.avro-soe")) {
            List<Operator> inputs = new ArrayList<>();
            for (JsonNode topic : dag.path("source").path("topics")) {
                Operator source = sources.get(topic.asText());
                if (source == null) throw new IllegalArgumentException("missing topic source " + topic.asText());
                inputs.add(source);
            }
            result = union(inputs, SpecificRecord.class);
        } else if (CleanChain.OPS.contains(op)) {
            List<JsonNode> run = new ArrayList<>();
            JsonNode first = node;
            run.add(first);
            while (first.path("in").size() == 1) {
                JsonNode previous = nodes.get(first.path("in").get(0).asText());
                if (previous == null || !CleanChain.OPS.contains(previous.path("op").asText())
                        || consumers.getOrDefault(previous.path("id").asText(), 0) != 1) break;
                run.add(previous);
                first = previous;
            }
            Collections.reverse(run);
            TsxSeriesChain chain = new TsxSeriesChain(id, run, target(id), expected(first.path("in").get(0).asText()), state);
            node(first.path("in").get(0).asText()).connectTo(0, chain, 0);
            result = chain;
            for (JsonNode member : run) built.put(member.path("id").asText(), result);
        } else if (op.equals("stats.welford-ddsketch")) {
            List<Duration> windows = new ArrayList<>();
            p.path("windows").forEach(w -> windows.add(Duration.parse(w.asText())));
            StatsEngine.Config config = StatsEngine.Config.defaults().withWindows(windows.toArray(Duration[]::new))
                    .withSketchAlpha(p.path("alpha").asDouble());
            TsxStatsWindow stats = context.complete() ? TsxStatsWindow.bounded(id, config, state) : new TsxStatsWindow(id, config, state);
            node(node.path("in").get(0).asText()).connectTo(0, stats, 0);
            result = stats;
        } else if (op.equals("segment.threshold")) {
            Segmenter segmenter = Segmenter.threshold(Segmenter.Direction.valueOf(p.path("direction").asText()),
                    p.path("enter").asDouble(), p.path("exit").asDouble(), duration(p, "pre"), duration(p, "post"),
                    duration(p, "minDuration"), duration(p, "maxDuration"));
            result = unary(node, context.complete() ? TsxSegment.bounded(id, segmenter, state) : new TsxSegment(id, segmenter, state));
        } else if (op.equals("segment.extremum")) {
            Segmenter segmenter = Segmenter.extremum(Segmenter.Feature.valueOf(p.path("feature").asText()),
                    p.path("prominence").asDouble(), duration(p, "window"), duration(p, "distance"), duration(p, "pre"), duration(p, "post"));
            result = unary(node, context.complete() ? TsxSegment.bounded(id, segmenter, state) : new TsxSegment(id, segmenter, state));
        } else if (op.equals("sax.word")) {
            result = unary(node, new TsxSaxWord(p.path("w").asInt(), p.path("a").asInt(), specHash));
        } else if (op.equals("dq.result")) {
            Set<DataQuality.Check> checks = new LinkedHashSet<>();
            p.path("checks").forEach(c -> checks.add(DataQuality.Check.valueOf(c.asText())));
            DataQuality.Expectation expectation = DataQuality.Expectation.of(p.has("min") ? p.path("min").asDouble() : null,
                    p.has("max") ? p.path("max").asDouble() : null, p.has("expectedInterval") ? duration(p, "expectedInterval") : null);
            result = unary(node, new TsxDqResult(dag.path("dag").asText(), context.site(), specHash, expectation, checks, context.nowMicros()));
        } else if (op.equals("align.vector")) {
            List<String> names = vectorNames(node);
            Map<String, Set<String>> expected = new LinkedHashMap<>();
            List<Operator> tagged = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                String input = node.path("in").get(i).asText();
                expected.put(names.get(i), inventory(input));
                tagged.add(tag(node(input), names.get(i)));
            }
            String vector = id;
            for (JsonNode sink : dag.path("sinks")) {
                if (sink.path("from").asText().equals(id)) {
                    String named = layout.smartVectorName(sink.path("topic").asText());
                    if (named != null) vector = named;
                }
            }
            VectorAligner.Params params = new VectorAligner.Params(duration(p, "every"), p.path("key").asText(),
                    VectorAligner.Agg.valueOf(p.path("agg").asText()), p.path("fill").asText().equals("as-of"),
                    duration(p, "maxAge"), p.path("emit").asText().equals("complete"));
            TsxAlignVector align = new TsxAlignVector(id, vector, dag.path("dag").asText(), specHash, params,
                    expected, micros(p, "lateness"), context.complete(), state);
            union(tagged, TsxTagged.class).connectTo(0, align, 0);
            result = align;
        } else if (op.equals("pair.xcorr") || op.equals("pair.es")) {
            String a = node.path("in").get(0).asText();
            boolean within = node.path("in").size() == 1;
            String b = within ? a : node.path("in").get(1).asText();
            Map<String, Object> params = JSON.convertValue(p, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            TsxPair pair = new TsxPair(id, op, params, inventory(a), inventory(b), specHash, state, within);
            List<Operator> inputs = new ArrayList<>();
            inputs.add(tag(node(a), TsxPair.A));
            if (!within) inputs.add(tag(node(b), TsxPair.B));
            union(inputs, TsxTagged.class).connectTo(0, pair, 0);
            result = pair;
        } else {
            throw new IllegalArgumentException("node " + id + ": no Java lowering for " + op);
        }
        built.put(id, result);
        return result;
    }

    private Operator unary(JsonNode node, Operator operator) {
        node(node.path("in").get(0).asText()).connectTo(0, operator, 0);
        return operator;
    }

    private SeriesStream.Target target(String id) {
        SeriesStream.Target bound = context.cleanTargets().get(id);
        if (bound != null) {
            if (!bound.specHash().equals(specHash)) throw new IllegalArgumentException("node " + id + ": clean target specHash differs from the submitted document");
            return bound;
        }
        for (JsonNode sink : dag.path("sinks")) {
            if (!sink.path("from").asText().equals(id)) continue;
            JsonNode signal = sink.path("signal");
            String system = signal.path("system").asText();
            String signalId = signal.path("id").asText();
            if (!system.isBlank() && !signalId.isBlank()) return new SeriesStream.Target(system, signalId,
                    signal.path("grade").asText("clean"), sink.path("origin").asText(null), dag.path("dag").asText(),
                    specHash, signal.path("metric").asText(null), signal.path("grade").asText().equals("features") ? id : null,
                    sink.path("madeOf").asText(null));
        }
        throw new IllegalArgumentException("node " + id + ": bind a clean target before building the plan");
    }

    private List<String> expected(String input) {
        List<String> bound = context.seriesByNode().get(input);
        if (bound != null) return bound;
        if (nodes.get(input).path("op").asText().equals("decode.avro-soe")) {
            List<String> ids = new ArrayList<>();
            dag.path("source").path("series").forEach(s -> ids.add(s.asText()));
            return ids;
        }
        return List.of();
    }

    private Set<String> inventory(String input) {
        List<String> ids = expected(input);
        if (ids.isEmpty()) throw new IllegalArgumentException("node " + input + ": bind the output series inventory before building pair or alignment");
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    private static Duration duration(JsonNode params, String name) {
        return Duration.parse(params.path(name).asText());
    }

    private static long micros(JsonNode params, String name) {
        return duration(params, name).toNanos() / 1000;
    }

    private static Operator tag(Operator source, String name) {
        MapOperator<SpecificRecord, TsxTagged> tag = new MapOperator<>(record -> new TsxTagged(name, (Episode) record), SpecificRecord.class, TsxTagged.class);
        source.connectTo(0, tag, 0);
        return tag;
    }

    private static <T> Operator union(List<Operator> inputs, Class<T> type) {
        if (inputs.isEmpty()) throw new IllegalArgumentException("a compiled input must bind at least one topic");
        Operator joined = inputs.get(0);
        for (int i = 1; i < inputs.size(); i++) {
            UnionAllOperator<T> union = new UnionAllOperator<>(type);
            joined.connectTo(0, union, 0);
            inputs.get(i).connectTo(0, union, 1);
            joined = union;
        }
        return joined;
    }
}
