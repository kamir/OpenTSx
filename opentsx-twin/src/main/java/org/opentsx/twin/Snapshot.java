package org.opentsx.twin;

import java.util.Map;

/**
 * Everything needed to continue a run at superstep {@code k} (time {@code tMicros}): atom states, the last outputs
 * (for delayed wires and held multirate atoms) and the interventions in force.
 */
public record Snapshot(String sourceRunId, int k, long tMicros, Map<String, Map<String, Double>> states,
                       Map<String, Map<String, Value>> outputs, Map<String, Map<String, Double>> overrides) {
}
