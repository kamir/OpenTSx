package org.opentsx.wayang.ops;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class TsxPairXcorr {

    private static final double TOL = 5e-7;

    private TsxPairXcorr() { }

    public record Peak(int index, double value, double strength) { }

    public record WindowSpan(double start, double end, double centre) { }

    public record Window(double centre, double max, double lag, double strength, double coverage, int quality,
                         double[] cc) { }

    public record PairSettings(String pairing, Double window, double step, Double every, String agg, Double maxAge,
                               double minCoverage) { }

    private record Moments(double mean, double std) { }

    public static PairSettings pairSettings(Map<String, ?> params) {
        String pairing = text(params.get("pairing"), "same");
        if (!pairing.equals("same") && !pairing.equals("intersect")) {
            throw new IllegalArgumentException("Pairing \"" + pairing + "\" is unknown. Use same or intersect.");
        }
        String w = trim(text(params.get("window"), "episode"));
        Double window = w.equals("episode") || w.isEmpty() ? null : numeric(w);
        if (window != null && !(window > 0)) {
            throw new IllegalArgumentException("The window \"" + w
                    + "\" is neither episode nor a width in seconds greater than 0.");
        }
        double requestedStep = numeric(params.get("step"));
        double step = window == null ? 0 : requestedStep > 0 ? requestedStep : window;
        double requestedEvery = numeric(params.get("every"));
        Double every = requestedEvery > 0 ? requestedEvery : null;
        String agg = text(params.get("agg"), "MEAN");
        if (!List.of("MEAN", "LAST", "FIRST", "MIN", "MAX", "SUM", "COUNT").contains(agg)) {
            throw new IllegalArgumentException("Aggregate \"" + agg + "\" is unknown.");
        }
        String fill = text(params.get("fill"), "none");
        if (!fill.equals("none") && !fill.equals("as-of")) {
            throw new IllegalArgumentException("pair fill must be none or as-of");
        }
        double requestedAge = numeric(params.get("maxAge"));
        Double maxAge = fill.equals("as-of") ? Math.max(0, Double.isNaN(requestedAge) ? 0 : requestedAge) : null;
        double requestedCoverage = params.containsKey("minCoverage") ? numeric(params.get("minCoverage")) : Double.NaN;
        double minCoverage = Double.isFinite(requestedCoverage) ? requestedCoverage : 0.8;
        if (minCoverage < 0 || minCoverage > 1) {
            throw new IllegalArgumentException("The minimum coverage is a share between 0 and 1.");
        }
        return new PairSettings(pairing, window, step, every, agg, maxAge, minCoverage);
    }

    public static int lagsOf(Object value) {
        double lags = value == null ? 10 : numeric(value);
        if (!Double.isFinite(lags) || lags != Math.rint(lags) || lags < 0) {
            throw new IllegalArgumentException("lags \"" + text(value, "10")
                    + "\" is not a whole number of clock ticks, 0 or more.");
        }
        if (lags > (Integer.MAX_VALUE - 1) / 2) {
            throw new IllegalArgumentException("The lag range exceeds the maximum Java array length.");
        }
        return (int) lags;
    }

    public static double[] crossCorrelation(double[] a, double[] b, double lags) {
        int range = lagsOf(lags);
        requireSameLength(a, b);
        int n = a.length;
        double[] out = new double[2 * range + 1];
        Moments ma = moments(a);
        Moments mb = moments(b);
        if (!(ma.std > 0) || !(mb.std > 0)) {
            Arrays.fill(out, Double.NaN);
            return out;
        }
        double[] x = new double[n];
        double[] y = new double[n];
        for (int t = 0; t < n; t++) {
            x[t] = Double.isFinite(a[t]) ? a[t] - ma.mean : 0;
            y[t] = Double.isFinite(b[t]) ? b[t] - mb.mean : 0;
        }
        double den = (n - 1) * ma.std * mb.std;
        for (int k = -range; k <= range; k++) {
            double sum = 0;
            int end = k > 0 ? n : Math.max(0, n + k);
            for (int t = Math.max(0, k); t < end; t++) sum += x[t] * y[t - k];
            out[k + range] = sum / den;
        }
        return out;
    }

    public static Peak peakOf(double[] cc) {
        if (cc.length == 0) return new Peak(0, Double.NaN, Double.NaN);
        int hi = 0;
        int lo = 0;
        double sum = cc[0];
        for (int i = 1; i < cc.length; i++) {
            if (cc[i] > cc[hi]) hi = i;
            if (cc[i] < cc[lo]) lo = i;
            sum += cc[i];
        }
        int index = Math.abs(cc[hi]) < Math.abs(cc[lo]) ? lo : hi;
        return new Peak(index, cc[index], (Math.abs(cc[index]) - sum / cc.length) / moments(cc).std);
    }

    public static List<WindowSpan> windowsOf(double start, double end, Double width, double step) {
        if (!Double.isFinite(start) || !Double.isFinite(end)) {
            throw new IllegalArgumentException("The pair range must have finite bounds.");
        }
        double length = end - start;
        if (width == null) {
            return length > TOL ? List.of(new WindowSpan(start, end, start + length / 2)) : List.of();
        }
        if (!(width > 0) || !Double.isFinite(width)) {
            throw new IllegalArgumentException("The window \"" + number(width)
                    + "\" is neither episode nor a width in seconds greater than 0.");
        }
        if (length < width - TOL || !(step > 0)) return List.of();
        if (!Double.isFinite(step)) throw new IllegalArgumentException("The window step must be finite.");
        double count = Math.max(0, Math.floor((length - width) / step + 1e-9) + 1);
        if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("The pair range has too many windows.");
        List<WindowSpan> windows = new ArrayList<>((int) count);
        for (int k = 0; k < count; k++) {
            double s = start + k * step;
            windows.add(new WindowSpan(s, s + width, s + width / 2));
        }
        return windows;
    }

    public static List<Window> compute(double[] a, double[] b, double start, double every, Double width,
                                       Double step, Double lags, double minCoverage) {
        requireSameLength(a, b);
        int range = lagsOf(lags);
        if (!(every > 0) || !Double.isFinite(every)) {
            throw new IllegalArgumentException("The clock interval must be finite and greater than 0.");
        }
        if (!Double.isFinite(minCoverage) || minCoverage < 0 || minCoverage > 1) {
            throw new IllegalArgumentException("The minimum coverage is a share between 0 and 1.");
        }
        double firstTick = Math.rint(start / every);
        if (!Double.isFinite(firstTick) || Math.abs(start - firstTick * every) > TOL) {
            throw new IllegalArgumentException("The first aligned sample must start on the epoch clock.");
        }
        double stride = width == null ? 0 : step != null && step > 0 ? step : width;
        List<Window> out = new ArrayList<>();
        double lo = Math.ceil((start - TOL) / every);
        for (WindowSpan w : windowsOf(start, start + a.length * every, width, stride)) {
            int j0 = (int) Math.max(0, Math.ceil((w.start - TOL) / every) - lo);
            int j1 = (int) Math.min(a.length, Math.floor((w.end + TOL) / every) - lo);
            int both = 0;
            for (int j = j0; j < j1; j++) if (Double.isFinite(a[j]) && Double.isFinite(b[j])) both++;
            double coverage = j1 > j0 ? (double) both / (j1 - j0) : 0;
            if (j1 <= j0 || coverage < minCoverage - 1e-12) {
                out.add(new Window(w.centre, Double.NaN, Double.NaN, Double.NaN, coverage, 1, new double[0]));
                continue;
            }
            double[] cc = crossCorrelation(Arrays.copyOfRange(a, j0, j1), Arrays.copyOfRange(b, j0, j1), range);
            Peak peak = peakOf(cc);
            boolean finite = Double.isFinite(peak.value);
            out.add(new Window(w.centre, finite ? peak.value : Double.NaN,
                    finite ? (range - peak.index) * every : Double.NaN,
                    finite ? peak.strength : Double.NaN, coverage, 0, cc));
        }
        return out;
    }

    private static Moments moments(double[] values) {
        int n = 0;
        double sum = 0;
        for (double value : values) {
            if (Double.isFinite(value)) {
                n++;
                sum += value;
            }
        }
        double mean = sum / n;
        double ss = 0;
        for (double value : values) if (Double.isFinite(value)) ss += (value - mean) * (value - mean);
        return new Moments(mean, n > 1 ? Math.sqrt(ss / (n - 1)) : Double.NaN);
    }

    private static void requireSameLength(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("A has " + a.length + " values and B " + b.length
                    + ": a window needs both alike.");
        }
    }

    static String number(double value) {
        if (!Double.isFinite(value)) return Double.toString(value);
        if (value == 0) return "0";
        BigDecimal exact = new BigDecimal(value);
        for (int digits = 1; digits <= 17; digits++) {
            BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() != value) continue;
            candidate = candidate.stripTrailingZeros();
            int exponent = candidate.precision() - candidate.scale() - 1;
            return exponent < -6 || exponent >= 21 ? candidate.toString().replace('E', 'e') : candidate.toPlainString();
        }
        throw new IllegalArgumentException("The number has no decimal representation that round-trips.");
    }

    private static String text(Object value, String fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return number(n.doubleValue());
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("Pair parameters must be numbers or strings.");
    }

    private static double numeric(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.doubleValue();
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Pair parameters must be numbers or strings.");
        }
        String number = trim(s);
        if (number.isEmpty()) return 0;
        if (number.matches("0[xX][0-9a-fA-F]+")) return new java.math.BigInteger(number.substring(2), 16).doubleValue();
        if (number.matches("0[bB][01]+")) return new java.math.BigInteger(number.substring(2), 2).doubleValue();
        if (number.matches("0[oO][0-7]+")) return new java.math.BigInteger(number.substring(2), 8).doubleValue();
        if (!number.matches("[+-]?(?:Infinity|(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")) {
            return Double.NaN;
        }
        return Double.parseDouble(number);
    }

    private static String trim(String text) {
        return text.replaceAll("^[\\s\\p{Z}\uFEFF]+|[\\s\\p{Z}\uFEFF]+$", "");
    }
}
