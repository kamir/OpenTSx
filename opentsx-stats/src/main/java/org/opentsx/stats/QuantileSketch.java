package org.opentsx.stats;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Mergeable quantile sketch with relative accuracy (DDSketch algorithm, Masson et al., VLDB 2019).
 *
 * <p>A finite value {@code x} with {@code |x| >= MIN_INDEXABLE} falls into bin {@code ceil(log_gamma |x|)} of the
 * positive or negative store, {@code gamma = (1 + alpha) / (1 - alpha)}; smaller magnitudes are counted as zero.
 * Every quantile is returned with a relative error of at most {@code alpha}. Sketches with the same {@code alpha}
 * merge exactly (bin counts add up), so per-episode or per-turbine sketches can be combined into window or
 * per-channel sketches. NaN and infinite values are ignored (callers count them).
 *
 * <p>Binary format v1 (stored in {@code SeriesStats.quantileSketch}; identical in Java and Python):
 * <pre>
 * byte    0x01                  format version
 * double  alpha                 little-endian IEEE 754
 * varint  zeroCount
 * store   positive, then negative:  varint binCount, then per bin in ascending index order:
 *                                   zig-zag varint (index - previousIndex, previousIndex starts at 0), varint count
 * </pre>
 * The encoding is canonical: equal sketches give equal bytes.
 */
public final class QuantileSketch {

    public static final double DEFAULT_ALPHA = 0.01;
    public static final double MIN_INDEXABLE = 1e-9;
    public static final int DEFAULT_MAX_BINS = 4096;
    private static final byte FORMAT_V1 = 1;

    private final double alpha;
    private final double gamma;
    private final double logGamma;
    private final int maxBins;
    private final TreeMap<Integer, Long> positive = new TreeMap<>();
    private final TreeMap<Integer, Long> negative = new TreeMap<>();
    private long zeroCount;
    private long count;

    public QuantileSketch() {
        this(DEFAULT_ALPHA);
    }

    public QuantileSketch(double alpha) {
        this(alpha, DEFAULT_MAX_BINS);
    }

    /**
     * @param maxBins upper bound per store; when exceeded, the bins of the smallest magnitudes are collapsed, which
     *                only degrades accuracy for values near zero
     */
    public QuantileSketch(double alpha, int maxBins) {
        if (!(alpha > 0 && alpha < 1)) {
            throw new IllegalArgumentException("alpha must be in (0, 1): " + alpha);
        }
        if (maxBins < 16) {
            throw new IllegalArgumentException("maxBins must be >= 16");
        }
        this.alpha = alpha;
        this.gamma = (1 + alpha) / (1 - alpha);
        this.logGamma = Math.log(gamma);
        this.maxBins = maxBins;
    }

    public double alpha() {
        return alpha;
    }

    public long count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void add(double value) {
        add(value, 1);
    }

    public void add(double value, long n) {
        if (n <= 0 || !Double.isFinite(value)) {
            return;
        }
        double abs = Math.abs(value);
        if (abs < MIN_INDEXABLE) {
            zeroCount += n;
        } else {
            TreeMap<Integer, Long> store = value > 0 ? positive : negative;
            store.merge(index(abs), n, Long::sum);
            collapse(store);
        }
        count += n;
    }

    public void merge(QuantileSketch other) {
        if (Double.compare(other.alpha, alpha) != 0) {
            throw new IllegalArgumentException("cannot merge sketches with different alpha: " + alpha + " vs " + other.alpha);
        }
        other.positive.forEach((k, v) -> positive.merge(k, v, Long::sum));
        other.negative.forEach((k, v) -> negative.merge(k, v, Long::sum));
        collapse(positive);
        collapse(negative);
        zeroCount += other.zeroCount;
        count += other.count;
    }

    public QuantileSketch copy() {
        QuantileSketch c = new QuantileSketch(alpha, maxBins);
        c.merge(this);
        return c;
    }

    /** Quantile {@code q} in [0, 1] (lower quantile, as DDSketch); NaN for an empty sketch. */
    public double quantile(double q) {
        if (q < 0 || q > 1 || Double.isNaN(q)) {
            throw new IllegalArgumentException("q must be in [0, 1]: " + q);
        }
        if (count == 0) {
            return Double.NaN;
        }
        double rank = q * (count - 1);
        long seen = 0;
        for (Map.Entry<Integer, Long> e : negative.descendingMap().entrySet()) {
            seen += e.getValue();
            if (seen > rank) {
                return -value(e.getKey());
            }
        }
        seen += zeroCount;
        if (seen > rank) {
            return 0.0;
        }
        for (Map.Entry<Integer, Long> e : positive.entrySet()) {
            seen += e.getValue();
            if (seen > rank) {
                return value(e.getKey());
            }
        }
        return value(positive.lastKey());
    }

