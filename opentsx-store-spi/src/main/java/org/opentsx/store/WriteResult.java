package org.opentsx.store;

/**
 * @param written episodes stored
 * @param skipped episodes already present (same episodeId) - writes are idempotent
 * @param droppedPoints points the store cannot represent (e.g. NaN in a store without {@link Capability#NAN_VALUES})
 */
public record WriteResult(int written, int skipped, long droppedPoints) {
}
