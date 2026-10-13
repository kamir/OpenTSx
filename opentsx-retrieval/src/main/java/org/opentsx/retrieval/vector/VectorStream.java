package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code align.vector} for data that keeps arriving: the measuring points of {@link VectorAligner}, handed out as
 * soon as they are final. Rules: docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5b.
 *
 * <ul>
 *   <li><b>Series arrive in time order.</b> Per series, a block of samples must begin at or after the end of the
 *   previous one. A block that does not is skipped and counted: a duplicate or data out of order.</li>
 *   <li><b>A tick is final</b> when every expected series has progressed to its end plus the allowed lateness. Until
 *   every expected series has been seen once, nothing is handed out: only then is it known which series form a
 *   vector and what the components are called.</li>
 *   <li><b>Every input reads the same series.</b> That is what the runtime executes (see {@link VectorJob}).</li>
 * </ul>
 *
 * <p>Fed with all data and {@link #flush()}ed, the stream gives what {@link VectorAligner#align} gives. The state
 * ({@link #state()}, {@link #restore}) is all that has to survive a restart. Not thread-safe.
 */
public final class VectorStream {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Running aggregate of the samples of one series in one tick. */
    private static final class Cell {
        long n;
        double sum;
        double min;
        double max;
        double first;
        double last;

        double value(VectorAligner.Agg agg) {
            return switch (agg) {
                case LAST -> last;
                case FIRST -> first;
                case MIN -> min;
                case MAX -> max;
                case SUM -> sum;
                case COUNT -> n;
                case MEAN -> sum / n;
            };
        }
    }

    private static final class SeriesState {
        String metric;
        Map<String, String> tags = new TreeMap<>();
        long progress = Long.MIN_VALUE;                 // exclusive end of the data seen
        final TreeMap<Long, Cell> cells = new TreeMap<>();
        // as-of: the last tick this series had a value in, and the value
        long lastTick = Long.MIN_VALUE / 2;
        Double lastValue;
    }

    /** One key of the vector once the layout is known: its series by component, and how far it was handed out. */
    private static final class KeyState {
        final Map<String, String> key;
        final Map<String, String> seriesByComponent = new LinkedHashMap<>();
        long nextTick = Long.MIN_VALUE;                 // first tick not handed out yet; MIN_VALUE: none so far

        KeyState(Map<String, String> key) {
            this.key = key;
        }
    }

    private final List<String> inputNames;
    private final VectorAligner.Params params;
    private final long every;
    private final long lateness;
    private final long maxAgeTicks;
    private final Set<String> expected;
    private final Map<String, SeriesState> series = new TreeMap<>();
    // set once every expected series has been seen
    private List<String> components;
    private Map<String, KeyState> keys;
    private long skippedBlocks;

    /**
     * @param inputNames one name per input of the node
     * @param expected   ids of the series the vector is made of; none is handed out before all were seen
     * @param latenessMicros how long a tick stays open after every series has passed its end
     */
    public VectorStream(List<String> inputNames, VectorAligner.Params params, Set<String> expected, long latenessMicros) {
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("a vector that keeps running needs the ids of its series (source.series)");
        }
        this.inputNames = List.copyOf(inputNames);
        this.params = params;
        this.every = Math.floorDiv(params.every().toNanos(), 1000);
        this.lateness = Math.max(0, latenessMicros);
        this.maxAgeTicks = Math.floorDiv(Math.floorDiv(params.maxAge() == null ? params.every().toNanos() : params.maxAge().toNanos(), 1000), every);
        this.expected = new TreeSet<>(expected);
    }

    /**
     * Takes a block of samples of one series, in time order.
     *
     * @param endMicros exclusive end of the block: the series has no further sample before it
     * @return the measuring points that became final
     */
    public List<VectorAligner.Point> add(String seriesId, String metric, Map<String, String> tags, long[] timesMicros,
                                         double[] values, long endMicros) {
        if (!expected.contains(seriesId)) {
            return List.of();
        }
        SeriesState s = series.computeIfAbsent(seriesId, id -> new SeriesState());
        if (s.metric == null) {
            s.metric = metric;
            s.tags.putAll(tags);
        }
        if (timesMicros.length > 0 && timesMicros[0] < s.progress) {
            skippedBlocks++;        // a duplicate, or older than what the series already delivered
            return List.of();
        }
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                continue;
            }
            long tick = Math.floorDiv(timesMicros[i], every);
            Cell cell = s.cells.get(tick);
            if (cell == null) {
                cell = new Cell();
                cell.n = 1;
                cell.sum = cell.min = cell.max = cell.first = cell.last = v;
                s.cells.put(tick, cell);
            } else {
                cell.n++;
                cell.sum += v;
                cell.min = Math.min(cell.min, v);
                cell.max = Math.max(cell.max, v);
                cell.last = v;
            }
        }
        s.progress = Math.max(s.progress, endMicros);
        return emit(false);
    }

    /** Hands out everything that is left, as at the end of the data. */
    public List<VectorAligner.Point> flush() {
        return emit(true);
    }

    /** The series that were not seen yet; while there are any, nothing is handed out. */
    public List<String> waitingFor() {
        return expected.stream().filter(id -> !series.containsKey(id)).toList();
    }

    /** Every expected series has data up to here (exclusive); {@code Long.MIN_VALUE} while one is missing. */
    public long watermark() {
        if (!waitingFor().isEmpty()) {
            return Long.MIN_VALUE;
        }
        return series.values().stream().mapToLong(s -> s.progress).min().orElse(Long.MIN_VALUE);
    }

    /** Blocks that were skipped because they did not continue their series. */
    public long skippedBlocks() {
        return skippedBlocks;
    }

    public List<String> components() {
        return components == null ? List.of() : components;
    }

    private void layout() {
        String keyBy = params.key().trim();
        boolean bySeries = keyBy.equals("series");
        boolean all = keyBy.equals("all");
        List<VectorAligner.Series> list = new ArrayList<>();
        series.forEach((id, s) -> list.add(new VectorAligner.Series(id, s.metric, s.tags, new long[0], new double[0])));
        if (!bySeries && !all && list.stream().noneMatch(s -> s.tags().containsKey(keyBy))) {
            throw new IllegalArgumentException("no series carries the tag \"" + keyBy + "\"; use series, all or a tag of the series");
        }
        Map<String, Integer> perKey = new LinkedHashMap<>();
        for (VectorAligner.Series s : list) {
            perKey.merge(VectorAligner.keyText(s, bySeries, all, keyBy), 1, Integer::sum);
        }
        boolean several = perKey.values().stream().anyMatch(n -> n > 1);
        List<String> comps = new ArrayList<>();
        Map<String, KeyState> ks = new TreeMap<>();
        for (String input : inputNames) {
            TreeSet<String> ofInput = new TreeSet<>();
            for (VectorAligner.Series s : list) {
                String component = !several ? input : inputNames.size() == 1 ? VectorAligner.seriesName(s)
                        : input + "." + VectorAligner.seriesName(s);
                ofInput.add(component);
                KeyState k = ks.computeIfAbsent(VectorAligner.keyText(s, bySeries, all, keyBy),
                        t -> new KeyState(VectorAligner.keyOf(s, bySeries, all, keyBy)));
                String other = k.seriesByComponent.put(component, s.id());
                if (other != null && !other.equals(s.id())) {
                    throw new IllegalArgumentException("the series " + other + " and " + s.id() + " both give the component \""
                            + component + "\" of one vector; choose a key that tells them apart");
                }
            }
            ofInput.stream().filter(c -> !comps.contains(c)).forEach(comps::add);
        }
        components = comps;
        keys = ks;
    }

    private List<VectorAligner.Point> emit(boolean everything) {
        if (!waitingFor().isEmpty()) {
            return List.of();
        }
        if (keys == null) {
            layout();
        }
        long limit = everything ? Long.MAX_VALUE : watermark() - lateness;
        List<VectorAligner.Point> out = new ArrayList<>();
        for (KeyState k : keys.values()) {
            // the range of ticks with data of this key that is final: [from, to]
            long lo = Long.MAX_VALUE;
            long hi = Long.MIN_VALUE;
            for (String id : k.seriesByComponent.values()) {
                TreeMap<Long, Cell> cells = series.get(id).cells;
                if (!cells.isEmpty()) {
                    lo = Math.min(lo, cells.firstKey());
                    hi = Math.max(hi, cells.lastKey());
                }
            }
            if (hi < lo) {
                continue;
            }
            long from = k.nextTick == Long.MIN_VALUE ? lo : k.nextTick;
            long to = everything ? hi : Math.min(hi, Math.floorDiv(limit, every) - 1);
            for (long tick = from; tick <= to; tick++) {
                List<Double> values = new ArrayList<>(components.size());
                int present = 0;
                for (String component : components) {
                    String id = k.seriesByComponent.get(component);
                    Double v = null;
                    if (id != null) {
                        SeriesState s = series.get(id);
                        Cell cell = s.cells.get(tick);
                        if (cell != null) {
                            v = cell.value(params.agg());
                        } else if (params.asOf() && s.lastValue != null && lastTickBefore(s, tick) >= tick - maxAgeTicks) {
                            v = lastBefore(s, tick);
                        }
                    }
                    values.add(v);
                    if (v != null) {
                        present++;
                    }
                }
                if (params.complete() ? present == components.size() : present > 0) {
                    out.add(new VectorAligner.Point(k.key, tick * every, (tick + 1) * every, List.copyOf(components),
                            java.util.Collections.unmodifiableList(values)));
                }
                // this tick is done for the key: remember the values for as-of, then let the cells go
                for (String id : new TreeSet<>(k.seriesByComponent.values())) {
                    SeriesState s = series.get(id);
                    Cell cell = s.cells.remove(tick);
                    if (cell != null) {
                        s.lastTick = tick;
                        s.lastValue = cell.value(params.agg());
                    }
                }
            }
            if (to >= from) {
                k.nextTick = to + 1;
            }
        }
        return out;
    }

    private static long lastTickBefore(SeriesState s, long tick) {
        return s.lastTick < tick ? s.lastTick : Long.MIN_VALUE / 2;
    }

    private static Double lastBefore(SeriesState s, long tick) {
        return s.lastTick < tick ? s.lastValue : null;
    }

    // ---- state -----------------------------------------------------------------------------------------------------

    /** Everything a restart needs: progress and open ticks per series, and how far each key was handed out. */
    public ObjectNode state() {
        ObjectNode n = JSON.createObjectNode();
        n.put("skippedBlocks", skippedBlocks);
        ObjectNode ss = n.putObject("series");
        series.forEach((id, s) -> {
            ObjectNode o = ss.putObject(id);
            o.put("metric", s.metric);
            ObjectNode tags = o.putObject("tags");
            s.tags.forEach(tags::put);
            o.put("progress", s.progress);
            if (s.lastValue != null) {
                o.put("lastTick", s.lastTick);
                o.put("lastValue", s.lastValue);
            }
            ObjectNode cells = o.putObject("cells");
            s.cells.forEach((tick, c) -> {
                ArrayNode a = cells.putArray(Long.toString(tick));
                a.add(c.n).add(c.sum).add(c.min).add(c.max).add(c.first).add(c.last);
            });
        });
        if (keys != null) {
            ObjectNode ks = n.putObject("next");
            keys.forEach((text, k) -> {
                if (k.nextTick != Long.MIN_VALUE) {
                    ks.put(text, k.nextTick);
                }
            });
        }
        return n;
    }

    /** A stream that continues where {@link #state()} was taken. The parameters must be those of then. */
    public static VectorStream restore(List<String> inputNames, VectorAligner.Params params, Set<String> expected,
                                       long latenessMicros, JsonNode state) {
        VectorStream v = new VectorStream(inputNames, params, expected, latenessMicros);
        v.skippedBlocks = state.path("skippedBlocks").asLong();
        state.path("series").fields().forEachRemaining(e -> {
            SeriesState s = new SeriesState();
            s.metric = e.getValue().path("metric").asText();
            e.getValue().path("tags").fields().forEachRemaining(t -> s.tags.put(t.getKey(), t.getValue().asText()));
            s.progress = e.getValue().path("progress").asLong();
            if (e.getValue().has("lastValue")) {
                s.lastTick = e.getValue().path("lastTick").asLong();
                s.lastValue = e.getValue().path("lastValue").asDouble();
            }
            e.getValue().path("cells").fields().forEachRemaining(c -> {
                Cell cell = new Cell();
                JsonNode a = c.getValue();
                cell.n = a.get(0).asLong();
                cell.sum = a.get(1).asDouble();
                cell.min = a.get(2).asDouble();
                cell.max = a.get(3).asDouble();
                cell.first = a.get(4).asDouble();
                cell.last = a.get(5).asDouble();
                s.cells.put(Long.parseLong(c.getKey()), cell);
            });
            v.series.put(e.getKey(), s);
        });
        if (state.has("next") && v.waitingFor().isEmpty()) {
            v.layout();
            state.path("next").fields().forEachRemaining(e -> {
                KeyState k = v.keys.get(e.getKey());
                if (k != null) {
                    k.nextTick = e.getValue().asLong();
                }
            });
        }
        return v;
    }
}