    /**
     * Estimated fraction of values {@code < x} (values sharing x's bin count as not below). Used for
     * "share outside the plausible band" checks; the error is bounded by the mass of x's own bin.
     */
    public double fractionBelow(double x) {
        if (count == 0) {
            return Double.NaN;
        }
        long below = 0;
        if (x > 0) {
            below += negative.values().stream().mapToLong(Long::longValue).sum() + zeroCount;
            if (x >= MIN_INDEXABLE) {
                below += sum(positive.headMap(index(x), false));
            }
        } else if (x < 0) {
            double abs = -x;
            below += abs < MIN_INDEXABLE ? sum(negative) : sum(negative.tailMap(index(abs), false));
        } else {
            below += sum(negative);
        }
        return (double) below / count;
    }

    /** Estimated fraction of values {@code > x}. */
    public double fractionAbove(double x) {
        if (count == 0) {
            return Double.NaN;
        }
        long above = 0;
        if (x < 0) {
            above += sum(positive) + zeroCount;
            double abs = -x;
            if (abs >= MIN_INDEXABLE) {
                above += sum(negative.headMap(index(abs), false));
            }
        } else if (x > 0) {
            above += x < MIN_INDEXABLE ? sum(positive) : sum(positive.tailMap(index(x), false));
        } else {
            above += sum(positive);
        }
        return (double) above / count;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + 4 * (positive.size() + negative.size()));
        out.write(FORMAT_V1);
        byte[] a = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(alpha).array();
        out.write(a, 0, 8);
        Varints.write(out, zeroCount);
        writeStore(out, positive);
        writeStore(out, negative);
        return out.toByteArray();
    }

    public static QuantileSketch fromBytes(byte[] bytes) {
        return fromBytes(ByteBuffer.wrap(bytes));
    }

    public static QuantileSketch fromBytes(ByteBuffer buffer) {
        ByteBuffer in = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        byte version = in.get();
        if (version != FORMAT_V1) {
            throw new IllegalArgumentException("unsupported sketch format version " + version);
        }
        QuantileSketch s = new QuantileSketch(in.getDouble());
        s.zeroCount = Varints.read(in);
        s.count = s.zeroCount;
        s.count += readStore(in, s.positive);
        s.count += readStore(in, s.negative);
        return s;
    }

    private int index(double abs) {
        return (int) Math.ceil(Math.log(abs) / logGamma);
    }

    private double value(int index) {
        return 2 * Math.pow(gamma, index) / (gamma + 1);
    }

    private void collapse(TreeMap<Integer, Long> store) {
        while (store.size() > maxBins) {
            Map.Entry<Integer, Long> lowest = store.pollFirstEntry();
            store.merge(store.firstKey(), lowest.getValue(), Long::sum);
        }
    }

    private static long sum(NavigableMap<Integer, Long> bins) {
        long s = 0;
        for (long v : bins.values()) {
            s += v;
        }
        return s;
    }

    private static void writeStore(ByteArrayOutputStream out, TreeMap<Integer, Long> store) {
        Varints.write(out, store.size());
        long previous = 0;
        for (Map.Entry<Integer, Long> e : store.entrySet()) {
            Varints.writeZigZag(out, e.getKey() - previous);
            Varints.write(out, e.getValue());
            previous = e.getKey();
        }
    }

    private static long readStore(ByteBuffer in, TreeMap<Integer, Long> store) {
        long bins = Varints.read(in);
        long previous = 0;
        long total = 0;
        for (long i = 0; i < bins; i++) {
            long index = previous + Varints.readZigZag(in);
            long n = Varints.read(in);
            store.put(Math.toIntExact(index), n);
            total += n;
            previous = index;
        }
        return total;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QuantileSketch s && Double.compare(alpha, s.alpha) == 0 && zeroCount == s.zeroCount
                && positive.equals(s.positive) && negative.equals(s.negative);
    }

    @Override
    public int hashCode() {
        return positive.hashCode() * 31 + negative.hashCode() + Long.hashCode(zeroCount);
    }
}
