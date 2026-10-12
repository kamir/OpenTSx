package org.opentsx.windpark.catalog;

import java.time.ZoneId;
import java.util.List;

/** A source system (vendor SCADA, historian, simulator) with its time zone and channel mappings. */
public record SourceSystem(String system, String description, ZoneId timezone, List<SourceMapping> mappings) {

    public SourceSystem {
        timezone = timezone == null ? ZoneId.of("UTC") : timezone;
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}
