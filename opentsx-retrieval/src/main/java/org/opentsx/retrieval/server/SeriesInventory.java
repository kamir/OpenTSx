package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The series inventory behind {@code GET /v1/inventory}: every series of the store with its extent (episodes,
 * samples, time range, value range), the labels its episodes carry (scenario, seed, catalog, IEC reference) and the
 * ground-truth marks of the scenario ({@code gt.operation}, {@code gt.sensor_fault}) as time ranges.
 *
 * <p>The inventory is derived by reading every episode of the store, so it is cached: for {@code ttl}, or until
 * {@link #invalidate()} is called. On a large Iceberg table the first request after the cache expired is slow.
 */
public final class SeriesInventory {

    public static final String SCHEMA = "opentsx.ts-inventory.v1";

    /** Labels with more distinct values than this are cut; {@code labelsTruncated} says so. */
    static final int MAX_LABEL_VALUES = 8;

    /**
     * Where the episodes come from.
     *
     * @param kind   {@code kafscale}, {@code iceberg} or {@code demo}
     * @param domain topic domain, or null when the source has none
     * @param topics topic names by role (episodes, observations, seriesStats, ...), empty when the source is no log
     */
    public record Source(String kind, String domain, Map<String, String> topics) {
        public static Source of(String kind) {
            return new Source(kind, null, Map.of());
        }
    }

    private final SeriesStore store;
    private final Source source;
    private final JsonNode scenario;
    private final JsonNode catalog;
    private final long ttlNanos;

    private BooleanSupplier truncated = () -> false;
    private Consumer<ObjectNode> sourceDetails = src -> { };

    private JsonNode cached;
    private long cachedAt;

    /**
     * @param scenario parsed scenario file (for example {@code opentsx-windpark/scenarios/ostsee-demo.yaml}) or null
     * @param catalog  parsed channel catalog ({@code channels: [{metric, description, unit, ...}]}) or null
     */
    public SeriesInventory(SeriesStore store, Source source, JsonNode scenario, JsonNode catalog, Duration ttl) {
        this.store = store;
        this.source = source;
        this.scenario = scenario;
        this.catalog = catalog;
        this.ttlNanos = ttl.toNanos();
    }

    /** The source reports through this that the store holds only a part of what the source has. */
    public synchronized void truncatedWhen(BooleanSupplier truncated) {
        this.truncated = truncated;
    }

    /** Lets the source add what it knows to the {@code source} object: vector topics, whether it runs DAGs. */
    public synchronized void sourceDetails(Consumer<ObjectNode> details) {
        this.sourceDetails = details;
    }

    /** Drops the cached inventory; the next {@link #get()} reads the store again. */
    public synchronized void invalidate() {
        cached = null;
    }

    public synchronized JsonNode get() {
        long now = System.nanoTime();
        if (cached == null || now - cachedAt > ttlNanos) {
            cached = build();
            cachedAt = now;
        }
        return cached;
    }

    private JsonNode build() {
        Map<String, Entry> bySeries = new LinkedHashMap<>();
        for (SeriesKey k : store.listSeries(SeriesSelector.all())) {
            bySeries.put(k.getSeriesId(), new Entry(k));
        }
        for (Episode e : store.read(EpisodeQuery.of(SeriesSelector.all(), Long.MIN_VALUE, Long.MAX_VALUE))) {
            bySeries.computeIfAbsent(e.getSeries().getSeriesId(), id -> new Entry(e.getSeries())).add(e);
        }
        List<Entry> entries = new ArrayList<>(bySeries.values());
        entries.sort(Comparator.comparing((Entry en) -> en.key.getMetric()).thenComparing(en -> en.tags.toString()));

        ObjectNode root = EpisodeJson.JSON.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("generatedAt", Micros.toInstant(Micros.now()).toString());
        ObjectNode src = root.putObject("source");
        src.put("kind", source.kind());
        if (source.domain() != null) {
            src.put("domain", source.domain());
        }
        ObjectNode topics = src.putObject("topics");
        source.topics().forEach(topics::put);
        if (truncated.getAsBoolean()) {
            src.put("truncated", true);
        }
        sourceDetails.accept(src);

        long episodes = 0;
        long samples = 0;
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        TreeSet<String> scenarios = new TreeSet<>();
        ArrayNode series = EpisodeJson.JSON.createArrayNode();
        for (Entry en : entries) {
            episodes += en.episodes;
            samples += en.samples;
            from = Math.min(from, en.from);
            to = Math.max(to, en.to);
            scenarios.addAll(en.labels.getOrDefault("scenario", new TreeSet<>()));
            series.add(en.json());
        }
        ObjectNode totals = root.putObject("totals");
        totals.put("series", entries.size());
        totals.put("episodes", episodes);
        totals.put("samples", samples);
        if (episodes > 0) {
            totals.put("from", Micros.toInstant(from).toString());
            totals.put("to", Micros.toInstant(to).toString());
        }
        ArrayNode sc = totals.putArray("scenarios");
        scenarios.forEach(sc::add);

        if (scenario != null) {
            root.set("scenario", scenario);
        }
        root.set("channels", catalog != null && catalog.path("channels").isArray()
                ? catalog.path("channels") : EpisodeJson.JSON.createArrayNode());
        root.set("series", series);
        return root;
    }

    /** One ground-truth label value over a contiguous run of episodes. */
    private record Mark(String kind, String type, long from, long to) {
    }

    private static final class Entry {
        final SeriesKey key;
        final Map<String, String> tags = new TreeMap<>();
        final Map<String, TreeSet<String>> labels = new TreeMap<>();
        final List<Episode> truth = new ArrayList<>();
        long episodes;
        long segments;
        long samples;
        long valid;
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum;
        long interval = -1;       // -1 not seen yet, 0 mixed or irregular
        boolean labelsTruncated;

        Entry(SeriesKey key) {
            this.key = key;
            key.getTags().forEach((k, v) -> tags.put(k.toString(), v.toString()));
        }

        void add(Episode e) {
            if (e.getProvenance().getParentEpisodeId() != null
                    || (e.getSegmentation() != null && e.getSegmentation().getSourceEpisodeId() != null)) {
                segments++;      // cut out of another episode: its samples are counted there
                return;
            }
            episodes++;
            samples += e.getCount();
            from = Math.min(from, Micros.of(e.getTStart()));
            to = Math.max(to, Micros.of(e.getTEnd()));
            long n = e.getSummary().getValidCount();
            if (n > 0) {
                valid += n;
                min = Math.min(min, e.getSummary().getMin());
                max = Math.max(max, e.getSummary().getMax());
                sum += e.getSummary().getMean() * n;
            }
            long iv = e.getIntervalMicros() == null ? 0 : e.getIntervalMicros();
            interval = interval == -1 || interval == iv ? iv : 0;
            boolean marked = false;
            for (var l : e.getLabels().entrySet()) {
                String k = l.getKey().toString();
                if (k.startsWith("gt.")) {
                    marked = true;
                    continue;
                }
                TreeSet<String> values = labels.computeIfAbsent(k, x -> new TreeSet<>());
                if (values.size() < MAX_LABEL_VALUES) {
                    values.add(l.getValue().toString());
                } else if (!values.contains(l.getValue().toString())) {
                    labelsTruncated = true;
                }
            }
            if (marked) {
                truth.add(e);
            }
        }

        /** Ground-truth labels as time ranges: adjacent episodes with the same value are joined. */
        List<Mark> marks() {
            truth.sort(Comparator.comparing(Episode::getTStart));
            Map<String, Mark> open = new LinkedHashMap<>();
            List<Mark> out = new ArrayList<>();
            for (Episode e : truth) {
                long start = Micros.of(e.getTStart());
                long end = Micros.of(e.getTEnd());
                long gap = 2 * Math.max(1_000_000L, e.getIntervalMicros() == null ? 0 : e.getIntervalMicros());
                for (var l : e.getLabels().entrySet()) {
                    String k = l.getKey().toString();
                    if (!k.startsWith("gt.")) {
                        continue;
                    }
                    for (String type : l.getValue().toString().split(",")) {
                        String id = k + "|" + type;
                        Mark m = open.get(id);
                        if (m != null && start <= m.to() + gap) {
                            open.put(id, new Mark(m.kind(), m.type(), m.from(), Math.max(m.to(), end)));
                        } else {
                            if (m != null) {
                                out.add(m);
                            }
                            open.put(id, new Mark(k.substring(3), type, start, end));
                        }
                    }
                }
            }
            out.addAll(open.values());
            out.sort(Comparator.comparingLong(Mark::from).thenComparing(Mark::kind).thenComparing(Mark::type));
            return out;
        }

        ObjectNode json() {
            ObjectNode n = EpisodeJson.JSON.createObjectNode();
            n.put("seriesId", key.getSeriesId());
            n.put("metric", key.getMetric());
            ObjectNode t = n.putObject("tags");
            tags.forEach(t::put);
            if (key.getUnit() != null) {
                n.put("unit", key.getUnit());
            }
            n.put("episodes", episodes);
            if (segments > 0) {
                n.put("segments", segments);
            }
            n.put("samples", samples);
            if (episodes > 0) {
                n.put("from", Micros.toInstant(from).toString());
                n.put("to", Micros.toInstant(to).toString());
            }
            if (interval > 0) {
                n.put("intervalMicros", interval);
            }
            if (valid > 0) {
                n.put("min", min);
                n.put("max", max);
                n.put("mean", sum / valid);
            }
            n.put("missing", samples - valid);
            ObjectNode ls = n.putObject("labels");
            labels.forEach((k, values) -> {
                ArrayNode a = ls.putArray(k);
                values.forEach(a::add);
            });
            if (labelsTruncated) {
                n.put("labelsTruncated", true);
            }
            ArrayNode ms = n.putArray("marks");
            for (Mark m : marks()) {
                ObjectNode o = ms.addObject();
                o.put("kind", m.kind());
                o.put("type", m.type());
                o.put("from", Micros.toInstant(m.from()).toString());
                o.put("to", Micros.toInstant(m.to()).toString());
            }
            return n;
        }
    }
}
