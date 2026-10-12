package org.opentsx.windpark.sim;

import java.util.List;
import java.util.Map;

/**
 * @param energyKwh        produced energy per turbine
 * @param meanWindMs       mean effective (waked) wind speed per turbine
 * @param stateSeconds     seconds per operating state code per turbine
 */
public record SimulationReport(String scenario, long episodes, long values, List<GroundTruth> groundTruth,
                               Map<String, Double> energyKwh, Map<String, Double> meanWindMs,
                               Map<String, Map<Integer, Long>> stateSeconds) {
}
