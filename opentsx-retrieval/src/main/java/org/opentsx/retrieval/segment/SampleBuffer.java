package org.opentsx.retrieval.segment;

import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The samples of one series in time order, addressed by absolute index (0 = first sample ever appended). Older
 * samples can be dropped ({@link #trimBefore}) so streaming segmentation runs in bounded memory.
 */
public final class SampleBuffer {

    private long base;
    private int size;
    private long[] ts = new long[1024];
    private double[] vs = new double[1024];
    private byte[] quality = new byte[1024];
    private Episode[] source = new Episode[1024];
    private boolean anyQuality;

    /** Appends an episode; samples not after the last one (overlap, replay) are skipped. Returns the samples added. */
    public int append(Episode e) {
        long[] t = Episodes.timestampsMicros(e);
        double[] v = Episodes.values(e);
        ByteBuffer q = e.getQuality();
        byte[] qb = null;
        if (q != null) {
            qb = new byte[q.remaining()];
            q.duplicate().get(qb);
            anyQuality = true;
        }
        int added = 0;
        for (int i = 0; i < t.length; i++) {
            if (size > 0 && t[i] <= ts[size - 1]) {
                continue;
            }
            ensure(size + 1);
            ts[size] = t[i];
            vs[size] = v[i];
            quality[size] = qb == null ? 0 : qb[i];
            source[size] = e;
            size++;
            added++;
        }
        return added;
    }

    /** First absolute index still held. */
    public long base() {
        return base;
    }

    /** One past the last absolute index. */
    public long end() {
        return base + size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public long ts(long i) {
        return ts[local(i)];
    }

    public double v(long i) {
        return vs[local(i)];
    }

    byte quality(long i) {
        return quality[local(i)];
    }

    Episode source(long i) {
        return source[local(i)];
    }

    boolean anyQuality() {
        return anyQuality;
    }

    public long lastTs() {
        return ts[size - 1];
    }

    /** Smallest absolute index in [from, end()) with ts >= t (end() if none). */
    public long indexAtOrAfter(long t, long from) {
        int lo = local(Math.max(from, base));
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return base + lo;
    }

    /** Drops samples with absolute index < i. */
    public void trimBefore(long i) {
        int n = (int) Math.min(Math.max(0, i - base), size);
        if (n == 0) {
            return;
        }
        System.arraycopy(ts, n, ts, 0, size - n);
        System.arraycopy(vs, n, vs, 0, size - n);
        System.arraycopy(quality, n, quality, 0, size - n);
        System.arraycopy(source, n, source, 0, size - n);
        Arrays.fill(source, size - n, size, null);
        size -= n;
        base += n;
    }

    private int local(long i) {
        long l = i - base;
        if (l < 0 || l >= size) {
            throw new IndexOutOfBoundsException("sample " + i + " not in [" + base + ", " + (base + size) + ")");
        }
        return (int) l;
    }

    private void ensure(int n) {
        if (n > ts.length) {
            int cap = Math.max(n, ts.length * 2);
            ts = Arrays.copyOf(ts, cap);
            vs = Arrays.copyOf(vs, cap);
            quality = Arrays.copyOf(quality, cap);
            source = Arrays.copyOf(source, cap);
        }
    }
}
