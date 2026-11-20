package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.wayang.ops.TsxPairWindows;
import org.opentsx.wayang.ops.TsxPairXcorr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Java execution of {@link TsxPair}. */
public final class JavaTsxPair extends TsxPair implements JavaExecutionOperator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public JavaTsxPair(TsxPair that) {
        super(that);
    }

    static TsxPairWindows.Method method(String id, Map<String, Object> params) {
        if (id.equals("pair.xcorr")) {
            return TsxPairWindows.xcorr(params);
        }
        if (id.equals("pair.es")) {
            return TsxPairWindows.es(params);
        }
        throw new IllegalArgumentException("unknown pair method " + id);
    }

    /** Episodes waiting for their counterparts, and how far each series has come, by input and series. */
    private static final class Held {
        final Map<String, Map<String, List<TsxPairWindows.Piece>>> pieces = new TreeMap<>();
        final Map<String, Map<String, Double>> progress = new TreeMap<>();

        List<TsxPairWindows.Piece> of(String input, String series) {
            return pieces.computeIfAbsent(input, k -> new TreeMap<>()).computeIfAbsent(series, k -> new ArrayList<>());
        }

        double progress(String input, String series) {
            return progress.getOrDefault(input, Map.of()).getOrDefault(series, Double.NEGATIVE_INFINITY);
        }
    }

    static List<SpecificRecord> run(TsxPair op, List<TsxTagged> in) {
        TsxPairWindows.Method method = method(op.method(), op.params());
        TsxPairXcorr.PairSettings st = TsxPairXcorr.pairSettings(op.params());
        Held held = restore(op.state().get(op.node()));
        List<SpecificRecord> out = new ArrayList<>();
        Map<String, Integer> order = new TreeMap<>();
        for (String series : op.aSeries()) order.put(series, order.size());
        for (TsxTagged t : in) {
            String input = t.input();
            if (op.within() && !input.equals(A)) throw new IllegalArgumentException("within-input pairing reads input a only");
            Set<String> own = input.equals(A) ? op.aSeries() : input.equals(B) ? op.bSeries() : Set.of();
            Episode e = t.episode();
            String series = e.getSeries().getSeriesId();
            if (!own.contains(series)) {
                continue;
            }
            TsxPairWindows.Piece p = piece(e);
            if (p.start() < held.progress(input, series) - TsxPairWindows.TOL) {
                continue;       // a duplicate, or older than what the series already delivered
            }
            String other = op.within() ? A : input.equals(A) ? B : A;
            for (String o : other.equals(A) ? op.aSeries() : op.bSeries()) {
                if (op.within() && series.equals(o)) continue;
                for (TsxPairWindows.Piece q : held.of(other, o)) {
                    boolean first = op.within() ? order.get(series) < order.get(o) : input.equals(A);
                    TsxPairWindows.Piece a = first ? p : q;
                    TsxPairWindows.Piece b = first ? q : p;
                    TsxPairWindows.Range r = TsxPairWindows.pair(a, b, st.pairing(), st.window());
                    if (r != null) {
                        TsxPairWindows.Result res = TsxPairWindows.run(method, st, r);
                        if (res != null) {
                            out.addAll(episodes(op, method, st, res));
                        }
                    }
                }
            }
            held.of(input, series).add(p);
            held.progress.computeIfAbsent(input, k -> new TreeMap<>()).merge(series, p.end(), Math::max);
        }
        prune(op, held);
        op.state().put(op.node(), save(held));
        return out;
    }

    /** Drops every episode that all series of the other input have passed: nothing can pair with it any more. */
    private static void prune(TsxPair op, Held held) {
        if (op.within()) {
            for (String series : op.aSeries()) {
                double watermark = op.aSeries().stream().filter(s -> !s.equals(series))
                        .mapToDouble(s -> held.progress(A, s)).min().orElse(Double.NEGATIVE_INFINITY);
                held.of(A, series).removeIf(p -> p.end() <= watermark + TsxPairWindows.TOL);
            }
            return;
        }
        for (String input : List.of(A, B)) {
            Set<String> others = input.equals(A) ? op.bSeries() : op.aSeries();
            for (List<TsxPairWindows.Piece> list : held.pieces.getOrDefault(input, Map.of()).values()) {
                list.removeIf(p -> others.stream().allMatch(o -> held.progress(input.equals(A) ? B : A, o) >= p.end() - TsxPairWindows.TOL));
            }
        }
    }

    private static TsxPairWindows.Piece piece(Episode e) {
        long[] t = Episodes.timestampsMicros(e);
        double[] x = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            x[i] = t[i] / 1e6;
        }
        return new TsxPairWindows.Piece(e.getSeries().getSeriesId(), e.getEpisodeId(), Micros.of(e.getTStart()) / 1e6,
                Micros.of(e.getTEnd()) / 1e6, x, Episodes.values(e));
    }

    private static long micros(double seconds) {
        return Math.round(seconds * 1e6);
    }

    /** One result episode per field of a pair range (pairs.ts:351-375). */
    static List<Episode> episodes(TsxPair op, TsxPairWindows.Method method, TsxPairXcorr.PairSettings st,
                                  TsxPairWindows.Result res) {
        String window = TsxPairWindows.durationTag(st.window());
        String step = st.window() == null ? "episode" : TsxPairWindows.durationTag(st.step());
        TsxPairWindows.Range r = res.range();
        List<Episode> out = new ArrayList<>();
        for (int f = 0; f < method.fields().size(); f++) {
            Map<String, String> tags = Map.of("a", r.a().seriesId(), "b", r.b().seriesId(), "window", window, "step", step);
            SeriesKey key = SeriesKeys.of(method.id() + "." + method.fields().get(f), tags);
            double c0 = res.centres()[0];
            Episodes.Draft d;
            if (st.window() == null) {
                d = Episodes.irregular(key, new long[]{micros(c0)}, res.values()[f]).tEnd(micros(c0) + 1);
            } else {
                d = Episodes.regular(key, micros(c0), micros(st.step()), res.values()[f]);
            }
            String identity = key.getSeriesId() + "|" + r.a().episodeId() + "|" + r.b().episodeId() + "|" + op.specHash();
            d.episodeId("p" + XxHash64.hex(XxHash64.hash(identity.getBytes(StandardCharsets.UTF_8))))
                    .source("opentsx-wayang").parentEpisodeId(r.a().episodeId()).quality(res.quality().clone())
                    .label("pair.a", r.a().episodeId()).label("pair.b", r.b().episodeId())
                    .label("pair.method", method.id()).label("pair.window", window).label("pair.step", step)
                    .label("specHash", op.specHash());
            method.settings().forEach((k, v) -> d.label("pair." + k, v));
            out.add(d.build());
        }
        return out;
    }

    private static JsonNode save(Held held) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode pieces = root.putArray("pieces");
        held.pieces.forEach((input, bySeries) -> bySeries.forEach((series, list) -> list.forEach(p -> {
            ObjectNode n = pieces.addObject();
            n.put("input", input);
            n.put("seriesId", p.seriesId());
            n.put("episodeId", p.episodeId());
            n.put("start", p.start());
            n.put("end", p.end());
            ArrayNode x = n.putArray("x");
            ArrayNode y = n.putArray("y");
            for (int i = 0; i < p.x().length; i++) {
                x.add(p.x()[i]);
                if (Double.isFinite(p.y()[i])) {
                    y.add(p.y()[i]);
                } else {
                    y.addNull();
                }
            }
        })));
        ObjectNode progress = root.putObject("progress");
        held.progress.forEach((input, bySeries) -> {
            ObjectNode n = progress.putObject(input);
            bySeries.forEach(n::put);
        });
        return root;
    }

    private static Held restore(JsonNode saved) {
        Held held = new Held();
        if (saved == null) {
            return held;
        }
        for (JsonNode n : saved.path("pieces")) {
            double[] x = new double[n.path("x").size()];
            double[] y = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                x[i] = n.path("x").get(i).asDouble();
                JsonNode v = n.path("y").get(i);
                y[i] = v.isNumber() ? v.asDouble() : Double.NaN;
            }
            held.of(n.path("input").asText(), n.path("seriesId").asText()).add(new TsxPairWindows.Piece(
                    n.path("seriesId").asText(), n.path("episodeId").asText(), n.path("start").asDouble(),
                    n.path("end").asDouble(), x, y));
        }
        saved.path("progress").fields().forEachRemaining(i -> i.getValue().fields().forEachRemaining(s ->
                held.progress.computeIfAbsent(i.getKey(), k -> new TreeMap<>()).put(s.getKey(), s.getValue().asDouble())));
        return held;
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        List<TsxTagged> in = ((JavaChannelInstance) inputs[0]).<TsxTagged>provideStream().toList();
        ((CollectionChannel.Instance) outputs[0]).accept(run(this, in));
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.pair.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxPair(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR);
    }
}
