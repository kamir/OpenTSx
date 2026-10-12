package org.opentsx.retrieval.segment;

/** A segment found by a detector: samples [from, to) by absolute index; anchor index or -1. */
public record Cut(long from, long to, long anchor) {

    public Cut {
        if (to <= from) {
            throw new IllegalArgumentException("empty cut [" + from + ", " + to + ")");
        }
    }
}
