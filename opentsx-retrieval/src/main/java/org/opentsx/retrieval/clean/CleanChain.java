package org.opentsx.retrieval.clean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The operators that turn a raw series into a clean one, for data that keeps arriving. A chain is a list of steps;
 * each series runs through its own copy of the state. The same operators compute in the workbench
 * ({@code opentsx-saas-frontend/src/workbench/ops.ts}); both are checked against
 * {@code docs/datamodel-2026/testvectors/clean-ops.v1.json}.
 *
 * <p>Besides cleaning, the chain runs the first models and the features: {@code model.wind-shear} (the wind of a
 * measuring height carried to the hub height by the power law, {@code v * (toM / fromM)^alpha}), {@code model.power-curve}
 * (what the wind offers a turbine), {@code feature.lag} and {@code feature.rolling}.
 *
 * <ul>
 *   <li>{@code dq.checks} with the check {@code PLAUSIBLE_RANGE}: a value outside [min, max] becomes a gap
 *   ({@code mode: gap}) or the bound ({@code mode: clip}).</li>
 *   <li>{@code convert.unit}: {@code value * factor + offset}, with a new unit.</li>
 *   <li>{@code resample}: windows of the width {@code every}, aligned to the epoch; the value of a window is the
 *   aggregate of its values that are no gap, and a gap when it has none; its time is the start of the window. A
 *   window is handed out when the data has passed its end.</li>
 * </ul>
 */
public final class CleanChain {

    /** Operators this runtime executes before a series sink, by their name in the DAG. */
    public static final List<String> OPS = List.of("dq.checks", "convert.unit", "resample", "model.wind-shear", "model.power-curve", "feature.lag", "feature.rolling");

    /** Values a rolling window may hold; a window over more fails, it is no feature for a stream. */
    static final int MAX_WINDOW_VALUES = 100_000;

    /** Windows a series may skip before the stream starts a new run instead of filling the gap. */
    static final long MAX_EMPTY_WINDOWS = 10_000;

    public enum Agg { MEAN, MIN, MAX, LAST }

    sealed interface Step permits Range, Convert, Resample, WindShear, PowerCurve, Lag, Rolling {
    }

    /**
     * The wind of one height carried to another by the power law. A model with one exponent for every weather: it
     * knows neither stability nor the direction of the wind. The exponent belongs to the site (about 0.1 over open
     * sea, 0.14 over flat land, 0.2 and more over rough land).
     */
    record WindShear(double factor) implements Step {
    }

    /**
     * Reference power curve of a wind turbine: zero below cut-in and above cut-out, rated power from the rated wind,
     * cubic in between; times {@code count} turbines. A model, not a measurement: it says what the wind offers.
     */
    record PowerCurve(double ratedKw, double cutIn, double ratedWind, double cutOut, double count) implements Step {
        double kw(double wind) {
            if (Double.isNaN(wind)) {
                return Double.NaN;
            }
            if (wind < cutIn || wind > cutOut) {
                return 0;
            }
            if (wind >= ratedWind) {
                return ratedKw * count;
            }
            double ci3 = cutIn * cutIn * cutIn;
            return count * ratedKw * (wind * wind * wind - ci3) / (ratedWind * ratedWind * ratedWind - ci3);
        }
    }

    /** The value {@code steps} samples earlier; a gap until that many samples have passed. */
    record Lag(int steps) implements Step {
    }

    /** Aggregate of the values of the last {@code windowMicros}, the current one included. */
    record Rolling(String node, long windowMicros, Fn fn) implements Step {
    }

    public enum Fn { MEAN, STD, MIN, MAX }

    record Range(double min, double max, boolean clip) implements Step {
    }

    record Convert(double factor, double offset, String unit) implements Step {
    }

    record Resample(long everyMicros, Agg agg) implements Step {
    }

    /** Samples of one series; {@code times} in microseconds, ascending. */
    public record Block(long[] times, double[] values) {
        public int size() {
            return times.length;
        }
    }

    private final List<Step> steps;

