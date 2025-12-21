package org.opentsx.flink.serdes;

import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TimeSeriesObjectSerializer}.
 *
 * These tests verify correct serialization and deserialization of TimeSeriesObject instances,
 * including edge cases like null values, empty series, and large datasets.
 */
public class TimeSeriesObjectSerializerTest {

    private final TimeSeriesObjectSerializer serializer = new TimeSeriesObjectSerializer();

    @Test
    public void testSerializeDeserialize_SimpleTimeSeries() throws Exception {
        // Create a simple time series
        TimeSeriesObject original = new TimeSeriesObject();
        original.setLabel("test-series");
        original.addValuePair(1.0, 100.0);
        original.addValuePair(2.0, 200.0);
        original.addValuePair(3.0, 300.0);

        // Serialize
        DataOutputSerializer out = new DataOutputSerializer(1024);
        serializer.serialize(original, out);

        // Deserialize
        DataInputDeserializer in = new DataInputDeserializer(out.getCopyOfBuffer());
        TimeSeriesObject deserialized = serializer.deserialize(in);

        // Verify
        assertNotNull(deserialized);
        assertEquals(original.getLabel(), deserialized.getLabel());
        assertEquals(original.xValues.size(), deserialized.xValues.size());
        assertEquals(original.yValues.size(), deserialized.yValues.size());

        for (int i = 0; i < original.xValues.size(); i++) {
            assertEquals(original.xValues.get(i), deserialized.xValues.get(i), 0.0001);
            assertEquals(original.yValues.get(i), deserialized.yValues.get(i), 0.0001);
        }
    }

    @Test
    public void testSerializeDeserialize_EmptyTimeSeries() throws Exception {
        TimeSeriesObject original = new TimeSeriesObject();
        original.setLabel("empty-series");

        DataOutputSerializer out = new DataOutputSerializer(256);
        serializer.serialize(original, out);

        DataInputDeserializer in = new DataInputDeserializer(out.getCopyOfBuffer());
        TimeSeriesObject deserialized = serializer.deserialize(in);

        assertNotNull(deserialized);
        assertEquals(original.getLabel(), deserialized.getLabel());
        assertTrue(deserialized.xValues == null || deserialized.xValues.isEmpty());
        assertTrue(deserialized.yValues == null || deserialized.yValues.isEmpty());
    }

    @Test
    public void testSerializeDeserialize_NullTimeSeries() throws Exception {
        DataOutputSerializer out = new DataOutputSerializer(256);
        serializer.serialize(null, out);

        DataInputDeserializer in = new DataInputDeserializer(out.getCopyOfBuffer());
        TimeSeriesObject deserialized = serializer.deserialize(in);

        assertNull(deserialized);
    }

    @Test
    public void testSerializeDeserialize_LargeTimeSeries() throws Exception {
        // Create a large time series (10,000 points)
        TimeSeriesObject original = new TimeSeriesObject();
        original.setLabel("large-series");

        for (int i = 0; i < 10000; i++) {
            original.addValuePair(i * 1.0, Math.sin(i * 0.1) * 100.0);
        }

        DataOutputSerializer out = new DataOutputSerializer(1024 * 256);
        serializer.serialize(original, out);

        DataInputDeserializer in = new DataInputDeserializer(out.getCopyOfBuffer());
        TimeSeriesObject deserialized = serializer.deserialize(in);

        assertNotNull(deserialized);
        assertEquals(10000, deserialized.xValues.size());
        assertEquals(10000, deserialized.yValues.size());

        // Verify a few random points
        assertEquals(original.xValues.get(0), deserialized.xValues.get(0), 0.0001);
        assertEquals(original.yValues.get(0), deserialized.yValues.get(0), 0.0001);
        assertEquals(original.xValues.get(5000), deserialized.xValues.get(5000), 0.0001);
        assertEquals(original.yValues.get(5000), deserialized.yValues.get(5000), 0.0001);
        assertEquals(original.xValues.get(9999), deserialized.xValues.get(9999), 0.0001);
        assertEquals(original.yValues.get(9999), deserialized.yValues.get(9999), 0.0001);
    }

    @Test
    public void testCopy() {
        TimeSeriesObject original = new TimeSeriesObject();
        original.setLabel("copy-test");
        original.addValuePair(1.0, 100.0);
        original.addValuePair(2.0, 200.0);

        TimeSeriesObject copy = serializer.copy(original);

        assertNotNull(copy);
        assertNotSame(original, copy);
        assertEquals(original.getLabel(), copy.getLabel());
        assertEquals(original.xValues.size(), copy.xValues.size());
        assertEquals(original.yValues.size(), copy.yValues.size());

        // Verify deep copy (modifying copy doesn't affect original)
        copy.addValuePair(3.0, 300.0);
        assertEquals(2, original.yValues.size());
        assertEquals(3, copy.yValues.size());
    }

    @Test
    public void testCopy_NullTimeSeries() {
        TimeSeriesObject copy = serializer.copy(null);
        assertNull(copy);
    }

    @Test
    public void testIsImmutableType() {
        assertFalse(serializer.isImmutableType());
    }

    @Test
    public void testCreateInstance() {
        TimeSeriesObject instance = serializer.createInstance();
        assertNotNull(instance);
    }

    @Test
    public void testDuplicate() {
        TimeSeriesObjectSerializer duplicate = (TimeSeriesObjectSerializer) serializer.duplicate();
        assertNotNull(duplicate);
        assertNotSame(serializer, duplicate);
    }
}
