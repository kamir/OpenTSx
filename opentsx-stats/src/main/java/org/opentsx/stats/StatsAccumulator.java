package org.opentsx.stats;

import org.opentsx.model.Micros;
import org.opentsx.model.v2.EpisodeSummary;
import org.opentsx.model.v2.SeriesStats;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Mutable, mergeable statistics of one series in one window: Welford mean/M2, min/max, NaN count, gaps, first/last
 * timestamp and (optionally) a {@link QuantileSketch}. Merging follows Chan et al. and is exact up to rounding, so
 * accumulators of turbines, partitions or windows can be combined.
 */
public final class StatsAccumulator {

    private long validCount;
    private long nanCount;
    private double min = Double.NaN;
    private double max = Double.NaN;
    private double mean;
    private double m2;
    private long gapCount;
    private long firstMicros = Long.MAX_VALUE;
    private long lastMicros = Long.MIN_VALUE;
    private final QuantileSketch sketch;

    /** @param sketchAlpha relative accuracy of the quantile sketch, or {@code <= 0} for no sketch */
    public StatsAccumulator(double sketchAlpha) {
        this.sketch = sketchAlpha > 0 ? new QuantileSketch(sketchAlpha) : null;
    }

    private StatsAccumulator(QuantileSketch sketch) {
        this.sketch = sketch;
    }

    /** Adds one value at {@code tsMicros}; NaN/infinite values are counted as {@code nanCount}. */
    public void add(long tsMicros, double v) {
        firstMicros = Math.min(firstMicros, tsMicros);
        lastMicros = Math.max(lastMicros, tsMicros);
        if (!Double.isFinite(v)) {
            nanCount++;
            return;
        }
        validCount++;
        double delta = v - mean;
        mean += delta / validCount;
        m2 += delta * (v - mean);
        min = validCount == 1 ? v : Math.min(min, v);
        max = validCount == 1 ? v : Math.max(max, v);
        if (sketch != null) {
            sketch.add(v);
        }
    }

    public void addGap() {
        gapCount++;
    }

    public void merge(StatsAccumulator o) {
        if (o.validCount > 0) {
            if (validCount == 0) {
                mean = o.mean;
                m2 = o.m2;
                min = o.min;
                max = o.max;
            } else {
                long n = validCount + o.validCount;
                double delta = o.mean - mean;
                mean += delta * o.validCount / n;
                m2 += o.m2 + delta * delta * ((double) validCount * o.validCount / n);
                min = Math.min(min, o.min);
                max = Math.max(max, o.max);
            }
            validCount += o.validCount;
        }
        nanCount += o.nanCount;
        gapCount += o.gapCount;
        firstMicros = Math.min(firstMicros, o.firstMicros);
        lastMicros = Math.max(lastMicros, o.lastMicros);
        if (sketch != null && o.sketch != null) {
            sketch.merge(o.sketch);
        }
    }

    public long validCount() {
        return validCount;
    }

    public long nanCount() {
        return nanCount;
    }

    public long gapCount() {
        return gapCount;
    }

    public double min() {
        return validCount == 0 ? Double.NaN : min;
    }

    public double max() {
        return validCount == 0 ? Double.NaN : max;
    }

    public double mean() {
        return validCount == 0 ? Double.NaN : mean;
    }

    public double m2() {
        return validCount == 0 ? Double.NaN : m2;
    }

    /** Sample variance; NaN for fewer than two values. */
    public double variance() {
        return validCount < 2 ? Double.NaN : m2 / (validCount - 1);
    }

    public double stddev() {
        return Math.sqrt(variance());
    }

    public long firstMicros() {
        return firstMicros;
    }

    public long lastMicros() {
        return lastMicros;
    }

    public boolean isEmpty() {
        return validCount == 0 && nanCount == 0;
    }

    /** The sketch, or null if this accumulator has none. */
    public QuantileSketch sketch() {
        return sketch;
    }

