package org.opentsx.flink.examples;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.opentsx.data.model.Observation;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.flink.functions.TimeSeriesAggregateFunction;
import org.opentsx.flink.serdes.ObservationSchema;
import org.opentsx.flink.serdes.TimeSeriesObjectTypeInfo;

import java.time.Duration;

/**
 * Example Flink job demonstrating OpenTSx time series analysis with Apache
 * Flink.
 *
 * <h2>Job Overview:</h2>
 * This job demonstrates a complete pipeline for time series processing:
 * <ol>
 * <li>Read {@link Observation} events from Kafka</li>
 * <li>Aggregate observations into {@link TimeSeriesObject} windows</li>
 * <li>Apply analysis operations (e.g., normalization, statistics)</li>
 * <li>Output results</li>
 * </ol>
 *
 * <h2>Prerequisites:</h2>
 * <ul>
 * <li>Kafka broker running on localhost:9092</li>
 * <li>Topic "observations" with Avro-serialized Observation records</li>
 * <li>Flink cluster (local or remote)</li>
 * </ul>
 *
 * <h2>Configuration:</h2>
 * Key parameters can be configured via command-line arguments or environment
 * variables:
 * 
 * <pre>
 * --kafka-brokers localhost:9092
 * --input-topic observations
 * --window-size 300000  (5 minutes in milliseconds)
 * </pre>
 *
 * <h2>Running Locally:</h2>
 * 
 * <pre>
 * mvn clean package
 * flink run -c org.opentsx.flink.examples.TimeSeriesAnalysisJob \
 *   target/opentsx-flink-core-3.0.0.jar
 * </pre>
 *
 * <h2>Architecture:</h2>
 * 
 * <pre>
 * Kafka (Observations)
 *   ↓
 * Source (ObservationSchema)
 *   ↓
 * Watermarks (Event Time)
 *   ↓
 * Key By Label
 *   ↓
 * Tumbling Window (5 min)
 *   ↓
 * Aggregate (TimeSeriesAggregateFunction)
 *   ↓
 * Analysis Operations
 *   ↓
 * Output (Print/Kafka)
 * </pre>
 *
 * @see Observation
 * @see TimeSeriesObject
 * @see TimeSeriesAggregateFunction
 */
public class TimeSeriesAnalysisJob {

    // Configuration constants
    private static final String DEFAULT_KAFKA_BROKERS = "localhost:9092";
    private static final String DEFAULT_INPUT_TOPIC = "observations";
    private static final long DEFAULT_WINDOW_SIZE_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Main entry point for the Flink job.
     *
     * @param args Command-line arguments (optional)
     * @throws Exception If job execution fails
     */
    public static void main(String[] args) throws Exception {
        // Parse configuration (in production, use ParameterTool)
        String kafkaBrokers = getConfigValue(args, "kafka-brokers", DEFAULT_KAFKA_BROKERS);
        String inputTopic = getConfigValue(args, "input-topic", DEFAULT_INPUT_TOPIC);
        long windowSizeMs = Long.parseLong(getConfigValue(args, "window-size", String.valueOf(DEFAULT_WINDOW_SIZE_MS)));

        // Create Flink execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Enable checkpointing for fault tolerance (every 60 seconds)
        env.enableCheckpointing(60000);

        // Configure for event-time processing
        // This ensures correct handling of out-of-order events
        env.getConfig().setAutoWatermarkInterval(1000);

        // Create Kafka source for reading Observation records
        KafkaSource<Observation> source = KafkaSource.<Observation>builder()
                .setBootstrapServers(kafkaBrokers)
                .setTopics(inputTopic)
                .setGroupId("opentsx-timeseries-analysis")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setDeserializer(new ObservationSchema())
                .build();

        // Create watermark strategy for event-time processing
        WatermarkStrategy<Observation> watermarkStrategy = WatermarkStrategy
                .<Observation>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                .withTimestampAssigner((observation, timestamp) -> observation.getTimestamp());

        // Build the processing pipeline
        DataStream<Observation> observations = env
                .fromSource(source, watermarkStrategy, "Kafka Observation Source")
                .name("Observation Stream");

        // Aggregate observations into time series windows
        DataStream<TimeSeriesObject> timeSeries = observations
                .keyBy(obs -> obs.getUri() != null ? obs.getUri().toString() : "unknown")
                .window(TumblingEventTimeWindows.of(Time.milliseconds(windowSizeMs)))
                .aggregate(new TimeSeriesAggregateFunction())
                .returns(new TimeSeriesObjectTypeInfo())
                .name("Time Series Aggregation");

        // Apply analysis operations
        DataStream<TimeSeriesObject> analyzed = timeSeries
                .map(ts -> {
                    // Example: Calculate statistics
                    if (ts.yValues != null && !ts.yValues.isEmpty()) {
                        ts.calcAverage();
                        // Note: normalize_zScore() method needs to be implemented
                    }
                    return ts;
                })
                .returns(new TimeSeriesObjectTypeInfo())
                .name("Time Series Normalization");

        // Calculate statistics for each time series
        DataStream<String> statistics = analyzed
                .map(ts -> {
                    if (ts.yValues == null || ts.yValues.isEmpty()) {
                        return String.format("TimeSeries[%s]: No data", ts.getLabel());
                    }

                    ts.calcAverage();
                    // Note: calcStddev() method needs to be implemented or use getStatisticData()

                    return String.format(
                            "TimeSeries[%s]: Points=%d, Mean=%.4f, Min=%.4f, Max=%.4f",
                            ts.getLabel(),
                            ts.yValues.size(),
                            ts.getAvarage(),
                            ts.getMinY(),
                            ts.getMaxY());
                })
                .name("Statistics Calculation");

        // Output results (in production, write to Kafka or other sink)
        statistics.print();

        // Execute the job
        env.execute("OpenTSx Time Series Analysis Job");
    }

    /**
     * Helper method to get configuration value from args or use default.
     *
     * @param args         Command-line arguments
     * @param key          Configuration key
     * @param defaultValue Default value if not found
     * @return Configuration value
     */
    private static String getConfigValue(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--" + key)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}
