package org.opentsx.retrieval.segment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Windows around local extrema: a sample is a peak if it is a local maximum (minimum for MIN), its prominence within
 * ±window/2 is at least {@code prominence}, and it is the highest (lowest) value within ±distance (ties: the earliest).
 * The segment covers [t - pre, t + post]. A peak is decided once the series has reached t + max(post, distance,
 * window/2), so streaming and batch agree.
 */
final class ExtremumDetector implements Detector {

    private final double sign;
    private final double prominence;
    private final long halfWindow;
    private final long distance;
    private final long pre;
    private final long post;
    private final long lookahead;
    private final long lookback;
    private final Deque<Long> candidates = new ArrayDeque<>();

    ExtremumDetector(boolean max, double prominence, long window, long distance, long pre, long post) {
        this.sign = max ? 1 : -1;
        this.prominence = prominence;
        this.halfWindow = window / 2;
        this.distance = distance;
        this.pre = pre;
        this.post = post;
        this.lookahead = Math.max(post, Math.max(distance, halfWindow));
        this.lookback = Math.max(pre, Math.max(distance, halfWindow));
    }

    private double val(SampleBuffer b, long i) {
        double v = b.v(i);
        return Double.isNaN(v) ? Double.NEGATIVE_INFINITY : sign * v;
    }

    @Override
    public List<Cut> onSample(SampleBuffer buf, long i) {
        long c = i - 1;
        if (c - 1 >= buf.base()) {
            double vc = val(buf, c);
            if (vc != Double.NEGATIVE_INFINITY && vc > val(buf, c - 1) && vc >= val(buf, i)) {
                candidates.addLast(c);
            }
        }
        List<Cut> out = null;
        long now = buf.ts(i);
        while (!candidates.isEmpty() && now >= buf.ts(candidates.peekFirst()) + lookahead) {
            Cut cut = decide(buf, candidates.pollFirst());
            if (cut != null) {
                if (out == null) {
                    out = new ArrayList<>(1);
                }
                out.add(cut);
            }
        }
        return out == null ? List.of() : out;
    }

    @Override
    public List<Cut> flush(SampleBuffer buf) {
        List<Cut> out = new ArrayList<>();
        while (!candidates.isEmpty()) {
            Cut cut = decide(buf, candidates.pollFirst());
            if (cut != null) {
                out.add(cut);
            }
        }
        return out;
    }

    private Cut decide(SampleBuffer b, long c) {
        long t = b.ts(c);
        double vc = val(b, c);
        // prominence within +-window/2: lowest point on each side before a higher sample
        double leftMin = vc;
        for (long j = c - 1; j >= b.base() && b.ts(j) >= t - halfWindow; j--) {
            double v = val(b, j);
            if (v > vc) {
                break;
            }
            if (v != Double.NEGATIVE_INFINITY) {
                leftMin = Math.min(leftMin, v);
            }
        }
        double rightMin = vc;
        for (long j = c + 1; j < b.end() && b.ts(j) <= t + halfWindow; j++) {
            double v = val(b, j);
            if (v > vc) {
                break;
            }
            if (v != Double.NEGATIVE_INFINITY) {
                rightMin = Math.min(rightMin, v);
            }
        }
        if (vc - Math.max(leftMin, rightMin) < prominence) {
            return null;
        }
        // highest within +-distance (earliest wins ties)
        for (long j = b.indexAtOrAfter(t - distance, b.base()); j < b.end() && b.ts(j) <= t + distance; j++) {
            double v = val(b, j);
            if (j != c && (v > vc || (v == vc && j < c))) {
                return null;
            }
        }
        long from = b.indexAtOrAfter(t - pre, b.base());
        long to = b.indexAtOrAfter(t + post + 1, c);
        return new Cut(from, to, c);
    }

    @Override
    public long earliestNeeded(SampleBuffer buf) {
        if (buf.isEmpty()) {
            return buf.end();
        }
        long need = buf.indexAtOrAfter(buf.lastTs() - lookback, buf.base()) - 2;
        if (!candidates.isEmpty()) {
            need = Math.min(need, buf.indexAtOrAfter(buf.ts(candidates.peekFirst()) - lookback, buf.base()));
        }
        return Math.max(buf.base(), need);
    }
}