    public SeriesStats toSeriesStats(String seriesId, String windowType, long windowStartMicros, long windowEndMicros) {
        return SeriesStats.newBuilder()
                .setSeriesId(seriesId)
                .setWindowType(windowType)
                .setWindowStart(Micros.toInstant(windowStartMicros))
                .setWindowEnd(Micros.toInstant(windowEndMicros))
                .setValidCount(validCount)
                .setNanCount(nanCount)
                .setMin(min())
                .setMax(max())
                .setMean(mean())
                .setM2(m2())
                .setQuantileSketch(sketch == null ? null : ByteBuffer.wrap(sketch.toBytes()))
                .setGapCount(gapCount)
                .setLastSeen(lastMicros == Long.MIN_VALUE ? null
                        : Micros.toInstant(lastMicros))
                .build();
    }

    /** Rebuilds an accumulator from a record, e.g. to merge the stats of several turbines of one channel. */
    public static StatsAccumulator of(SeriesStats s) {
        QuantileSketch sketch = s.getQuantileSketch() == null ? null : QuantileSketch.fromBytes(s.getQuantileSketch());
        StatsAccumulator a = new StatsAccumulator(sketch);
        a.validCount = s.getValidCount();
        a.nanCount = s.getNanCount();
        a.min = s.getMin();
        a.max = s.getMax();
        a.mean = s.getValidCount() == 0 ? 0 : s.getMean();
        a.m2 = s.getValidCount() == 0 ? 0 : s.getM2();
        a.gapCount = s.getGapCount();
        a.firstMicros = Micros.of(s.getWindowStart());
        a.lastMicros = s.getLastSeen() == null ? Long.MIN_VALUE : Micros.of(s.getLastSeen());
        return a;
    }

    /** Accumulator with the exact moments of an episode summary (no sketch, no timestamps). */
    public static StatsAccumulator of(EpisodeSummary s, long tStartMicros, long lastMicros) {
        StatsAccumulator a = new StatsAccumulator(null);
        a.validCount = s.getValidCount();
        a.nanCount = s.getNanCount();
        a.min = s.getMin();
        a.max = s.getMax();
        a.mean = s.getValidCount() == 0 ? 0 : s.getMean();
        a.m2 = s.getValidCount() == 0 ? 0 : s.getM2();
        a.firstMicros = tStartMicros;
        a.lastMicros = lastMicros;
        return a;
    }

    /** Empty accumulator with a sketch of the same accuracy (or none). */
    public StatsAccumulator emptyLike() {
        return new StatsAccumulator(sketch == null ? null : new QuantileSketch(sketch.alpha()));
    }

    /** Writes the full state (for processor checkpoints). */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeLong(validCount);
        out.writeLong(nanCount);
        out.writeDouble(min);
        out.writeDouble(max);
        out.writeDouble(mean);
        out.writeDouble(m2);
        out.writeLong(gapCount);
        out.writeLong(firstMicros);
        out.writeLong(lastMicros);
        if (sketch == null) {
            out.writeInt(-1);
        } else {
            byte[] b = sketch.toBytes();
            out.writeInt(b.length);
            out.write(b);
        }
    }

    public static StatsAccumulator readFrom(DataInputStream in) throws IOException {
        long valid = in.readLong();
        long nan = in.readLong();
        double mn = in.readDouble();
        double mx = in.readDouble();
        double me = in.readDouble();
        double mm = in.readDouble();
        long gaps = in.readLong();
        long first = in.readLong();
        long last = in.readLong();
        int len = in.readInt();
        QuantileSketch sk = null;
        if (len >= 0) {
            byte[] b = new byte[len];
            in.readFully(b);
            sk = QuantileSketch.fromBytes(b);
        }
        StatsAccumulator a = new StatsAccumulator(sk);
        a.validCount = valid;
        a.nanCount = nan;
        a.min = mn;
        a.max = mx;
        a.mean = me;
        a.m2 = mm;
        a.gapCount = gaps;
        a.firstMicros = first;
        a.lastMicros = last;
        return a;
    }
}
