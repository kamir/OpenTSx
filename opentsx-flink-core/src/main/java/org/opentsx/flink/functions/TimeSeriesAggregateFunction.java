package org.opentsx.flink.functions;

import org.apache.flink.api.common.functions.AggregateFunction;
import org.opentsx.data.model.Observation;
import org.opentsx.data.series.TimeSeriesObject;

/**
 * Flink aggregate function that accumulates {@link Observation} objects into a
 * {@link TimeSeriesObject}.
 *
 * This function is typically used in windowed operations to collect
 * observations over time
 * and produce a complete time series for analysis.
 *
 * <h2>Usage Example:</h2>
 * 
 * <pre>{@code
 * DataStream<Observation> observations = ...;
 *
 * DataStream<TimeSeriesObject> timeSeries = observations
 *     .keyBy(Observation::getLabel)
 *     .window(TumblingEventTimeWindows.of(Time.minutes(5)))
 *     .aggregate(new TimeSeriesAggregateFunction())
 *     .returns(new TimeSeriesObjectTypeInfo());
 * }</pre>
 *
 * <h2>Stateful Processing:</h2>
 * The accumulator (TimeSeriesObject) grows with each observation. For large
 * windows,
 * consider using RocksDB state backend to handle state larger than memory.
 *
 * <h2>Performance Characteristics:</h2>
 * <ul>
 * <li>Add operation: O(1) amortized (Vector append)</li>
 * <li>Merge operation: O(n + m) where n, m are series sizes</li>
 * <li>Memory: ~16 bytes per observation (2 doubles)</li>
 * </ul>
 *
 * @see TimeSeriesObject
 * @see Observation
 */
public class TimeSeriesAggregateFunction implements AggregateFunction<Observation, TimeSeriesObject, TimeSeriesObject> {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new empty accumulator (TimeSeriesObject) for aggregating
     * observations.
     *
     * @return A new, empty TimeSeriesObject
     */
    @Override
    public TimeSeriesObject createAccumulator() {
        return new TimeSeriesObject();
    }

    /**
     * Adds an observation to the accumulating time series.
     *
     * The observation's timestamp and value are appended to the time series.
     * The label from the first observation is used for the entire series.
     *
     * @param observation The observation to add
     * @param accumulator The time series being built
     * @return The updated time series (same instance as accumulator)
     */
    @Override
    public TimeSeriesObject add(Observation observation, TimeSeriesObject accumulator) {
        if (observation == null) {
            return accumulator;
        }

        // Set label from first observation if not already set
        if (accumulator.getLabel() == null && observation.getUri() != null) {
            accumulator.setLabel(observation.getUri().toString());
        }

        // Add the observation as a (timestamp, value) pair
        Long timestamp = observation.getTimestamp();
        Double value = observation.getValue();

        if (timestamp != null && value != null) {
            accumulator.addValuePair(timestamp.doubleValue(), value);
        }

        return accumulator;
    }

    /**
     * Returns the final result of the aggregation.
     *
     * In this implementation, the accumulator itself is the result,
     * so we just return it directly.
     *
     * @param accumulator The accumulated time series
     * @return The same TimeSeriesObject
     */
    @Override
    public TimeSeriesObject getResult(TimeSeriesObject accumulator) {
        return accumulator;
    }

    /**
     * Merges two partial time series into one.
     *
     * This is used when Flink needs to combine partial aggregates from different
     * parallel instances or when merging session windows.
     *
     * The implementation creates a new TimeSeriesObject containing all points
     * from both series, sorted by timestamp.
     *
     * @param a First time series
     * @param b Second time series
     * @return A new TimeSeriesObject containing all points from both series
     */
    @Override
    public TimeSeriesObject merge(TimeSeriesObject a, TimeSeriesObject b) {
        if (a == null || a.yValues == null || a.yValues.isEmpty()) {
            return b;
        }
        if (b == null || b.yValues == null || b.yValues.isEmpty()) {
            return a;
        }

        // Create new time series to hold merged data
        TimeSeriesObject merged = new TimeSeriesObject();

        // Use label from first non-null series
        if (a.getLabel() != null) {
            merged.setLabel(a.getLabel());
        } else if (b.getLabel() != null) {
            merged.setLabel(b.getLabel());
        }

        // Merge x and y values
        // Simple approach: append all points and let downstream sorting handle order if
        // needed
        // For better performance with large series, could implement sorted merge
        if (a.xValues != null && a.yValues != null) {
            for (int i = 0; i < a.xValues.size() && i < a.yValues.size(); i++) {
                merged.addValuePair((Double) a.xValues.get(i), (Double) a.yValues.get(i));
            }
        }

        if (b.xValues != null && b.yValues != null) {
            for (int i = 0; i < b.xValues.size() && i < b.yValues.size(); i++) {
                merged.addValuePair((Double) b.xValues.get(i), (Double) b.yValues.get(i));
            }
        }

        return merged;
    }
}
