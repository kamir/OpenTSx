package org.opentsx.retrieval.segment;

import java.util.ArrayList;
import java.util.List;

/** Blocks of n samples, consecutive blocks overlapping by {@code overlap} samples; an incomplete tail is dropped. */
final class FixedCountDetector implements Detector {

    private final int n;
    private final int step;
    private long nextStart = -1;

    FixedCountDetector(int n, int overlap) {
        this.n = n;
        this.step = n - overlap;
    }

    @Override
    public List<Cut> onSample(SampleBuffer buf, long i) {
        if (nextStart < 0) {
            nextStart = i;
        }
        List<Cut> out = null;
        while (nextStart + n - 1 <= i) {
            if (out == null) {
                out = new ArrayList<>(1);
            }
            out.add(new Cut(nextStart, nextStart + n, -1));
            nextStart += step;
        }
        return out == null ? List.of() : out;
    }

    @Override
    public List<Cut> flush(SampleBuffer buf) {
        return List.of();
    }

    @Override
    public long earliestNeeded(SampleBuffer buf) {
        return nextStart < 0 ? buf.end() : nextStart;
    }
}
