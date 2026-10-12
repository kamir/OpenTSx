package org.opentsx.retrieval.pattern;

/** Distance measure of a pattern search; both work on z-normalized subsequences. */
public record Distance(Kind kind, double band) {

    public enum Kind { Z_EUCLIDEAN, DTW }

    public static Distance zNormalizedEuclidean() {
        return new Distance(Kind.Z_EUCLIDEAN, 0);
    }

    /** DTW with a Sakoe-Chiba band of {@code band} x query length (e.g. 0.1). */
    public static Distance dtw(double band) {
        if (band < 0 || band > 1) {
            throw new IllegalArgumentException("band must be in [0, 1]");
        }
        return new Distance(Kind.DTW, band);
    }

    /** Name used in {@code PatternMatch.metric}. */
    public String label() {
        return kind == Kind.Z_EUCLIDEAN ? "zED" : "DTW(w=" + band + ")";
    }
}
