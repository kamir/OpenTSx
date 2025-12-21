package org.opentsx.flink.functions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentsx.data.model.Observation;
import org.opentsx.data.series.TimeSeriesObject;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TimeSeriesAggregateFunction}.
 *
 * These tests verify correct aggregation of Observation objects into TimeSeriesObject instances,
 * including merging of partial aggregates.
 */
public class TimeSeriesAggregateFunctionTest {

    private TimeSeriesAggregateFunction aggregateFunction;

    @BeforeEach
    public void setUp() {
        aggregateFunction = new TimeSeriesAggregateFunction();
    }

    @Test
    public void testCreateAccumulator() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();
        assertNotNull(accumulator);
        assertTrue(accumulator.xValues == null || accumulator.xValues.isEmpty());
        assertTrue(accumulator.yValues == null || accumulator.yValues.isEmpty());
    }

    @Test
    public void testAdd_SingleObservation() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();

        Observation obs = new Observation();
        obs.setLabel("sensor-1");
        obs.setTimestamp(1000L);
        obs.setValue(42.5);

        TimeSeriesObject result = aggregateFunction.add(obs, accumulator);

        assertSame(accumulator, result);
        assertEquals("sensor-1", result.getLabel());
        assertEquals(1, result.xValues.size());
        assertEquals(1, result.yValues.size());
        assertEquals(1000.0, result.xValues.get(0), 0.001);
        assertEquals(42.5, result.yValues.get(0), 0.001);
    }

    @Test
    public void testAdd_MultipleObservations() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();

        // Add first observation
        Observation obs1 = new Observation();
        obs1.setLabel("sensor-1");
        obs1.setTimestamp(1000L);
        obs1.setValue(10.0);
        aggregateFunction.add(obs1, accumulator);

        // Add second observation
        Observation obs2 = new Observation();
        obs2.setLabel("sensor-1");
        obs2.setTimestamp(2000L);
        obs2.setValue(20.0);
        aggregateFunction.add(obs2, accumulator);

        // Add third observation
        Observation obs3 = new Observation();
        obs3.setLabel("sensor-1");
        obs3.setTimestamp(3000L);
        obs3.setValue(30.0);
        aggregateFunction.add(obs3, accumulator);

        assertEquals("sensor-1", accumulator.getLabel());
        assertEquals(3, accumulator.xValues.size());
        assertEquals(3, accumulator.yValues.size());

        assertEquals(1000.0, accumulator.xValues.get(0), 0.001);
        assertEquals(2000.0, accumulator.xValues.get(1), 0.001);
        assertEquals(3000.0, accumulator.xValues.get(2), 0.001);

        assertEquals(10.0, accumulator.yValues.get(0), 0.001);
        assertEquals(20.0, accumulator.yValues.get(1), 0.001);
        assertEquals(30.0, accumulator.yValues.get(2), 0.001);
    }

    @Test
    public void testAdd_NullObservation() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();
        TimeSeriesObject result = aggregateFunction.add(null, accumulator);

        assertSame(accumulator, result);
        assertTrue(result.xValues == null || result.xValues.isEmpty());
    }

    @Test
    public void testGetResult() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();

        Observation obs = new Observation();
        obs.setLabel("test");
        obs.setTimestamp(1000L);
        obs.setValue(100.0);

        aggregateFunction.add(obs, accumulator);
        TimeSeriesObject result = aggregateFunction.getResult(accumulator);

        assertSame(accumulator, result);
    }

    @Test
    public void testMerge_TwoNonEmptySeries() {
        // Create first time series
        TimeSeriesObject ts1 = new TimeSeriesObject();
        ts1.setLabel("sensor-1");
        ts1.addValuePair(1000.0, 10.0);
        ts1.addValuePair(2000.0, 20.0);

        // Create second time series
        TimeSeriesObject ts2 = new TimeSeriesObject();
        ts2.setLabel("sensor-1");
        ts2.addValuePair(3000.0, 30.0);
        ts2.addValuePair(4000.0, 40.0);

        // Merge
        TimeSeriesObject merged = aggregateFunction.merge(ts1, ts2);

        assertNotNull(merged);
        assertEquals("sensor-1", merged.getLabel());
        assertEquals(4, merged.xValues.size());
        assertEquals(4, merged.yValues.size());

        // Verify all values are present (order may vary in simple implementation)
        assertTrue(merged.yValues.contains(10.0));
        assertTrue(merged.yValues.contains(20.0));
        assertTrue(merged.yValues.contains(30.0));
        assertTrue(merged.yValues.contains(40.0));
    }

    @Test
    public void testMerge_OneEmptySeries() {
        TimeSeriesObject ts1 = new TimeSeriesObject();
        ts1.setLabel("sensor-1");
        ts1.addValuePair(1000.0, 10.0);

        TimeSeriesObject ts2 = new TimeSeriesObject();
        ts2.setLabel("sensor-1");

        TimeSeriesObject merged = aggregateFunction.merge(ts1, ts2);

        assertNotNull(merged);
        assertEquals(ts1, merged);
        assertEquals(1, merged.yValues.size());
    }

    @Test
    public void testMerge_BothEmpty() {
        TimeSeriesObject ts1 = new TimeSeriesObject();
        TimeSeriesObject ts2 = new TimeSeriesObject();

        TimeSeriesObject merged = aggregateFunction.merge(ts1, ts2);

        assertNotNull(merged);
        assertTrue(merged.yValues == null || merged.yValues.isEmpty());
    }

    @Test
    public void testMerge_NullSeries() {
        TimeSeriesObject ts1 = new TimeSeriesObject();
        ts1.setLabel("sensor-1");
        ts1.addValuePair(1000.0, 10.0);

        TimeSeriesObject merged1 = aggregateFunction.merge(null, ts1);
        assertEquals(ts1, merged1);

        TimeSeriesObject merged2 = aggregateFunction.merge(ts1, null);
        assertEquals(ts1, merged2);
    }

    @Test
    public void testAdd_PreservesLabelFromFirstObservation() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();

        Observation obs1 = new Observation();
        obs1.setLabel("first-label");
        obs1.setTimestamp(1000L);
        obs1.setValue(10.0);

        Observation obs2 = new Observation();
        obs2.setLabel("second-label"); // Different label
        obs2.setTimestamp(2000L);
        obs2.setValue(20.0);

        aggregateFunction.add(obs1, accumulator);
        aggregateFunction.add(obs2, accumulator);

        // Should keep the first label
        assertEquals("first-label", accumulator.getLabel());
    }

    @Test
    public void testAdd_HandlesNullFields() {
        TimeSeriesObject accumulator = aggregateFunction.createAccumulator();

        // Observation with null timestamp
        Observation obs1 = new Observation();
        obs1.setLabel("test");
        obs1.setTimestamp(null);
        obs1.setValue(10.0);
        aggregateFunction.add(obs1, accumulator);

        // Observation with null value
        Observation obs2 = new Observation();
        obs2.setLabel("test");
        obs2.setTimestamp(1000L);
        obs2.setValue(null);
        aggregateFunction.add(obs2, accumulator);

        // Valid observation
        Observation obs3 = new Observation();
        obs3.setLabel("test");
        obs3.setTimestamp(2000L);
        obs3.setValue(20.0);
        aggregateFunction.add(obs3, accumulator);

        // Should only have one valid point
        assertEquals(1, accumulator.yValues.size());
        assertEquals(20.0, accumulator.yValues.get(0), 0.001);
    }
}
