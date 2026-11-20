package org.opentsx.flink.serdes;

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSchemaCompatibility;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.util.Vector;

/**
 * Serializer of a {@link TimeSeriesObject} for Flink state, network transfer and checkpoints.
 *
 * <pre>
 * [present (boolean)]                  false: a null series, nothing follows
 * [has label (boolean)] [label (UTF)]  the label only when present
 * [xValues size (int)] [size doubles]  0 for null or empty
 * [yValues size (int)] [size doubles]  0 for null or empty
 * [x format (UTF)] [y format (UTF)] [stat format (UTF)]   DecimalFormat patterns
 * </pre>
 *
 * About 16 bytes per point (two doubles) plus the label and the patterns. A null value in {@code xValues} or
 * {@code yValues} is refused with its index; NaN is written as it is.
 *
 * @see TimeSeriesObjectTypeInfo
 */
public class TimeSeriesObjectSerializer extends TypeSerializer<TimeSeriesObject> {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isImmutableType() {
        // TimeSeriesObject is mutable
        return false;
    }

    @Override
    public TypeSerializer<TimeSeriesObject> duplicate() {
        return new TimeSeriesObjectSerializer();
    }

    @Override
    public TimeSeriesObject createInstance() {
        return new TimeSeriesObject();
    }

    @Override
    public TimeSeriesObject copy(TimeSeriesObject from) {
        if (from == null) {
            return null;
        }

        TimeSeriesObject copy = new TimeSeriesObject();
        copy.setLabel(from.getLabel());

        // Deep copy vectors
        if (from.xValues != null) {
            copy.xValues = new Vector<Double>(from.xValues);
        }
        if (from.yValues != null) {
            copy.yValues = new Vector<Double>(from.yValues);
        }

        // Copy metadata
        copy.setDecimalFomrmatX(from.getDecimalFormat_X().toPattern());
        copy.setDecimalFomrmatY(from.getDecimalFormat_Y().toPattern());
        copy.getDecimalFormat_STAT().applyPattern(from.getDecimalFormat_STAT().toPattern());

        return copy;
    }

    @Override
    public TimeSeriesObject copy(TimeSeriesObject from, TimeSeriesObject reuse) {
        // For simplicity, we don't reuse objects
        return copy(from);
    }

    @Override
    public int getLength() {
        return -1; // Variable length
    }

    @Override
    public void serialize(TimeSeriesObject record, DataOutputView target) throws IOException {
        if (record == null) {
            target.writeBoolean(false);
            return;
        }

        target.writeBoolean(true);

        // Serialize label
        String label = record.getLabel();
        if (label != null) {
            target.writeBoolean(true);
            target.writeUTF(label);
        } else {
            target.writeBoolean(false);
        }

        writeValues(record.xValues, "xValues", target);
        writeValues(record.yValues, "yValues", target);

        // Copy metadata
        // We serialize the patterns of the DecimalFormats
        String dfx = record.getDecimalFormat_X().toPattern();
        String dfy = record.getDecimalFormat_Y().toPattern();
        String dfstat = record.getDecimalFormat_STAT().toPattern();

        target.writeUTF(dfx);
        target.writeUTF(dfy);
        target.writeUTF(dfstat);
    }

    private static void writeValues(Vector<?> values, String name, DataOutputView target) throws IOException {
        if (values == null) {
            target.writeInt(0);
            return;
        }
        target.writeInt(values.size());
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) {
                throw new IllegalArgumentException(name + "[" + i + "]: null; expected a number, NaN for a missing value");
            }
            target.writeDouble((Double) value);
        }
    }

    @Override
    public TimeSeriesObject deserialize(DataInputView source) throws IOException {
        boolean isNotNull = source.readBoolean();
        if (!isNotNull) {
            return null;
        }

        TimeSeriesObject ts = new TimeSeriesObject();

        // Deserialize label
        boolean hasLabel = source.readBoolean();
        if (hasLabel) {
            ts.setLabel(source.readUTF());
        }

        // Deserialize xValues
        int xSize = source.readInt();
        if (xSize > 0) {
            ts.xValues = new Vector<Double>(xSize);
            for (int i = 0; i < xSize; i++) {
                ts.xValues.add(source.readDouble());
            }
        }

        // Deserialize yValues
        int ySize = source.readInt();
        if (ySize > 0) {
            ts.yValues = new Vector<Double>(ySize);
            for (int i = 0; i < ySize; i++) {
                ts.yValues.add(source.readDouble());
            }
        }

        // Deserialize metadata
        String dfx = source.readUTF();
        String dfy = source.readUTF();
        String dfstat = source.readUTF();

        ts.setDecimalFomrmatX(dfx);
        ts.setDecimalFomrmatY(dfy);
        ts.getDecimalFormat_STAT().applyPattern(dfstat);

        return ts;
    }

    @Override
    public TimeSeriesObject deserialize(TimeSeriesObject reuse, DataInputView source) throws IOException {
        // For simplicity, we don't reuse objects
        return deserialize(source);
    }

    @Override
    public void copy(DataInputView source, DataOutputView target) throws IOException {
        boolean isNotNull = source.readBoolean();
        target.writeBoolean(isNotNull);

        if (!isNotNull) {
            return;
        }

        // Copy label
        boolean hasLabel = source.readBoolean();
        target.writeBoolean(hasLabel);
        if (hasLabel) {
            target.writeUTF(source.readUTF());
        }

        // Copy xValues
        int xSize = source.readInt();
        target.writeInt(xSize);
        for (int i = 0; i < xSize; i++) {
            target.writeDouble(source.readDouble());
        }

        // Copy yValues
        int ySize = source.readInt();
        target.writeInt(ySize);
        for (int i = 0; i < ySize; i++) {
            target.writeDouble(source.readDouble());
        }

        // Copy metadata
        target.writeUTF(source.readUTF()); // dfx
        target.writeUTF(source.readUTF()); // dfy
        target.writeUTF(source.readUTF()); // dfstat
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TimeSeriesObjectSerializer;
    }

    @Override
    public int hashCode() {
        return TimeSeriesObjectSerializer.class.hashCode();
    }

    @Override
    public TypeSerializerSnapshot<TimeSeriesObject> snapshotConfiguration() {
        return new TimeSeriesObjectSerializerSnapshot();
    }

    /**
     * Snapshot for serializer compatibility checking across Flink versions.
     */
    public static class TimeSeriesObjectSerializerSnapshot implements TypeSerializerSnapshot<TimeSeriesObject> {

        private static final int CURRENT_VERSION = 1;

        @Override
        public int getCurrentVersion() {
            return CURRENT_VERSION;
        }

        @Override
        public void writeSnapshot(DataOutputView out) throws IOException {
            // No configuration to write
        }

        @Override
        public void readSnapshot(int readVersion, DataInputView in, ClassLoader userCodeClassLoader)
                throws IOException {
            // No configuration to read
        }

        @Override
        public TypeSerializer<TimeSeriesObject> restoreSerializer() {
            return new TimeSeriesObjectSerializer();
        }

        @Override
        public TypeSerializerSchemaCompatibility<TimeSeriesObject> resolveSchemaCompatibility(
                TypeSerializer<TimeSeriesObject> newSerializer) {
            return TypeSerializerSchemaCompatibility.compatibleAsIs();
        }
    }
}
