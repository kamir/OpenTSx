package org.opentsx.model;

import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Episode time axis.
 *
 * <ul>
 *   <li>{@code REGULAR}: {@code t_i = tStart + i * intervalMicros}; no per-value timestamps are stored.</li>
 *   <li>{@code IRREGULAR_DELTA}: {@code t_0 = tStart}; {@code timeDeltas} holds {@code count - 1} zig-zag varints
 *   (Avro long encoding): the first delta {@code t_1 - t_0}, then delta-of-delta {@code d_i - d_(i-1)}.
 *   Timestamps must be strictly increasing.</li>
 * </ul>
 */
public final class TimeAxis {

    private TimeAxis() {
    }

    public static byte[] encodeDeltas(long[] timestampsMicros) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1, timestampsMicros.length));
        long previousDelta = 0;
        for (int i = 1; i < timestampsMicros.length; i++) {
            long delta = Math.subtractExact(timestampsMicros[i], timestampsMicros[i - 1]);
            if (delta <= 0) {
                throw new IllegalArgumentException("timestamps must be strictly increasing at index " + i);
            }
            writeZigZag(out, i == 1 ? delta : delta - previousDelta);
            previousDelta = delta;
        }
        return out.toByteArray();
    }

    public static long[] decodeDeltas(long tStartMicros, int count, ByteBuffer deltas) {
        long[] ts = new long[count];
        if (count == 0) {
            return ts;
        }
        ByteBuffer in = deltas.duplicate();
        ts[0] = tStartMicros;
        long delta = 0;
        for (int i = 1; i < count; i++) {
            long v = readZigZag(in);
            delta = i == 1 ? v : delta + v;
            ts[i] = ts[i - 1] + delta;
        }
        if (in.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes in timeDeltas");
        }
        return ts;
    }

    /** Timestamps of all values of an episode in epoch microseconds. */
    public static long[] timestampsMicros(Episode episode) {
        long tStart = Micros.of(episode.getTStart());
        int n = episode.getCount();
        if (episode.getTimeEncoding() == TimeEncoding.REGULAR) {
            long interval = episode.getIntervalMicros();
            long[] ts = new long[n];
            for (int i = 0; i < n; i++) {
                ts[i] = tStart + i * interval;
            }
            return ts;
        }
        return decodeDeltas(tStart, n, episode.getTimeDeltas());
    }

    static void writeZigZag(ByteArrayOutputStream out, long v) {
        long z = (v << 1) ^ (v >> 63);
        while ((z & ~0x7FL) != 0) {
            out.write((int) ((z & 0x7F) | 0x80));
            z >>>= 7;
        }
        out.write((int) z);
    }

    static long readZigZag(ByteBuffer in) {
        long z = 0;
        int shift = 0;
        while (true) {
            if (!in.hasRemaining()) {
                throw new IllegalArgumentException("truncated varint in timeDeltas");
            }
            int b = in.get() & 0xFF;
            z |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("varint too long in timeDeltas");
            }
        }
        return (z >>> 1) ^ -(z & 1);
    }
}