    private CleanChain(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * @param nodes the nodes between the source and the sink, in order: {@code {op, params}}
     * @throws IllegalArgumentException with a message for the user when a node cannot be run here
     */
    public static CleanChain of(List<JsonNode> nodes) {
        List<Step> steps = new ArrayList<>();
        // the interval of the samples a step reads; 0 until a resample step sets it
        long interval = 0;
        for (JsonNode n : nodes) {
            String id = n.path("id").asText();
            JsonNode p = n.path("params");
            switch (n.path("op").asText()) {
                case "dq.checks" -> {
                    for (JsonNode c : p.path("checks")) {
                        if (!"PLAUSIBLE_RANGE".equals(c.asText())) {
                            throw new IllegalArgumentException("node " + id + ": this runtime runs the check PLAUSIBLE_RANGE only, not " + c.asText());
                        }
                    }
                    double min = number(id, p, "min", Double.NEGATIVE_INFINITY);
                    double max = number(id, p, "max", Double.POSITIVE_INFINITY);
                    if (min > max) {
                        throw new IllegalArgumentException("node " + id + ": min is above max");
                    }
                    String mode = p.path("mode").asText("gap");
                    if (!mode.equals("gap") && !mode.equals("clip")) {
                        throw new IllegalArgumentException("node " + id + ": mode must be gap or clip");
                    }
                    steps.add(new Range(min, max, mode.equals("clip")));
                }
                case "convert.unit" -> steps.add(new Convert(number(id, p, "factor", 1), number(id, p, "offset", 0), p.path("unit").asText("")));
                case "resample" -> {
                    long every = micros(id, p, "every");
                    Agg agg;
                    try {
                        agg = Agg.valueOf(p.path("agg").asText("MEAN").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("node " + id + ": agg must be one of " + List.of(Agg.values()));
                    }
                    steps.add(new Resample(every, agg));
                    interval = every;
                }
                case "model.wind-shear" -> {
                    double from = number(id, p, "fromM", Double.NaN);
                    double to = number(id, p, "toM", Double.NaN);
                    double alpha = number(id, p, "alpha", Double.NaN);
                    if (!(from > 0) || !(to > 0) || !(alpha >= 0 && alpha <= 0.6)) {
                        throw new IllegalArgumentException("node " + id + ": a wind shear needs fromM and toM above zero and alpha"
                                + " between 0 and 0.6");
                    }
                    steps.add(new WindShear(Math.pow(to / from, alpha)));
                }
                case "model.power-curve" -> {
                    PowerCurve c = new PowerCurve(number(id, p, "ratedKw", Double.NaN), number(id, p, "cutIn", Double.NaN),
                            number(id, p, "ratedWind", Double.NaN), number(id, p, "cutOut", Double.NaN), number(id, p, "count", 1));
                    if (!(c.ratedKw() > 0) || !(c.cutIn() >= 0) || !(c.ratedWind() > c.cutIn()) || !(c.cutOut() >= c.ratedWind()) || !(c.count() > 0)) {
                        throw new IllegalArgumentException("node " + id + ": a power curve needs ratedKw above zero and cutIn below"
                                + " ratedWind, which is at most cutOut");
                    }
                    steps.add(c);
                }
                case "feature.lag" -> {
                    int k = integer(id, p, "steps", 0);
                    if (k < 1 || k > MAX_WINDOW_VALUES) {
                        throw new IllegalArgumentException("node " + id + ": steps must be between 1 and " + MAX_WINDOW_VALUES);
                    }
                    steps.add(new Lag(k));
                }
                case "feature.rolling" -> {
                    Fn fn;
                    try {
                        fn = Fn.valueOf(p.path("fn").asText("MEAN").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("node " + id + ": fn must be one of " + List.of(Fn.values()));
                    }
                    long window = micros(id, p, "window");
                    if (interval > 0 && Math.floorDiv(window - 1, interval) + 1 > MAX_WINDOW_VALUES) {
                        throw new IllegalArgumentException("node " + id + ": window: " + Duration.of(window, ChronoUnit.MICROS) + " holds "
                                + (Math.floorDiv(window - 1, interval) + 1) + " values at the interval " + Duration.of(interval, ChronoUnit.MICROS)
                                + " of its input; expected a window of at most " + Duration.of(MAX_WINDOW_VALUES * interval, ChronoUnit.MICROS)
                                + " (" + MAX_WINDOW_VALUES + " values) or a longer resample interval before it");
                    }
                    steps.add(new Rolling(id, window, fn));
                }
                default -> throw new IllegalArgumentException("node " + id + " (" + n.path("op").asText() + ") is not available in this"
                        + " runtime before a series sink: it runs " + OPS + ". Keep other operators for the workbench.");
            }
        }
        return new CleanChain(steps);
    }

    private static double number(String node, JsonNode params, String field, double dflt) {
        JsonNode v = params.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return dflt;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException("node " + node + ": " + field + ": " + shown(v)
                    + " is not a number; expected a number without quotes, such as 0.01 or 1e-3");
        }
        return v.asDouble();
    }

    private static int integer(String node, JsonNode params, String field, int dflt) {
        JsonNode v = params.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return dflt;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new IllegalArgumentException("node " + node + ": " + field + ": " + shown(v)
                    + " is not a whole number; expected a whole number from -2147483648 to 2147483647 without quotes");
        }
        return v.asInt();
    }

    private static String shown(JsonNode v) {
        return "\"" + (v.isTextual() ? v.asText().trim() : v.toString()) + "\"";
    }

    /** The longest duration of a step: a window in nanoseconds still fits a long. */
    static final Duration LONGEST = Duration.ofNanos(Long.MAX_VALUE);

    /** A duration like PT1M, or a number of seconds as the workbench writes it for other axes. */
    private static long micros(String node, JsonNode params, String field) {
        JsonNode v = params.path(field);
        Duration d;
        try {
            d = v.isNumber() ? null : Duration.parse(v.asText(""));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("node " + node + ": " + field + ": " + shown(v)
                    + " is not a duration; expected an ISO-8601 duration such as PT1M, or a number of seconds", e);
        }
        if (d == null ? !(v.asDouble() > 0) : d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("node " + node + ": " + field + ": " + shown(v)
                    + " is not above zero; expected a duration above zero, such as PT1M");
        }
        long micros = d == null ? Math.round(v.asDouble() * 1e6) : d.compareTo(LONGEST) <= 0 ? d.toNanos() / 1000 : Long.MAX_VALUE;
        if (micros <= 0 || micros > LONGEST.toNanos() / 1000) {
            throw new IllegalArgumentException("node " + node + ": " + field + ": " + shown(v) + " is out of range; expected a duration from"
                    + " PT0.000001S to " + LONGEST + ", such as PT1M");
        }
        return micros;
    }

    /** The interval of the output, or 0 when the chain keeps the time axis of its input. */
    public long outputIntervalMicros() {
        long every = 0;
        for (Step s : steps) {
            if (s instanceof Resample r) {
                every = r.everyMicros();
            }
        }
        return every;
    }

    /** The unit of the output, or null when the chain keeps the unit of its input. */
    public String outputUnit() {
        String unit = null;
        for (Step s : steps) {
            if (s instanceof Convert c && !c.unit().isBlank()) {
                unit = c.unit();
            }
            if (s instanceof PowerCurve) {
                unit = "kW";
            }
        }
        return unit;
    }

    public int size() {
        return steps.size();
    }

    /**
     * What one series carries from block to block: the open window of every resample step, and the recent samples a
     * lag or a rolling window still needs.
     */
    public static final class State {
        /** Per step the samples kept: {time, value} pairs, oldest first. */
        final List<java.util.ArrayDeque<double[]>> kept = new ArrayList<>();
        final long[] start;
        final double[] sum;
        final long[] count;
        final double[] min;
        final double[] max;
        final double[] last;
        /** Per rolling step the aggregates of its kept samples; null for the other steps. */
        final Window[] rolling;

        State(int steps) {
            start = new long[steps];
            sum = new double[steps];
            count = new long[steps];
            min = new double[steps];
            max = new double[steps];
            last = new double[steps];
            rolling = new Window[steps];
            java.util.Arrays.fill(start, Long.MIN_VALUE);
            for (int i = 0; i < steps; i++) {
                kept.add(new java.util.ArrayDeque<>());
            }
        }

        void reset(int i) {
            sum[i] = 0;
            count[i] = 0;
            min[i] = Double.POSITIVE_INFINITY;
            max[i] = Double.NEGATIVE_INFINITY;
            last[i] = Double.NaN;
        }

        public ObjectNode json() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            ArrayNode a = o.putArray("windows");
            for (int i = 0; i < start.length; i++) {
                if (start[i] != Long.MIN_VALUE) {
                    a.addObject().put("step", i).put("start", start[i]).put("sum", text(sum[i])).put("count", count[i])
                            .put("min", text(min[i])).put("max", text(max[i])).put("last", text(last[i]));
                }
            }
            ArrayNode k = o.putArray("kept");
            for (int i = 0; i < kept.size(); i++) {
                if (!kept.get(i).isEmpty()) {
                    ArrayNode samples = k.addObject().put("step", i).putArray("samples");
                    kept.get(i).forEach(tv -> samples.addArray().add((long) tv[0]).add(text(tv[1])));
                }
            }
            // the running sums as they are, so a restart goes on with the same rounding as a run without one
            ArrayNode r = o.putArray("rolling");
            for (int i = 0; i < rolling.length; i++) {
                if (rolling[i] != null) {
                    r.addObject().put("step", i).put("sum", text(rolling[i].sum)).put("sq", text(rolling[i].sq))
                            .put("sumLost", text(rolling[i].sumLost)).put("sqLost", text(rolling[i].sqLost)).put("removed", rolling[i].removed);
                }
            }
            return o;
        }

        private static String text(double v) {
            return Double.toString(v);
        }
    }

