package org.opentsx.twin;

import java.util.Map;

/**
 * Something an atom reports, with the exact time it happened (may lie inside a superstep, e.g. a threshold
 * crossing) and the superstep {@code k} that detected it.
 */
public record TwinEvent(long tMicros, int k, String atom, String type, Map<String, String> attrs) {

    public TwinEvent {
        attrs = Map.copyOf(attrs);
    }
}
