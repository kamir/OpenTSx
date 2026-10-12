package org.opentsx.windpark.normalize;

import java.util.Map;
import java.util.Set;

/**
 * What the normalizer did with a batch.
 *
 * @param unmapped     readings whose channel is neither mapped for the source system nor a canonical name
 * @param provisional  readings mapped through a PROVISIONAL mapping (to be confirmed with the vendor)
 * @param implausible  values outside the channel's plausibility band (kept, quality IMPLAUSIBLE)
 * @param missing      NaN values (quality MISSING)
 * @param duplicates   readings with a timestamp already seen for the same series (last one wins)
 */
public record NormalizationReport(String sourceSystem, String catalogVersion, long readings, long accepted,
                                  long unmapped, long unknownTurbine, long unitErrors, long converted,
                                  long provisional, long implausible, long missing, long duplicates, long episodes,
                                  Set<String> unmappedNames, Map<String, Long> conversions) {
}
