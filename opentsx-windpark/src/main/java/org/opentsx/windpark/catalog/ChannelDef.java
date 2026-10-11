package org.opentsx.windpark.catalog;

import java.time.Duration;
import java.util.Map;

/**
 * One canonical data point of the catalog.
 *
 * @param id             catalog id, by convention the IEC reference {@code <LN>.<DataObject>}
 * @param component      physical component / sensor the value belongs to
 * @param dataType       FLOAT64, INT32, BOOLEAN, ENUM
 * @param resolution     sensor resolution; normalized values are quantized to it
 * @param min            lower plausibility bound (inclusive)
 * @param max            upper plausibility bound (inclusive)
 * @param sampling       native sampling interval of the instantaneous signal
 * @param statusCodes    meaning of codes for STATUS channels
 * @param vendorSpecific true for extensions or deviations from the standard (see notes)
 */
public record ChannelDef(String id, String metric, String component, String description, IecMapping iec,
                         String dataType, String unit, double resolution, double min, double max, ChannelKind kind,
                         Duration sampling, Map<Integer, String> statusCodes, boolean vendorSpecific, String notes) {

    public ChannelDef {
        statusCodes = statusCodes == null ? Map.of() : Map.copyOf(statusCodes);
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution must be > 0 for " + metric);
        }
        if (id == null || metric == null || iec == null || unit == null || kind == null) {
            throw new IllegalArgumentException("channel needs id, metric, iec, unit and kind: " + metric);
        }
    }

    /** IEC 61400-25 style reference, e.g. {@code WROT.RotSpd}. */
    public String iecReference() {
        return iec.logicalNode() + "." + iec.dataObject();
    }

    /** Rounds to the sensor resolution (e.g. 0.01 m/s); NaN stays NaN, -0.0 becomes 0.0. */
    public double quantize(double v) {
        if (Double.isNaN(v)) {
            return v;
        }
        int decimals = Math.max(0, (int) Math.ceil(-Math.log10(resolution) - 1e-9));
        double q = Math.round(v / resolution) * resolution;
        double f = Math.pow(10, decimals);
        return Math.round(q * f) / f + 0.0;
    }

    public boolean plausible(double v) {
        return !Double.isNaN(v) && v >= min && v <= max;
    }
}
