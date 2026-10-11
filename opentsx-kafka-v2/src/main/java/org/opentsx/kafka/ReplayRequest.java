package org.opentsx.kafka;

import java.util.Set;

/**
 * What to replay: a whole bucket, or episodes of some series overlapping a time range.
 *
 * @param speed 0 = as fast as possible; otherwise event-time speed-up (1.0 = real time, 60.0 = one hour per minute)
 */
public record ReplayRequest(String bucketId, Set<String> seriesIds, long fromMicros, long toMicros, double speed,
                            int maxEpisodesInMemory) {

    public static ReplayRequest bucket(String bucketId) {
        return new ReplayRequest(bucketId, null, Long.MIN_VALUE, Long.MAX_VALUE, 0, 10_000);
    }

    /** {@code seriesIds == null} selects all series. */
    public static ReplayRequest range(Set<String> seriesIds, long fromMicros, long toMicros) {
        return new ReplayRequest(null, seriesIds, fromMicros, toMicros, 0, 10_000);
    }

    public ReplayRequest withSpeed(double speed) {
        if (speed < 0) {
            throw new IllegalArgumentException("speed must be >= 0");
        }
        return new ReplayRequest(bucketId, seriesIds, fromMicros, toMicros, speed, maxEpisodesInMemory);
    }

    public ReplayRequest withMaxEpisodesInMemory(int max) {
        return new ReplayRequest(bucketId, seriesIds, fromMicros, toMicros, speed, Math.max(1, max));
    }
}
