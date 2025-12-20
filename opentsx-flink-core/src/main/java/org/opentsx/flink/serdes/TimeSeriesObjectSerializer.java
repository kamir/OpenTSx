package org.opentsx.flink.serdes;

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.util.Vector;

/**
 * Efficient serializer for OpenTSx {@link TimeSeriesObject} in Apache Flink.
 *
 * This serializer handles the conversion of TimeSeriesObject instances to/from binary format
 * for Flink's state backends, network transfers, and checkpointing.
 *
 * <h2>Serialization Format:</h2>
 * <pre>
 * [label (UTF-8 string)]
 * [description (UTF-8 string or null)]
 * [xValues.size (int)]
 * [xValues data (size * double)]
 * [yValues.size (int)]
 * [yValues data (size * double)]
 * [metadata fields...]
 * </pre>
 *
 * <h2>Performance Characteristics:</h2>
 * <ul>
 *   <li>Time Complexity: O(n) where n = number of data points</li>
 *   <li>Space: ~16 bytes per point (2 doubles) + metadata overhead</li>
 *   <li>Works efficiently with RocksDB for large state</li>
 * </ul>
 *
 * @see TimeSeriesObjectTypeInfo
 * @see TimeSeriesObject
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
            copy.xValues = new Vector<>(from.xValues);
        }
        if (from.yValues != null) {
            copy.yValues = new Vector<>(from.yValues);
        }

        // Copy metadata
        copy.decimalFomrat = from.decimalFomrat;

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

        // Serialize xValues
        if (record.xValues != null) {
            target.writeInt(record.xValues.size());
            for (Double val : record.xValues) {
                target.writeDouble(val != null ? val : 0.0);
            }
        } else {
            target.writeInt(0);
        }

        // Serialize yValues
        if (record.yValues != null) {
            target.writeInt(record.yValues.size());
            for (Double val : record.yValues) {
                target.writeDouble(val != null ? val : 0.0);
            }
        } else {
            target.writeInt(0);
        }

        // Serialize metadata
        target.writeInt(record.decimalFomrat);
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
            ts.xValues = new Vector<>(xSize);
            for (int i = 0; i < xSize; i++) {
                ts.xValues.add(source.readDouble());
            }
        }

        // Deserialize yValues
        int ySize = source.readInt();
        if (ySize > 0) {
            ts.yValues = new Vector<>(ySize);
            for (int i = 0; i < ySize; i++) {
                ts.yValues.add(source.readDouble());
            }
        }

        // Deserialize metadata
        ts.decimalFomrat = source.readInt();

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
        target.writeInt(source.readInt());
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
        public void readSnapshot(int readVersion, DataInputView in, ClassLoader userCodeClassLoader) throws IOException {
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
