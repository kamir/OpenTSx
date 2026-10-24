package org.opentsx.flink.pfd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.StreamMap;
import org.apache.flink.streaming.api.transformations.OneInputTransformation;
import org.apache.flink.streaming.api.transformations.SourceTransformation;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.api.common.eventtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.flink.serdes.TimeSeriesObjectTypeInfo;
import org.opentsx.app.bucketanalyser.PFDRecorder;
import org.opentsx.data.model.Observation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PFDRuntimeContractTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"DFA", "SMOOTH", "LOAD_DATA", "CACHE", "FILTER", "WINDOW_AGGREGATE",
            "DECOMPOSE", "DETECT_ANOMALY"})
    void parserRejectsUnsupportedOperations(String operation) throws Exception {
        PFDDefinition flow = flow();
        flow.steps.get(0).operation = operation;
        assertThrows(IllegalArgumentException.class, () -> parse(flow));
    }

    @ParameterizedTest
    @ValueSource(strings = {"KAFKA", "FILE", "OPENTSDB"})
    void parserRejectsUnsupportedOutputs(String type) throws Exception {
        PFDDefinition flow = flow();
        flow.output.type = type;
        assertThrows(IllegalArgumentException.class, () -> parse(flow));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FILE", "GENERATOR"})
    void parserRejectsUnsupportedInputs(String type) throws Exception {
        PFDDefinition flow = flow();
        flow.input.type = type;
        assertThrows(IllegalArgumentException.class, () -> parse(flow));
    }

    @ParameterizedTest
    @ValueSource(strings = {"z-score", "min-max", "unknown"})
    void parserRejectsUnsupportedNormalization(String method) throws Exception {
        PFDDefinition flow = flow();
        flow.steps.get(0).params = Map.of("method", method);
        assertThrows(IllegalArgumentException.class, () -> parse(flow));
    }

    @Test
    void parserRequiresAnInputTopic() throws Exception {
        PFDDefinition flow = flow();
        flow.input.topic = " ";
        assertThrows(IllegalArgumentException.class, () -> parse(flow));
    }

    @Test
    void centeringUpdatesValuesAndCachedMean() throws Exception {
        TimeSeriesObject row = new TimeSeriesObject();
        row.addValue(2.0);
        row.addValue(4.0);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<TimeSeriesObject> input = env.fromCollection(List.of(row), new TimeSeriesObjectTypeInfo());
        DataStream<TimeSeriesObject> output = OperationFactory.apply(input, flow().steps.get(0));
        OneInputTransformation<TimeSeriesObject, TimeSeriesObject> transformation =
                (OneInputTransformation<TimeSeriesObject, TimeSeriesObject>) output.getTransformation();
        StreamMap<TimeSeriesObject, TimeSeriesObject> operator =
                (StreamMap<TimeSeriesObject, TimeSeriesObject>) transformation.getOperator();
        TimeSeriesObject result = operator.getUserFunction().map(row);
        assertEquals(List.of(-1.0, 1.0), result.yValues);
        assertEquals(0.0, result.getAvarage());
    }

    @Test
    void sourceUsesConfiguredBrokers() throws Exception {
        String original = System.getProperty("bootstrap.servers");
        try {
            System.setProperty("bootstrap.servers", "fixture-broker:19092");
            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            DataStream<?> stream = (DataStream<?>) invoke("buildSource",
                    new Class<?>[]{StreamExecutionEnvironment.class, PFDDefinition.PFDInput.class},
                    env, flow().input);
            SourceTransformation<?, ?, ?> transformation = (SourceTransformation<?, ?, ?>) stream.getTransformation();
            KafkaSource<?> source = (KafkaSource<?>) transformation.getSource();
            Method configuration = KafkaSource.class.getDeclaredMethod("getConfiguration");
            configuration.setAccessible(true);
            org.apache.flink.configuration.Configuration settings =
                    (org.apache.flink.configuration.Configuration) configuration.invoke(source);
            assertEquals("fixture-broker:19092", settings.getString("bootstrap.servers", ""));
        } finally {
            if (original == null) System.clearProperty("bootstrap.servers");
            else System.setProperty("bootstrap.servers", original);
        }
    }

    @Test
    void sourceRejectsMissingBrokerConfiguration() {
        String original = System.getProperty("bootstrap.servers");
        try {
            System.setProperty("bootstrap.servers", " ");
            assertThrows(IllegalArgumentException.class, () -> invoke("buildSource",
                    new Class<?>[]{StreamExecutionEnvironment.class, PFDDefinition.PFDInput.class},
                    StreamExecutionEnvironment.getExecutionEnvironment(), flow().input));
        } finally {
            if (original == null) System.clearProperty("bootstrap.servers");
            else System.setProperty("bootstrap.servers", original);
        }
    }

    @Test
    void eventTimeWatermarksAdvanceFromObservationTimestamps() throws Exception {
        String original = System.getProperty("bootstrap.servers");
        try {
            System.setProperty("bootstrap.servers", "fixture-broker:19092");
            DataStream<?> stream = (DataStream<?>) invoke("buildSource",
                    new Class<?>[]{StreamExecutionEnvironment.class, PFDDefinition.PFDInput.class},
                    StreamExecutionEnvironment.getExecutionEnvironment(), flow().input);
            SourceTransformation<Observation, ?, ?> source =
                    (SourceTransformation<Observation, ?, ?>) stream.getTransformation();
            WatermarkStrategy<Observation> strategy = source.getWatermarkStrategy();
            Observation observation = new Observation();
            observation.setTimestamp(20000L);
            long timestamp = strategy.createTimestampAssigner(null).extractTimestamp(observation, -1L);
            assertEquals(20000L, timestamp);
            AtomicLong watermark = new AtomicLong(Long.MIN_VALUE);
            WatermarkOutput output = new WatermarkOutput() {
                public void emitWatermark(Watermark value) { watermark.set(value.getTimestamp()); }
                public void markIdle() {}
                public void markActive() {}
            };
            WatermarkGenerator<Observation> generator = strategy.createWatermarkGenerator(null);
            generator.onEvent(observation, timestamp, output);
            generator.onPeriodicEmit(output);
            assertEquals(9999L, watermark.get());
        } finally {
            if (original == null) System.clearProperty("bootstrap.servers");
            else System.setProperty("bootstrap.servers", original);
        }
    }

    @Test
    void recorderExportCanBeParsedForExecution() throws Exception {
        String original = System.getProperty("opentsx.pfd.input.topic");
        PFDRecorder recorder = PFDRecorder.getInstance();
        try {
            System.setProperty("opentsx.pfd.input.topic", "fixture-input");
            recorder.reset("Exported flow");
            recorder.addStep("NORMALIZE", Map.of("method", "center"));
            Path file = directory.resolve("flow.json");
            recorder.saveToFile(file.toString());
            PFDDefinition parsed = (PFDDefinition) invoke("parsePFDString", new Class<?>[]{String.class},
                    Files.readString(file));
            assertEquals("fixture-input", parsed.input.topic);
            assertEquals("LOG", parsed.output.type);
            assertEquals("center", parsed.steps.get(0).params.get("method"));
        } finally {
            if (original == null) System.clearProperty("opentsx.pfd.input.topic");
            else System.setProperty("opentsx.pfd.input.topic", original);
            recorder.reset("New Flow");
        }
    }

    private PFDDefinition flow() {
        PFDDefinition flow = new PFDDefinition();
        flow.name = "Contract flow";
        flow.input = new PFDDefinition.PFDInput();
        flow.input.type = "KAFKA";
        flow.input.topic = "fixture-input";
        flow.output = new PFDDefinition.PFDOutput();
        flow.output.type = "LOG";
        PFDStep step = new PFDStep();
        step.id = "center";
        step.operation = "NORMALIZE";
        step.params = Map.of("method", "center");
        flow.steps = List.of(step);
        return flow;
    }

    private PFDDefinition parse(PFDDefinition flow) throws Exception {
        return (PFDDefinition) invoke("parsePFDString", new Class<?>[]{String.class},
                new ObjectMapper().writeValueAsString(flow));
    }

    private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = DynamicTopologyJob.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
}
