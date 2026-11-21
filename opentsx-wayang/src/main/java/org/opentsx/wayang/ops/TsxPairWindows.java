package org.opentsx.wayang.ops;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pair windows of the workbench ({@code opentsx-saas-frontend/src/workbench/pairs.ts}) for one pair of episodes: pair
 * range, windows, alignment onto one clock, coverage and one value per field and window. Times are seconds since the
 * epoch, as in the workbench.
 */
public final class TsxPairWindows {

    /** Half a microsecond: the v2 model keeps times to the microsecond (pairs.ts:29). */
    public static final double TOL = 5e-7;

    private TsxPairWindows() {
    }

    /** A pair method: {@code pair.<name>}, its fields and one value per field for the aligned ticks of a window. */
    public interface Method {
        String id();

        List<String> fields();

        /** Settings beyond the window, written as labels {@code pair.<key>}. */
        Map<String, String> settings();

        double[] compute(double[] a, double[] b, double every, double coverage);
    }

    /** {@code pair.xcorr} (xcorr.ts:101-114): fields max, lag, strength. */
    public static Method xcorr(Map<String, ?> params) {
        int lags = TsxPairXcorr.lagsOf(params.get("lags"));
        return new Method() {
            public String id() {
                return "pair.xcorr";
            }

            public List<String> fields() {
                return List.of("max", "lag", "strength");
            }

            public Map<String, String> settings() {
                return Map.of("lags", Integer.toString(lags));
            }

            public double[] compute(double[] a, double[] b, double every, double coverage) {
                TsxPairXcorr.Peak peak = TsxPairXcorr.peakOf(TsxPairXcorr.crossCorrelation(a, b, lags));
                if (!Double.isFinite(peak.value())) {
                    return new double[]{Double.NaN, Double.NaN, Double.NaN};
                }
                // (lags - index), not -(index - lags): no negative zero at lag 0
                return new double[]{peak.value(), (lags - peak.index()) * every, peak.strength()};
            }
        };
    }

