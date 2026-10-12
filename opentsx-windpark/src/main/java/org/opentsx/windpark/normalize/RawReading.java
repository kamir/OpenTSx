package org.opentsx.windpark.normalize;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** One reading as it arrives from SCADA, a CSV export or a historian: vendor channel name, local unit. */
public record RawReading(String turbineId, String channel, Instant timestamp, double value, String unit) {

    /** For sources that export local wall-clock time. */
    public static RawReading local(String turbineId, String channel, LocalDateTime localTime, ZoneId zone,
                                   double value, String unit) {
        return new RawReading(turbineId, channel, localTime.atZone(zone).toInstant(), value, unit);
    }
}
