package org.opentsx.store;

/** What a store preserves or offers; contract tests and callers check these instead of store types. */
public enum Capability {
    /** Keeps episodes as written: ids, boundaries, labels, segmentation, provenance (exact round trip). */
    EPISODES,
    /** Stores individual points; episodes are reconstructed on read. */
    RAW_POINTS,
    /** Timestamps keep microsecond precision (otherwise milliseconds). */
    MICROSECOND_PRECISION,
    /** NaN values are stored (otherwise dropped on write). */
    NAN_VALUES,
    /** Server-side downsampling/aggregation. */
    DOWNSAMPLING,
    /** SQL access for analytics engines (Spark, Trino, PyIceberg). */
    SQL,
    /** Reads as of an earlier snapshot. */
    TIME_TRAVEL
}
