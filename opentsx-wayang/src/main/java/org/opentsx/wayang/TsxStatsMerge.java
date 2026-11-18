package org.opentsx.wayang;

import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsAccumulator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.TreeMap;

public final class TsxStatsMerge {

    public record Partial(String site, SeriesStats statistics) { }

    private TsxStatsMerge() { }

    public static SeriesStats merge(String groupId, String windowType, long startMicros, long endMicros,
                                    double alpha, Collection<Partial> partials) {
        if (groupId == null || !groupId.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("invalid aggregate group series id");
        if (!(alpha > 0 && alpha < 1)) throw new IllegalArgumentException("aggregate sketch alpha must be in (0, 1)");
        validateWindow(windowType, startMicros, endMicros);
        if (partials == null) throw new IllegalArgumentException("aggregate partials must not be null");
        TreeMap<String, SeriesStats> sorted = new TreeMap<>();
        long valid = 0;
        long nan = 0;
        long gaps = 0;
        for (Partial partial : partials) {
            if (partial == null || partial.site() == null || !partial.site().matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("invalid aggregate site id");
            }
            SeriesStats s = partial.statistics();
            validateRecord(s, groupId, windowType, startMicros, endMicros, alpha);
            if (sorted.putIfAbsent(partial.site(), s) != null) throw new IllegalArgumentException("duplicate aggregate site " + partial.site());
            try {
                valid = Math.addExact(valid, s.getValidCount());
                nan = Math.addExact(nan, s.getNanCount());
                gaps = Math.addExact(gaps, s.getGapCount());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("aggregate count overflow", e);
            }
        }
        StatsAccumulator merged = new StatsAccumulator(alpha);
        sorted.values().forEach(s -> merged.merge(StatsAccumulator.of(s)));
        if (valid > 0 && (!Double.isFinite(merged.mean()) || !Double.isFinite(merged.m2()))) {
            throw new IllegalArgumentException("aggregate moments overflow");
        }
        return merged.toSeriesStats(groupId, windowType, startMicros, endMicros);
    }

    private static void validateWindow(String windowType, long start, long end) {
        try {
            if (windowType == null || windowType.equals("GLOBAL")) throw new IllegalArgumentException("federated aggregate needs a window duration");
            Duration duration = Duration.parse(windowType);
            long nanos = duration.toNanos();
            if (nanos <= 0 || nanos % 1000 != 0) throw new IllegalArgumentException("aggregate duration must be positive whole microseconds");
            long micros = nanos / 1000;
            if (Math.floorMod(start, micros) != 0 || Math.addExact(start, micros) != end) {
                throw new IllegalArgumentException("aggregate window must follow the epoch grid");
            }
        } catch (ArithmeticException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("invalid aggregate window duration", e);
        }
    }

    private static void validateRecord(SeriesStats s, String groupId, String windowType, long start, long end, double alpha) {
        if (s == null) throw new IllegalArgumentException("aggregate statistics must not be null");
        if (!groupId.equals(s.getSeriesId()) || !windowType.equals(s.getWindowType())
                || s.getWindowStart() == null || s.getWindowEnd() == null
                || Micros.of(s.getWindowStart()) != start || Micros.of(s.getWindowEnd()) != end) {
            throw new IllegalArgumentException("aggregate group and window must match");
        }
        if (s.getValidCount() < 0 || s.getNanCount() < 0 || s.getGapCount() < 0) {
            throw new IllegalArgumentException("aggregate counts must be nonnegative");
        }
        if (s.getValidCount() > 0) {
            if (!Double.isFinite(s.getMin()) || !Double.isFinite(s.getMax()) || s.getMin() > s.getMax()
                    || !Double.isFinite(s.getMean()) || !Double.isFinite(s.getM2()) || s.getM2() < 0) {
                throw new IllegalArgumentException("aggregate moments must be finite and ordered");
            }
        } else if (!Double.isNaN(s.getMin()) || !Double.isNaN(s.getMax())
                || !Double.isNaN(s.getMean()) || !Double.isNaN(s.getM2())) {
            throw new IllegalArgumentException("empty aggregate moments must be NaN");
        }
        if (s.getValidCount() > 0 || s.getNanCount() > 0) {
            if (s.getLastSeen() == null || Micros.of(s.getLastSeen()) < start || Micros.of(s.getLastSeen()) >= end) {
                throw new IllegalArgumentException("aggregate lastSeen must be inside its window");
            }
        } else if (s.getLastSeen() != null) {
            throw new IllegalArgumentException("empty aggregate lastSeen must be null");
        }
        validateSketch(s.getQuantileSketch(), alpha, s.getValidCount());
    }

    private static void validateSketch(ByteBuffer encoded, double alpha, long expectedCount) {
        if (encoded == null) throw new IllegalArgumentException("aggregate sketch is required");
        ByteBuffer in = encoded.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        byte[] bytes = new byte[in.remaining()];
        in.duplicate().get(bytes);
        try {
            if (in.get() != 1 || Double.compare(in.getDouble(), alpha) != 0) {
                throw new IllegalArgumentException("aggregate sketch format and alpha must match");
            }
            long count = unsigned(in);
            for (int store = 0; store < 2; store++) {
                long bins = unsigned(in);
                if (bins > QuantileSketch.DEFAULT_MAX_BINS) throw new IllegalArgumentException("aggregate sketch exceeds catalog bin limit");
                long previous = 0;
                for (long i = 0; i < bins; i++) {
                    long zigzag = unsigned(in);
                    long index = Math.addExact(previous, (zigzag >>> 1) ^ -(zigzag & 1));
                    if (index < Integer.MIN_VALUE || index > Integer.MAX_VALUE || i > 0 && index <= previous) {
                        throw new IllegalArgumentException("aggregate sketch bins must be ordered integer indices");
                    }
                    long n = unsigned(in);
                    if (n == 0) throw new IllegalArgumentException("aggregate sketch bin counts must be positive");
                    count = Math.addExact(count, n);
                    previous = index;
                }
            }
            if (in.hasRemaining() || count != expectedCount) throw new IllegalArgumentException("aggregate sketch count and bytes must match");
            if (!Arrays.equals(bytes, QuantileSketch.fromBytes(bytes).toBytes())) {
                throw new IllegalArgumentException("aggregate sketch encoding must be canonical");
            }
        } catch (java.nio.BufferUnderflowException | ArithmeticException e) {
            throw new IllegalArgumentException("invalid aggregate sketch", e);
        }
    }

    private static long unsigned(ByteBuffer in) {
        long result = 0;
        for (int shift = 0; shift <= 56; shift += 7) {
            int b = in.get() & 255;
            result |= (long) (b & 127) << shift;
            if ((b & 128) == 0) return result;
        }
        throw new IllegalArgumentException("aggregate sketch integer exceeds signed 64-bit bounds");
    }
}
