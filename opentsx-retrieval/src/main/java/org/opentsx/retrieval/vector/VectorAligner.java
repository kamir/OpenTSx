package org.opentsx.retrieval.vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The operator {@code align.vector}: the series of several inputs on one clock, one measuring point per tick and key
 * with one value per component. Rules: docs/datamodel-2026/11-SMART-TOPIC-PROZESSOR-DEVOPS.md, section 5a.
 *
 * <p>This is the Java side of the operator. The reference is the web workbench
 * ({@code opentsx-saas-frontend/src/workbench/vector.ts}); both run the shared test vectors in
 * {@code docs/datamodel-2026/testvectors/align-vector.v1.json}.
 */
public final class VectorAligner {

    public enum Agg { MEAN, LAST, FIRST, MIN, MAX, SUM, COUNT }

    /**
     * @param every clock: ticks are multiples of it since the epoch
     * @param key   {@code series}, {@code all} or a tag name
     * @param asOf  fill a missing component with its last value when that is not older than {@code maxAge}
     * @param complete emit only ticks in which every component has a value
     */
    public record Params(Duration every, String key, Agg agg, boolean asOf, Duration maxAge, boolean complete) {
        public Params {
            if (every == null || every.isZero() || every.isNegative()) {
                throw new IllegalArgumentException("the clock \"every\" must be greater than 0");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must be series, all or a tag name");
            }
        }
    }

    /** One series of an input: identity and samples in time order. NaN values are skipped. */
    public record Series(String id, String metric, Map<String, String> tags, long[] timesMicros, double[] values) {
    }

    /** One input of the node, under the name it has in the vector. */
    public record Input(String name, List<Series> series) {
    }

    /** One measuring point. {@code values} has one entry per component, null where the component is missing. */
    public record Point(Map<String, String> key, long fromMicros, long toMicros, List<String> components, List<Double> values) {
        public int missing() {
            return (int) values.stream().filter(v -> v == null).count();
        }
    }

    private static final class Cell {
        long n;
        double sum;
        double min;
        double max;
        double first;
        double last;

        double value(Agg agg) {
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

    private static final class KeyState {
        final Map<String, String> key;
        final Map<String, TreeMap<Long, Cell>> cells = new LinkedHashMap<>();

        KeyState(Map<String, String> key) {
            this.key = key;
        }
    }

    private VectorAligner() {
    }

    static String snake(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Name of a series inside its input: the last part of the metric. */
    static String seriesName(Series s) {
        String metric = s.metric() == null ? "" : s.metric();
        String name = snake(metric.substring(metric.lastIndexOf('.') + 1));
        return name.isEmpty() ? "value" : name;
    }

    public static List<Point> align(List<Input> inputs, Params p) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("align.vector needs at least one input");
        }
        long every = Math.floorDiv(p.every().toNanos(), 1000);
        long maxAgeTicks = Math.floorDiv(Math.floorDiv(p.maxAge() == null ? p.every().toNanos() : p.maxAge().toNanos(), 1000), every);
        String keyBy = p.key().trim();
        boolean bySeries = keyBy.equals("series");
        boolean all = keyBy.equals("all");
        if (!bySeries && !all && inputs.stream().flatMap(i -> i.series().stream()).noneMatch(s -> s.tags().containsKey(keyBy))) {
            throw new IllegalArgumentException("no series carries the tag \"" + keyBy + "\"; use series, all or a tag of the series");
        }

        List<String> components = new ArrayList<>();
        Map<String, KeyState> keys = new TreeMap<>();     // measuring points in key order
        for (Input in : inputs) {
            // an input with several series per key contributes one component per series, named after the series
            Map<String, Integer> perKey = new LinkedHashMap<>();
            for (Series s : in.series()) {
                perKey.merge(keyText(s, bySeries, all, keyBy), 1, Integer::sum);
            }
            boolean several = perKey.values().stream().anyMatch(n -> n > 1);
            java.util.function.Function<Series, String> componentOf = s ->
                    !several ? in.name() : inputs.size() == 1 ? seriesName(s) : in.name() + "." + seriesName(s);
            // the components of one input in name order, so the vector does not depend on the order the series arrive in
            in.series().stream().map(componentOf).distinct().sorted().filter(c -> !components.contains(c)).forEach(components::add);
            for (Series s : in.series()) {
                String component = componentOf.apply(s);
                KeyState k = keys.computeIfAbsent(keyText(s, bySeries, all, keyBy), t -> new KeyState(keyOf(s, bySeries, all, keyBy)));
                TreeMap<Long, Cell> cells = k.cells.computeIfAbsent(component, c -> new TreeMap<>());
                for (int i = 0; i < s.values().length; i++) {
                    double v = s.values()[i];
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        continue;
                    }
                    long tick = Math.floorDiv(s.timesMicros()[i], every);
                    Cell cell = cells.get(tick);
                    if (cell == null) {
                        cell = new Cell();
                        cell.n = 1;
                        cell.sum = cell.min = cell.max = cell.first = cell.last = v;
                        cells.put(tick, cell);
                    } else {
                        cell.n++;
                        cell.sum += v;
                        cell.min = Math.min(cell.min, v);
                        cell.max = Math.max(cell.max, v);
                        cell.last = v;
                    }
                }
            }
        }

        List<Point> out = new ArrayList<>();
        for (KeyState k : keys.values()) {
            long lo = Long.MAX_VALUE;
            long hi = Long.MIN_VALUE;
            for (TreeMap<Long, Cell> cells : k.cells.values()) {
                if (!cells.isEmpty()) {
                    lo = Math.min(lo, cells.firstKey());
                    hi = Math.max(hi, cells.lastKey());
                }
            }
            if (hi < lo) {
                continue;
            }
            int n = Math.toIntExact(hi - lo + 1);
            Double[][] columns = new Double[components.size()][n];
            for (int c = 0; c < components.size(); c++) {
                TreeMap<Long, Cell> cells = k.cells.get(components.get(c));
                long lastTick = Long.MIN_VALUE / 2;
                Double lastValue = null;
                for (int j = 0; j < n; j++) {
                    Cell cell = cells == null ? null : cells.get(lo + j);
                    if (cell != null) {
                        lastTick = j;
                        lastValue = cell.value(p.agg());
                        columns[c][j] = lastValue;
                    } else {
                        columns[c][j] = p.asOf() && j - lastTick <= maxAgeTicks ? lastValue : null;
                    }
                }
            }
            for (int j = 0; j < n; j++) {
                List<Double> values = new ArrayList<>(components.size());
                int present = 0;
                for (Double[] column : columns) {
                    values.add(column[j]);
                    if (column[j] != null) {
                        present++;
                    }
                }
                if (p.complete() ? present == components.size() : present > 0) {
                    out.add(new Point(k.key, (lo + j) * every, (lo + j + 1) * every, List.copyOf(components),
                            java.util.Collections.unmodifiableList(values)));
                }
            }
        }
        return out;
    }

    private static String keyText(Series s, boolean bySeries, boolean all, String keyBy) {
        return bySeries ? s.id() : all ? "" : s.tags().getOrDefault(keyBy, "");
    }

    private static Map<String, String> keyOf(Series s, boolean bySeries, boolean all, String keyBy) {
        Map<String, String> key = new TreeMap<>();
        if (bySeries) {
            key.putAll(s.tags());
            key.put("series", s.id());
        } else if (!all) {
            key.put(keyBy, s.tags().getOrDefault(keyBy, ""));
        }
        return key;
    }
}