    public State newState() {
        State st = new State(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            st.reset(i);
            if (steps.get(i) instanceof Rolling) {
                st.rolling[i] = new Window();
            }
        }
        return st;
    }

    public State restore(JsonNode json) {
        State st = newState();
        for (JsonNode w : json.path("windows")) {
            int i = w.path("step").asInt(-1);
            if (i >= 0 && i < steps.size()) {
                st.start[i] = w.path("start").asLong();
                st.sum[i] = Double.parseDouble(w.path("sum").asText("0"));
                st.count[i] = w.path("count").asLong();
                st.min[i] = Double.parseDouble(w.path("min").asText("Infinity"));
                st.max[i] = Double.parseDouble(w.path("max").asText("-Infinity"));
                st.last[i] = Double.parseDouble(w.path("last").asText("NaN"));
            }
        }
        for (JsonNode k : json.path("kept")) {
            int i = k.path("step").asInt(-1);
            if (i >= 0 && i < steps.size()) {
                k.path("samples").forEach(tv -> st.kept.get(i).add(new double[]{tv.get(0).asLong(), Double.parseDouble(tv.get(1).asText("NaN"))}));
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Rolling r) {
                for (double[] tv : st.kept.get(i)) {
                    st.rolling[i].enter(tv, r.fn());
                }
            }
        }
        for (JsonNode w : json.path("rolling")) {
            int i = w.path("step").asInt(-1);
            if (i >= 0 && i < steps.size() && st.rolling[i] != null) {
                st.rolling[i].sum = Double.parseDouble(w.path("sum").asText("0"));
                st.rolling[i].sq = Double.parseDouble(w.path("sq").asText("0"));
                st.rolling[i].sumLost = Double.parseDouble(w.path("sumLost").asText("0"));
                st.rolling[i].sqLost = Double.parseDouble(w.path("sqLost").asText("0"));
                st.rolling[i].removed = w.path("removed").asInt();
            }
        }
        return st;
    }

