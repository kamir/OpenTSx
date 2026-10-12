package org.opentsx.twin;

/**
 * Barrier record. {@code phase != null}: a phase of superstep {@code k} is complete with {@code count} messages.
 * {@code phase == null}: superstep {@code k} is committed; {@code stateHash} chains all states and outputs so far.
 */
public record Barrier(String runId, int k, Phase phase, int count, String stateHash) {

    public boolean isCommit() {
        return phase == null;
    }
}
