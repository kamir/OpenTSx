package org.opentsx.kafka;

/**
 * Outcome of a replay.
 *
 * @param verified for bucket replays: the bucket is SEALED and delivered count and checksum match its manifest;
 *                 always false for range replays
 * @param missing  index entries whose record was not found where the index pointed (stale index)
 */
public record ReplayResult(long requested, long delivered, long missing, String checksum, boolean verified) {
}
