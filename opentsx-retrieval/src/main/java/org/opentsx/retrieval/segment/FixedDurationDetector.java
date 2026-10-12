package org.opentsx.retrieval.segment;

import java.util.List;

/** Epoch-aligned windows of fixed duration (shifted by an offset); empty windows produce nothing. */
final class FixedDurationDetector implements Detector {

    private final long duration;
    private final long offset;
    private long windowStart = Long.MIN_VALUE;
    private long startIdx = -1;

    FixedDurationDetector(long durationMicros, long offsetMicros) {
        this.duration = durationMicros;
        this.offset = offsetMicros;
    }

    @Override
    public List<Cut> onSample(SampleBuffer buf, long i) {
        long t = buf.ts(i);
        long w = Math.floorDiv(t - offset, duration) * duration + offset;
        if (startIdx < 0) {
            windowStart = w;
            startIdx = i;
            return List.of();
        }
        if (w != windowStart) {
            Cut c = new Cut(startIdx, i, -1);
            windowStart = w;
            startIdx = i;
            return List.of(c);
        }
        return List.of();
    }

    @Override
    public List<Cut> flush(SampleBuffer buf) {
        if (startIdx < 0 || startIdx >= buf.end()) {
            return List.of();
        }
        Cut c = new Cut(startIdx, buf.end(), -1);
        startIdx = -1;
        return List.of(c);
    }

    @Override
    public long earliestNeeded(SampleBuffer buf) {
        return startIdx < 0 ? buf.end() : startIdx;
    }

    long windowEnd() {
        return windowStart + duration;
    }
}
