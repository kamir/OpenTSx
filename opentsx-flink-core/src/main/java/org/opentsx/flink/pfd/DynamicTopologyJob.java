package org.opentsx.flink.pfd;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.File;
import java.io.IOException;
import java.time.Duration;

/**
 * Flink Job that builds a topology dynamically from a Processing Flow
 * Descriptor (PFD).
 */
public class DynamicTopologyJob {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DynamicTopologyJob <path-to-pfd.json> OR --json <json-content>");
            System.exit(1);
        }

        String pfdPath = null;
        String pfdContent = null;

        if (args[0].equals("--json") && args.length > 1) {
            pfdContent = args[1];
        } else {
            pfdPath = args[0];
        }

        PFDDefinition pfd = (pfdContent != null) ? parsePFDString(pfdContent) : parsePFD(pfdPath);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 1. Source
        DataStream<Observation> sourceStream = buildSource(env, pfd.input);

        // 2. Initial Aggregation (Observation -> TimeSeriesObject)
        // Hardcoded 5-minute tumbling window for now, could be parameterized
        DataStream<TimeSeriesObject> timeSeriesStream = sourceStream
                .keyBy(obs -> obs.getUri() != null ? obs.getUri().toString() : "unknown")
                .window(TumblingEventTimeWindows.of(Time.minutes(5)))
                .aggregate(new TimeSeriesAggregateFunction())
                .returns(new TimeSeriesObjectTypeInfo());

        // 3. Dynamic Steps
        DataStream<TimeSeriesObject> currentStream = timeSeriesStream;
        for (PFDStep step : pfd.steps) {
            currentStream = OperationFactory.apply(currentStream, step);
        }

        // 4. Sink
        buildSink(currentStream, pfd.output);

        env.execute("OpenTSx Dynamic Job: " + pfd.name);
    }

    private static PFDDefinition parsePFD(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(path), PFDDefinition.class);
    }

    private static PFDDefinition parsePFDString(String content) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(content, PFDDefinition.class);
    }

    private static DataStream<Observation> buildSource(StreamExecutionEnvironment env, PFDDefinition.PFDInput input) {
        if ("KAFKA".equalsIgnoreCase(input.type)) {
            KafkaSource<Observation> source = KafkaSource.<Observation>builder()
                    .setBootstrapServers("localhost:9092") // Should come from config
                    .setTopics(input.topic)
                    .setGroupId("opentsx-dynamic-job")
                    .setStartingOffsets(OffsetsInitializer.earliest())
                    .setDeserializer(new ObservationSchema())
                    .build();

            return env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka Source");
        } else {
            throw new UnsupportedOperationException("Unsupported input type: " + input.type);
        }
    }

    private static void buildSink(DataStream<TimeSeriesObject> stream, PFDDefinition.PFDOutput output) {
        if ("LOG".equalsIgnoreCase(output.type)) {
            stream.map(ts -> ts.toString()).print();
        } else {
            // Default to print
            stream.map(ts -> "Output to " + output.type + ": " + ts.getLabel()).print();
        }
    }
}
