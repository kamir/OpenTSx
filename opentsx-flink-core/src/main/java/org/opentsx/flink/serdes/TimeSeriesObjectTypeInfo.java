package org.opentsx.flink.serdes;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.opentsx.data.series.TimeSeriesObject;

/**
 * TypeInformation for TimeSeriesObject to help Flink's type system.
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
        return false;
    }

    @Override
    public org.apache.flink.api.common.typeutils.TypeSerializer<TimeSeriesObject> createSerializer(
            org.apache.flink.api.common.ExecutionConfig config) {
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
