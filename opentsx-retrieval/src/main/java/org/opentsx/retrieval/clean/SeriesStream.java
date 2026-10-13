package org.opentsx.retrieval.clean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The clean grade of a signal for data that keeps arriving: every raw series of the signal runs through a
 * {@link CleanChain} and comes out as episodes of a new series. Rules: docs/datamodel-2026/14-ENGINEERING-FLOW.md,
 * section 6.
 *
 * <ul>
 *   <li><b>The clean series</b> has the metric of the raw series and its tags, plus {@code system} and
 *   {@code signal}. Its unit is that of the raw series unless the chain converts it.</li>
 *   <li><b>Series arrive in time order.</b> A block that begins before the end of the previous block of its series
 *   is skipped and counted: a duplicate, or data out of order.</li>
 *   <li><b>What is handed out is final.</b> With a resample step that is every window the data has passed; the open
 *   window stays in the state ({@link #state()}, {@link #restore}), which is all that has to survive a restart.</li>
 * </ul>
 *
 * Not thread-safe.
 */
public final class SeriesStream {

    /**
     * What every written episode says about where it belongs and comes from.
     *
     * @param metric  metric of the written series; null to keep that of the input (a sensor channel stays what it is)
     * @param feature name of the feature when the grade is features, else null
     */
    public record Target(String system, String signal, String grade, String origin, String flow, String specHash,
                         String metric, String feature, String madeOf) {

        public Target(String system, String signal, String grade, String origin, String flow, String specHash) {
            this(system, signal, grade, origin, flow, specHash, null, null, null);
        }
    }

    private static final class Raw {
        long end = Long.MIN_VALUE;
        CleanChain.State chain;
        long written;
    }

    private final CleanChain chain;
    private final Target target;
    private final Set<String> expected;
    private final Map<String, Raw> series = new TreeMap<>();
    private long skippedBlocks;

    /** @param expected ids of the raw series to read; empty for every series handed in */
    public SeriesStream(CleanChain chain, Target target, Collection<String> expected) {
        this.chain = chain;
        this.target = target;
        this.expected = new LinkedHashSet<>(expected);
    }

    /** Takes a raw episode; returns the clean episodes that are final now (none for a series this stream does not read). */
    public List<Episode> add(Episode e) {
        String id = e.getSeries().getSeriesId().toString();
        if (!expected.isEmpty() && !expected.contains(id)) {
            return List.of();
        }
        long[] times = Episodes.timestampsMicros(e);
        if (times.length == 0) {
            return List.of();
        }
        Raw raw = series.computeIfAbsent(id, k -> {
            Raw r = new Raw();
            r.chain = chain.newState();
            return r;
        });
        if (times[0] < raw.end) {
            skippedBlocks++;
            return List.of();
        }
        long tEnd = Micros.of(e.getTEnd());
        raw.end = tEnd;
        CleanChain.Block out = chain.apply(raw.chain, new CleanChain.Block(times, Episodes.values(e)), tEnd);
        if (out.size() == 0) {
            return List.of();
        }
        raw.written += out.size();
        SeriesKey key = key(e.getSeries());
        long every = chain.outputIntervalMicros();
        List<Episode> episodes = new ArrayList<>();
        if (every == 0) {
            // the chain keeps the time axis: one clean episode per raw episode, with its end
            Episodes.Draft d = e.getTimeEncoding() == TimeEncoding.REGULAR
                    ? Episodes.regular(key, out.times()[0], e.getIntervalMicros(), out.values())
                    : Episodes.irregular(key, out.times(), out.values());
            episodes.add(label(d.tEnd(tEnd), key, out.times()[0], tEnd).build());
            return episodes;
        }
        // windows on a grid: one episode per run without a break
        int from = 0;
        for (int k = 1; k <= out.size(); k++) {
            if (k == out.size() || out.times()[k] - out.times()[k - 1] != every) {
                double[] v = java.util.Arrays.copyOfRange(out.values(), from, k);
                episodes.add(label(Episodes.regular(key, out.times()[from], every, v), key, out.times()[from],
                        out.times()[k - 1] + every).build());
                from = k;
            }
        }
        return episodes;
    }

    private SeriesKey key(SeriesKey raw) {
        Map<String, String> tags = new TreeMap<>();
        if (target.metric() == null) {
            // the same quantity as the input: its tags say which sensor it is
            raw.getTags().forEach((k, v) -> tags.put(k.toString(), v.toString()));
        }
        tags.put("system", target.system());
        tags.put("signal", target.signal());
        tags.put("grade", target.grade());
        if (target.feature() != null) {
            tags.put("feature", target.feature());
        } else {
            tags.remove("feature");
        }
        String unit = chain.outputUnit() != null ? chain.outputUnit() : raw.getUnit() == null ? null : raw.getUnit().toString();
        return SeriesKeys.of(target.metric() != null ? target.metric() : raw.getMetric().toString(), tags, unit);
    }

    /**
     * Labels the episode and gives it its identity: the same piece of the same clean series, computed by the same
     * document, has the same id. A reader that sees it twice (a restart writes again what lay after the last
     * checkpoint) keeps one.
     */
    private Episodes.Draft label(Episodes.Draft d, SeriesKey key, long from, long to) {
        String identity = key.getSeriesId() + "|" + from + "|" + to + "|" + target.specHash();
        d.episodeId("c" + XxHash64.hex(XxHash64.hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        d.source("opentsx-processor").pipeline(target.flow()).label("grade", target.grade()).label("flow", target.flow())
                .label("specHash", target.specHash());
        if (target.origin() != null && !target.origin().isBlank()) {
            d.label("origin", target.origin());
        }
        // where the series comes from is a path, not a rank: the label names the step before this one
        if (target.madeOf() != null && !target.madeOf().isBlank()) {
            d.label("madeOf", target.madeOf());
        }
        return d;
    }

    /** Ids of the expected series no episode has arrived for yet. */
    public List<String> waitingFor() {
        return expected.stream().filter(id -> !series.containsKey(id)).toList();
    }

    /** How far every series seen so far has progressed; {@code Long.MIN_VALUE} before the first episode. */
    public long watermark() {
        return series.values().stream().mapToLong(r -> r.end).min().orElse(Long.MIN_VALUE);
    }

    public long skippedBlocks() {
        return skippedBlocks;
    }

    public ObjectNode state() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("skippedBlocks", skippedBlocks);
        ObjectNode s = o.putObject("series");
        series.forEach((id, r) -> {
            ObjectNode n = s.putObject(id);
            n.put("end", r.end).put("written", r.written);
            n.set("chain", r.chain.json());
        });
        return o;
    }

    public static SeriesStream restore(CleanChain chain, Target target, Collection<String> expected, JsonNode state) {
        SeriesStream st = new SeriesStream(chain, target, expected);
        st.skippedBlocks = state.path("skippedBlocks").asLong();
        state.path("series").fields().forEachRemaining(e -> {
            Raw r = new Raw();
            r.end = e.getValue().path("end").asLong(Long.MIN_VALUE);
            r.written = e.getValue().path("written").asLong();
            r.chain = chain.restore(e.getValue().path("chain"));
            st.series.put(e.getKey(), r);
        });
        return st;
    }
}
