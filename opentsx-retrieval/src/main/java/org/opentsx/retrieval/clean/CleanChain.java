package org.opentsx.retrieval.clean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The operators that turn a raw series into a clean one, for data that keeps arriving. A chain is a list of steps;
 * each series runs through its own copy of the state. The same operators compute in the workbench
 * ({@code opentsx-saas-frontend/src/workbench/ops.ts}); both are checked against
 * {@code docs/datamodel-2026/testvectors/clean-ops.v1.json}.
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
    public static final List<String> OPS = List.of("dq.checks", "convert.unit", "resample");

    /** Windows a series may skip before the stream starts a new run instead of filling the gap. */
    static final long MAX_EMPTY_WINDOWS = 10_000;

    public enum Agg { MEAN, MIN, MAX, LAST }

    sealed interface Step permits Range, Convert, Resample {
    }

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
                    double min = p.path("min").asDouble(Double.NEGATIVE_INFINITY);
                    double max = p.path("max").asDouble(Double.POSITIVE_INFINITY);
                    if (min > max) {
                        throw new IllegalArgumentException("node " + id + ": min is above max");
                    }
                    String mode = p.path("mode").asText("gap");
                    if (!mode.equals("gap") && !mode.equals("clip")) {
                        throw new IllegalArgumentException("node " + id + ": mode must be gap or clip");
                    }
                    steps.add(new Range(min, max, mode.equals("clip")));
                }
                case "convert.unit" -> steps.add(new Convert(p.path("factor").asDouble(1), p.path("offset").asDouble(0), p.path("unit").asText("")));
                case "resample" -> {
                    long every = micros(id, p.path("every"));
                    Agg agg;
                    try {
                        agg = Agg.valueOf(p.path("agg").asText("MEAN").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("node " + id + ": agg must be one of " + List.of(Agg.values()));
                    }
                    steps.add(new Resample(every, agg));
                }
                default -> throw new IllegalArgumentException("node " + id + " (" + n.path("op").asText() + ") is not available in this"
                        + " runtime before a series sink: it runs " + OPS + ". Keep other operators for the workbench.");
            }
        }
        return new CleanChain(steps);
    }

    /** A duration like PT1M, or a number of seconds as the workbench writes it for other axes. */
    private static long micros(String node, JsonNode every) {
        long micros;
        try {
            micros = every.isNumber() ? Math.round(every.asDouble() * 1e6) : Duration.parse(every.asText("")).toNanos() / 1000;
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("node " + node + ": every must be a duration like PT1M");
        }
        if (micros <= 0) {
            throw new IllegalArgumentException("node " + node + ": every must be above zero");
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
        }
        return unit;
    }

    public int size() {
        return steps.size();
    }

    /** What one series carries from block to block: the open window of every resample step. */
    public static final class State {
        final long[] start;
        final double[] sum;
        final long[] count;
        final double[] min;
        final double[] max;
        final double[] last;

        State(int steps) {
            start = new long[steps];
            sum = new double[steps];
            count = new long[steps];
            min = new double[steps];
            max = new double[steps];
            last = new double[steps];
            java.util.Arrays.fill(start, Long.MIN_VALUE);
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
