package org.opentsx.modbus.normalize;

import java.util.Set;

/**
 * What the normalizer did with a batch.
 *
 * @param unknownPoints readings whose point is not in the register map (dropped)
 * @param missing       failed reads (NaN, quality MISSING)
 * @param implausible   values outside min/max (kept, quality IMPLAUSIBLE)
 * @param counterWraps  counter overflows (quality COUNTER_WRAP)
 * @param counterResets counter resets (quality COUNTER_RESET)
 * @param duplicates    readings with a timestamp already seen for the same series (last one wins)
 */
public record NormalizationReport(String device, String registerMap, long readings, long accepted, long unknownPoints,
                                  long missing, long implausible, long counterWraps, long counterResets,
                                  long duplicates, long episodes, Set<String> unknownNames) {
}
