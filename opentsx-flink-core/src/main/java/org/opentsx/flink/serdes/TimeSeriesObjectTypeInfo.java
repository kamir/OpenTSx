package org.opentsx.flink.serdes;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.opentsx.data.series.TimeSeriesObject;

/**
 * Type information for OpenTSx {@link TimeSeriesObject} in Apache Flink.
 *
 * This class tells Flink how to serialize and deserialize TimeSeriesObject instances
 * efficiently in the Flink runtime, including state backends and network transfers.
 *
 * <h2>Why Custom TypeInformation?</h2>
 * TimeSeriesObject uses {@link java.util.Vector} for data storage, which is not
 * optimally handled by Flink's default serialization. This custom type information
 * provides:
 * <ul>
 *   <li>Efficient serialization of large time series (millions of points)</li>
 *   <li>Support for RocksDB state backend</li>
 *   <li>Optimized network transfers between Flink operators</li>
 *   <li>Proper handling of metadata (label, description, etc.)</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * DataStream<TimeSeriesObject> timeSeries = observations
 *     .keyBy(Observation::getLabel)
 *     .window(TumblingEventTimeWindows.of(Time.minutes(5)))
 *     .aggregate(new TimeSeriesAggregateFunction())
 *     .returns(new TimeSeriesObjectTypeInfo());
 * }</pre>
 *
 * @see TimeSeriesObjectSerializer
 * @see TimeSeriesObject
 */
public class TimeSeriesObjectTypeInfo extends TypeInformation<TimeSeriesObject> {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isBasicType() {
        return false;
    }

    @Override
    public boolean isTupleType() {
        return false;
    }

    @Override
    public int getArity() {
        return 1;
    }

    @Override
    public int getTotalFields() {
        return 1;
    }

    @Override
    public Class<TimeSeriesObject> getTypeClass() {
        return TimeSeriesObject.class;
    }

    @Override
    public boolean isKeyType() {
        // TimeSeriesObject can be used as a key (hashCode/equals implemented)
        return false;
    }

    @Override
    public TypeSerializer<TimeSeriesObject> createSerializer(ExecutionConfig config) {
        return new TimeSeriesObjectSerializer();
    }

    @Override
    public String toString() {
        return "TimeSeriesObjectTypeInfo";
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TimeSeriesObjectTypeInfo;
    }

    @Override
    public int hashCode() {
        return TimeSeriesObjectTypeInfo.class.hashCode();
    }

    @Override
    public boolean canEqual(Object obj) {
        return obj instanceof TimeSeriesObjectTypeInfo;
    }
}