    /**
     * Runs a block through the chain.
     *
     * @param tEndMicros exclusive end of the block: the data of the series has progressed to here
     * @return what is final now; the samples of a window that is still open stay in the state
     */
    public Block apply(State st, Block in, long tEndMicros) {
        Block b = in;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s instanceof Range r) {
                double[] v = new double[b.size()];
                for (int k = 0; k < v.length; k++) {
                    double x = b.values()[k];
                    v[k] = x < r.min() ? (r.clip() ? r.min() : Double.NaN) : x > r.max() ? (r.clip() ? r.max() : Double.NaN) : x;
                }
                b = new Block(b.times(), v);
            } else if (s instanceof Convert c) {
                double[] v = new double[b.size()];
                for (int k = 0; k < v.length; k++) {
                    v[k] = b.values()[k] * c.factor() + c.offset();
                }
                b = new Block(b.times(), v);
            } else if (s instanceof Resample r) {
                b = resample(st, i, r, b, tEndMicros);
            } else if (s instanceof WindShear w) {
                double[] v = new double[b.size()];
                for (int k = 0; k < v.length; k++) {
                    v[k] = b.values()[k] * w.factor();
                }
                b = new Block(b.times(), v);
            } else if (s instanceof PowerCurve c) {
                double[] v = new double[b.size()];
                for (int k = 0; k < v.length; k++) {
                    v[k] = c.kw(b.values()[k]);
                }
                b = new Block(b.times(), v);
            } else if (s instanceof Lag l) {
                b = lag(st.kept.get(i), l, b);
            } else if (s instanceof Rolling r) {
                b = rolling(st, i, r, b);
            }
        }
        return b;
    }

    private static Block resample(State st, int i, Resample r, Block in, long tEnd) {
        long every = r.everyMicros();
        long[] t = new long[in.size() + 8];
        double[] v = new double[in.size() + 8];
        int n = 0;
        for (int k = 0; k < in.size(); k++) {
            long ts = in.times()[k];
            if (st.start[i] == Long.MIN_VALUE || ts - st.start[i] > MAX_EMPTY_WINDOWS * every) {
                // the first sample, or a long silence: the window grid starts again at this sample
                st.start[i] = Math.floorDiv(ts, every) * every;
                st.reset(i);
            }
            while (ts >= st.start[i] + every) {
                if (n == t.length) {
                    t = java.util.Arrays.copyOf(t, n * 2);
                    v = java.util.Arrays.copyOf(v, n * 2);
                }
                t[n] = st.start[i];
                v[n++] = value(st, i, r.agg());
                st.start[i] += every;
                st.reset(i);
            }
            double x = in.values()[k];
            if (!Double.isNaN(x) && !Double.isInfinite(x)) {
                st.sum[i] += x;
                st.count[i]++;
                st.min[i] = Math.min(st.min[i], x);
                st.max[i] = Math.max(st.max[i], x);
                st.last[i] = x;
            }
        }
        // the data has passed the end of the open window: it is final, also when the next block is late
        while (st.start[i] != Long.MIN_VALUE && tEnd >= st.start[i] + every && tEnd - st.start[i] <= MAX_EMPTY_WINDOWS * every) {
            if (n == t.length) {
                t = java.util.Arrays.copyOf(t, n * 2);
                v = java.util.Arrays.copyOf(v, n * 2);
            }
            t[n] = st.start[i];
            v[n++] = value(st, i, r.agg());
            st.start[i] += every;
            st.reset(i);
        }
        return new Block(java.util.Arrays.copyOf(t, n), java.util.Arrays.copyOf(v, n));
    }

    private static Block lag(java.util.ArrayDeque<double[]> kept, Lag l, Block in) {
        double[] v = new double[in.size()];
        for (int k = 0; k < v.length; k++) {
            v[k] = kept.size() < l.steps() ? Double.NaN : kept.peekFirst()[1];
            kept.addLast(new double[]{in.times()[k], in.values()[k]});
            if (kept.size() > l.steps()) {
                kept.removeFirst();
            }
        }
        return new Block(in.times(), v);
    }

    /**
     * What a rolling step knows of the values in its window that are no gap: the sums of the finite ones, the number
     * of infinite ones, how many of the latest values are equal, and for MIN and MAX the samples that can still
     * become the extreme. Each sample enters and leaves once, so a step costs the same for any window.
     */
    static final class Window {
        double sum;
        double sq;
        /**
         * What the additions to sum and sq rounded away (Neumaier): a value that leaves takes its share with it, so a
         * level that falls by orders of magnitude leaves no rounding behind in the window after it.
         */
        double sumLost;
        double sqLost;
        int n;
        int positiveInfinite;
        int negativeInfinite;
        /** Finite values taken out since the sums were added up afresh; bounds the rounding error of the subtractions. */
        int removed;
        /** The latest value and how many values in a row up to it were equal: a window of equal values has no spread. */
        double latest = Double.NaN;
        long equal;
        /** MIN (MAX): samples in time order whose values rise (fall), so the first is the extreme of the window. */
        final java.util.ArrayDeque<double[]> extremes = new java.util.ArrayDeque<>();

        void enter(double[] sample, Fn fn) {
            double x = sample[1];
            if (Double.isNaN(x)) {
                return;
            }
            n++;
            equal = x == latest ? equal + 1 : 1;
            latest = x;
            if (x == Double.POSITIVE_INFINITY) {
                positiveInfinite++;
            } else if (x == Double.NEGATIVE_INFINITY) {
                negativeInfinite++;
            } else {
                add(x, x * x);
            }
            if (fn == Fn.MIN || fn == Fn.MAX) {
                while (!extremes.isEmpty() && (fn == Fn.MIN ? extremes.peekLast()[1] > x : extremes.peekLast()[1] < x)) {
                    extremes.removeLast();
                }
                extremes.addLast(sample);
            }
        }

        void leave(double[] sample) {
            double x = sample[1];
            if (Double.isNaN(x)) {
                return;
            }
            n--;
            if (x == Double.POSITIVE_INFINITY) {
                positiveInfinite--;
            } else if (x == Double.NEGATIVE_INFINITY) {
                negativeInfinite--;
            } else {
                add(-x, -(x * x));
                removed++;
            }
            if (extremes.peekFirst() == sample) {
                extremes.removeFirst();
            }
        }

        private void add(double x, double xx) {
            double s = sum + x;
            if (Double.isFinite(s)) {
                sumLost += Math.abs(sum) >= Math.abs(x) ? (sum - s) + x : (x - s) + sum;
            }
            sum = s;
            double q = sq + xx;
            if (Double.isFinite(q)) {
                sqLost += Math.abs(sq) >= Math.abs(xx) ? (sq - q) + xx : (xx - q) + sq;
            }
            sq = q;
        }

        void clear() {
            sum = 0;
            sq = 0;
            sumLost = 0;
            sqLost = 0;
            removed = 0;
        }

        void addUp(java.util.ArrayDeque<double[]> kept) {
            clear();
            for (double[] tv : kept) {
                if (Double.isFinite(tv[1])) {
                    add(tv[1], tv[1] * tv[1]);
                }
            }
        }

        double value(Fn fn) {
            if (n == 0) {
                return Double.NaN;
            }
            boolean same = n <= equal;
            double mean = same ? latest : (sum + sumLost) / n;
            return switch (fn) {
                case MIN, MAX -> extremes.peekFirst()[1];
                case MEAN -> positiveInfinite + negativeInfinite == 0 || same ? mean : negativeInfinite == 0 ? Double.POSITIVE_INFINITY
                        : positiveInfinite == 0 ? Double.NEGATIVE_INFINITY : Double.NaN;
                // of the values in the window, as a population: no value, no spread
                case STD -> positiveInfinite + negativeInfinite > 0 ? Double.NaN : same ? 0 : Math.sqrt(Math.max(0, (sq + sqLost) / n - mean * mean));
            };
        }
    }

    private static Block rolling(State st, int i, Rolling r, Block in) {
        java.util.ArrayDeque<double[]> kept = st.kept.get(i);
        Window w = st.rolling[i];
        double[] v = new double[in.size()];
        for (int k = 0; k < v.length; k++) {
            long t = in.times()[k];
            double[] sample = {t, in.values()[k]};
            kept.addLast(sample);
            w.enter(sample, r.fn());
            // the window reaches back windowMicros from this sample, this sample included
            while ((long) kept.peekFirst()[0] <= t - r.windowMicros()) {
                w.leave(kept.removeFirst());
            }
            if (kept.size() > MAX_WINDOW_VALUES) {
                throw new IllegalStateException("node " + r.node() + ": window " + Duration.ofNanos(r.windowMicros() * 1000)
                        + " holds more than " + MAX_WINDOW_VALUES + " values; give a shorter window or resample the series first");
            }
            if (w.n == w.positiveInfinite + w.negativeInfinite) {
                w.clear();
            } else if (w.removed > kept.size() || !Double.isFinite(w.sq)) {
                // a square that is no finite number stays so when it leaves; only adding up afresh brings the sums back
                w.addUp(kept);
            }
            v[k] = w.value(r.fn());
        }
        return new Block(in.times(), v);
    }

    private static double value(State st, int i, Agg agg) {
        if (st.count[i] == 0) {
            return Double.NaN;
        }
        return switch (agg) {
            case MIN -> st.min[i];
            case MAX -> st.max[i];
            case LAST -> st.last[i];
            case MEAN -> st.sum[i] / st.count[i];
        };
    }
}
