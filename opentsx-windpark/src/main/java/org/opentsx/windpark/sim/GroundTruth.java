package org.opentsx.windpark.sim;

import java.util.Map;

/**
 * An event the simulator injected - the reference for evaluating data-quality checks and pattern search.
 *
 * @param category "operation" or "sensor-fault"
 * @param channel  metric for sensor faults, null for operations (whole turbine)
 */
public record GroundTruth(String category, String type, String turbine, String channel, long fromMicros, long toMicros,
                          Map<String, String> params) {

    public boolean overlaps(long from, long to) {
        return fromMicros < to && toMicros > from;
    }
}