    /** {@code pair.es} (es.ts:132-166): fields q_total, q_ab, q_ba, q_rand; the cap tauMax in seconds. */
    public static Method es(Map<String, ?> params) {
        double sigma = number(params.get("sigma"), 2);
        if (!Double.isFinite(sigma)) {
            throw new IllegalArgumentException("sigma \"" + params.get("sigma") + "\" is not a number.");
        }
        double cap = number(params.get("tauMax"), 0);
        if (!(cap >= 0)) {
            throw new IllegalArgumentException("tauMax \"" + params.get("tauMax") + "\" is not a duration in seconds, 0 or more.");
        }
        double shuffles = number(params.get("shuffles"), 10);
        if (shuffles != Math.rint(shuffles) || shuffles < 1 || shuffles > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shuffles \"" + params.get("shuffles") + "\" is not a whole number of 1 or more.");
        }
        double seed = number(params.get("seed"), 1);
        if (seed != Math.rint(seed) || Double.isInfinite(seed)) {
            throw new IllegalArgumentException("seed \"" + params.get("seed") + "\" is not a whole number.");
        }
        Double tauMax = cap > 0 ? cap : null;
        Map<String, String> settings = Map.of("sigma", text(sigma), "tauMax", tauMax == null ? "none" : "PT" + text(tauMax) + "S",
                "shuffles", text(shuffles), "seed", text(seed));
        return new Method() {
            public String id() {
                return "pair.es";
            }

            public List<String> fields() {
                return List.of("q_total", "q_ab", "q_ba", "q_rand");
            }

            public Map<String, String> settings() {
                return settings;
            }

            public double[] compute(double[] a, double[] b, double every, double coverage) {
                double[] ea = TsxPairEs.detectEvents(a, sigma);
                double[] eb = TsxPairEs.detectEvents(b, sigma);
                Double tau = tauMax == null ? null : tauMax / every;
                TsxPairEs.Sync sync = TsxPairEs.eventSync(ea, eb, tau);
                return new double[]{sync.qTotal(), sync.qAb(), sync.qBa(),
                        TsxPairEs.surrogateSync(ea, eb, (int) shuffles, (long) (seed % 4294967296.0), tau)};
            }
        };
    }

    /** A parameter as a number, as JavaScript's Number() reads a number or a decimal string. */
    private static double number(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A number as JavaScript's String() writes it, for the values that occur in settings. */
    private static String text(double v) {
        return TsxPairXcorr.number(v);
    }

    /** The samples of one episode: start and end of its range, times and values. */
    public record Piece(String seriesId, String episodeId, double start, double end, double[] x, double[] y) {
    }

    /** A pair range: the part of an episode of A and an episode of B that is compared. */
    public record Range(double start, double end, Piece a, Piece b) {
    }

    /** What one pair range gives: the window centres and per field one value per window, with quality 1 for NaN items. */
    public record Result(Range range, double every, double[] centres, double[][] values, byte[] quality) {
    }

    /** The pair range of two episodes, or null (pairs.ts:127-150, for one episode of A and one of B). */
    public static Range pair(Piece a, Piece b, String pairing, Double window) {
        if (pairing.equals("same")) {
            boolean same = Math.round(a.start / (2 * TOL)) == Math.round(b.start / (2 * TOL))
                    && Math.round(a.end / (2 * TOL)) == Math.round(b.end / (2 * TOL));
            return same ? new Range(a.start, a.end, a, b) : null;
        }
        double start = Math.max(a.start, b.start);
        double end = Math.min(a.end, b.end);
        double minLength = window == null ? 0 : window;
        return end - start > TOL && end - start >= minLength - TOL ? new Range(start, end, a, b) : null;
    }

    /** Median spacing of the times: the sampling interval (pairs.ts:99-106). */
    public static double samplingInterval(double[] x) {
        if (x.length < 2) {
            return 1;
        }
        double[] d = new double[x.length - 1];
        for (int i = 1; i < x.length; i++) {
            d[i - 1] = x[i] - x[i - 1];
        }
        Arrays.sort(d);
        double m = d[d.length / 2];
        return m > 0 ? m : 1;
    }

    /** Tag value of a duration (pairs.ts:93-96). */
    public static String durationTag(Double seconds) {
        if (seconds == null) {
            return "episode";
        }
        BigDecimal v = new BigDecimal(seconds).round(new MathContext(12, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        return "PT" + (v.signum() == 0 ? "0" : v.toPlainString()) + "S";
    }

    private static final class Cell {
        int n;
        double sum;
        double min;
        double max;
        double first;
        double last;
    }

    private static Map<Long, Cell> cells(Piece p, double every) {
        Map<Long, Cell> cells = new HashMap<>();
        for (int i = 0; i < p.x.length; i++) {
            double v = p.y[i];
            if (!Double.isFinite(v)) {
                continue;
            }
            long tick = (long) Math.floor(p.x[i] / every);
            Cell c = cells.get(tick);
            if (c == null) {
                c = new Cell();
                c.n = 1;
                c.sum = c.min = c.max = c.first = c.last = v;
                cells.put(tick, c);
            } else {
                c.n++;
                c.sum += v;
                c.min = Math.min(c.min, v);
                c.max = Math.max(c.max, v);
                c.last = v;
            }
        }
        return cells;
    }

    private static double value(Cell c, String agg) {
        if (c == null) {
            return Double.NaN;
        }
        return switch (agg) {
            case "LAST" -> c.last;
            case "FIRST" -> c.first;
            case "MIN" -> c.min;
            case "MAX" -> c.max;
            case "SUM" -> c.sum;
            case "COUNT" -> c.n;
            default -> c.sum / c.n;
        };
    }

    /** Ticks lo to lo + n - 1, one aggregate per tick, fill as-of up to maxAgeTicks; nothing before lo (vector.ts:79-94). */
    private static double[] column(Map<Long, Cell> cells, long lo, int n, String agg, long maxAgeTicks) {
        double[] col = new double[Math.max(0, n)];
        long lastTick = Long.MIN_VALUE / 2;
        double lastValue = Double.NaN;
        for (int j = 0; j < n; j++) {
            double v = value(cells.get(lo + j), agg);
            if (Double.isFinite(v)) {
                lastTick = j;
                lastValue = v;
                col[j] = v;
            } else {
                col[j] = j - lastTick <= maxAgeTicks ? lastValue : Double.NaN;
            }
        }
        return col;
    }

    /** Runs the method over the windows of one pair range (pairs.ts:306-349); null when the range is shorter than a window. */
    public static Result run(Method method, TsxPairXcorr.PairSettings st, Range range) {
        double every = st.every() != null ? st.every()
                : Math.max(samplingInterval(range.a().x()), samplingInterval(range.b().x()));
        long maxAgeTicks = st.maxAge() == null ? -1 : (long) Math.floor(st.maxAge() / every);
        List<TsxPairXcorr.WindowSpan> windows = TsxPairXcorr.windowsOf(range.start(), range.end(), st.window(), st.step());
        if (windows.isEmpty()) {
            return null;
        }
        long lo = (long) Math.ceil((range.start() - TOL) / every);
        int n = (int) ((long) Math.floor((range.end() + TOL) / every) - lo);
        double[] colA = column(cells(range.a(), every), lo, n, st.agg(), maxAgeTicks);
        double[] colB = column(cells(range.b(), every), lo, n, st.agg(), maxAgeTicks);
        int fields = method.fields().size();
        double[] centres = new double[windows.size()];
        double[][] values = new double[fields][windows.size()];
        byte[] quality = new byte[windows.size()];
        for (int k = 0; k < windows.size(); k++) {
            TsxPairXcorr.WindowSpan w = windows.get(k);
            int j0 = (int) Math.max(0, (long) Math.ceil((w.start() - TOL) / every) - lo);
            int j1 = (int) Math.min(n, (long) Math.floor((w.end() + TOL) / every) - lo);
            int both = 0;
            for (int j = j0; j < j1; j++) {
                if (Double.isFinite(colA[j]) && Double.isFinite(colB[j])) {
                    both++;
                }
            }
            double coverage = j1 > j0 ? (double) both / (j1 - j0) : 0;
            boolean ok = j1 > j0 && coverage >= st.minCoverage() - 1e-12;
            double[] v = ok ? method.compute(Arrays.copyOfRange(colA, j0, j1), Arrays.copyOfRange(colB, j0, j1), every, coverage)
                    : null;
            centres[k] = w.centre();
            for (int f = 0; f < fields; f++) {
                values[f][k] = ok ? v[f] : Double.NaN;
            }
            quality[k] = (byte) (ok ? 0 : 1);
        }
        return new Result(range, every, centres, values, quality);
    }
}
