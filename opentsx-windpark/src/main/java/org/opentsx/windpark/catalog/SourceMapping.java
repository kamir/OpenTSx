package org.opentsx.windpark.catalog;

import java.time.Duration;

/**
 * How one channel of a source system maps onto a catalog channel.
 *
 * <p>Conversion order: {@code value * scale + offset} (in the source unit), sign inversion, unit conversion to the
 * catalog unit, quantization. Timestamps referring to the END of an aggregation interval are moved to its start
 * (OpenTSx convention: an interval is stamped with its start, like episode tStart).
 *
 * @param sourceName    original name in the source system (CSV column, OPC UA browse name, tag name ...)
 * @param channel       catalog channel id, e.g. {@code WMET.HorWdSpd}
 * @param aggregation   null for instantaneous values
 * @param timestamp     INSTANT, START or END (of the aggregation interval)
 * @param sourceAddress where the value is read technically (e.g. {@code opcua:ns=2;s=WTG07.WindSpeed})
 * @param status        CONFIRMED, PROVISIONAL or UNMAPPED
 */
public record SourceMapping(String sourceName, String channel, String sourceUnit, Double scale, Double offset,
                            boolean invertSign, Aggregation aggregation, TimestampReference timestamp,
                            String sourceAddress, MappingStatus status, String notes) {

    public SourceMapping {
        scale = scale == null ? 1.0 : scale;
        offset = offset == null ? 0.0 : offset;
        timestamp = timestamp == null ? (aggregation == null ? TimestampReference.INSTANT : TimestampReference.START) : timestamp;
        status = status == null ? MappingStatus.PROVISIONAL : status;
        if (aggregation != null && timestamp == TimestampReference.INSTANT) {
            throw new IllegalArgumentException(sourceName + ": aggregated values need timestamp START or END");
        }
    }

    public enum TimestampReference { INSTANT, START, END }

    public enum MappingStatus { CONFIRMED, PROVISIONAL, UNMAPPED }

    public enum AggregationFunction { MEAN, MIN, MAX, STD, SUM, LAST }

    public record Aggregation(AggregationFunction function, Duration interval) {

        /** Tag value distinguishing aggregate series from raw ones, e.g. {@code mean.PT10M}. */
        public String tag() {
            return function.name().toLowerCase(java.util.Locale.ROOT) + "." + interval;
        }
    }
}
