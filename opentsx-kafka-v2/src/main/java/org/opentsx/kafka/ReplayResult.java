package org.opentsx.kafka;

/**
 * Outcome of a replay.
 *
 * @param checksum {@link org.opentsx.model.Buckets#contentChecksum} of the delivered episodes; for a bucket whose
 *                 manifest holds an id-only checksum (no {@code sha256:} prefix), {@link org.opentsx.model.Buckets#checksum}
 *                 of the delivered episode ids
 * @param verified for bucket replays: the bucket is SEALED and delivered count and checksum match its manifest. With a
 *                 content checksum this covers the episode contents, with an id-only checksum the episode ids only.
 *                 Always false for range replays
 * @param missing  index entries whose record was not found where the index pointed (stale index)
 */
public record ReplayResult(long requested, long delivered, long missing, String checksum, boolean verified) {
}
