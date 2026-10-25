package org.opentsx.flink.pfd;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.JobID;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.transformations.OneInputTransformation;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.runtime.operators.windowing.WindowOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.model.Observation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PFDWindowSizeTest {
    private static final String FLOW = "{\"name\":\"Window contract\",\"input\":{\"type\":\"KAFKA\",\"topic\":\"fixture\"},"
            + "\"steps\":[],\"output\":{\"type\":\"LOG\"}}";
    private String originalBrokers;
    private GraphEnvironment environment;
    @TempDir
    Path directory;

    @BeforeEach
    void configureGraphExecution() {
        originalBrokers = System.getProperty("bootstrap.servers");
        System.setProperty("bootstrap.servers", "fixture-broker:19092");
        environment = new GraphEnvironment();
        GraphEnvironment.install(environment);
    }

    @AfterEach
    void restoreExecution() {
        GraphEnvironment.reset();
        if (originalBrokers == null) System.clearProperty("bootstrap.servers");
        else System.setProperty("bootstrap.servers", originalBrokers);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultWindowRemainsFiveMinutes(boolean inline) throws Exception {
        DynamicTopologyJob.main(arguments(inline));
        assertWindows(300000L);
    }

    @ParameterizedTest
    @CsvSource({"false,1", "true,1", "false,1000", "true,1000", "false,60000", "true,60000",
            "false,300000", "true,300000", "false,600000", "true,600000"})
    void configuredWindowControlsEventTimeBoundaries(boolean inline, long size) throws Exception {
        DynamicTopologyJob.main(arguments(inline, "--window-size", Long.toString(size)));
        assertWindows(size);
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void invalidWindowOptionsFailBeforeExecution(List<String> options) throws Exception {
        for (boolean inline : new boolean[]{false, true}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> DynamicTopologyJob.main(arguments(inline, options.toArray(String[]::new))));
            assertTrue(failure.getMessage().contains("--window-size"), failure.getMessage());
            assertFalse(environment.executed);
            assertTrue(environment.getTransformations().isEmpty());
        }
    }

    static Stream<List<String>> invalidOptions() {
        return Stream.of(List.of("--window-size", "0"), List.of("--window-size", "-1"),
                List.of("--window-size", ""), List.of("--window-size", " "),
                List.of("--window-size", "1.5"), List.of("--window-size", "abc"),
                List.of("--window-size", "9223372036854775808"), List.of("--window-size"),
                List.of("--unknown", "1000"),
                List.of("--window-size", "1000", "--window-size", "2000"));
    }

    private String[] arguments(boolean inline, String... options) throws Exception {
        List<String> arguments = new ArrayList<>();
        if (inline) {
            arguments.add("--json");
            arguments.add(FLOW);
        } else {
            Path descriptor = directory.resolve("flow.json");
            Files.writeString(descriptor, FLOW);
            arguments.add(descriptor.toString());
        }
        arguments.addAll(List.of(options));
        return arguments.toArray(String[]::new);
    }

    private void assertWindows(long size) {
        assertTrue(environment.executed);
        var windows = environment.getTransformations().stream()
                .filter(t -> t instanceof OneInputTransformation<?, ?>)
                .map(t -> ((OneInputTransformation<?, ?>) t).getOperator())
                .filter(operator -> operator instanceof WindowOperator<?, ?, ?, ?, ?>).toList();
        assertEquals(1, windows.size());
        WindowAssigner<Object, TimeWindow> assigner = (WindowAssigner<Object, TimeWindow>)
                ((WindowOperator<?, ?, ?, ?, ?>) windows.get(0)).getWindowAssigner();
        assertTrue(assigner.isEventTime());
        WindowAssigner.WindowAssignerContext context = new WindowAssigner.WindowAssignerContext() {
            @Override
            public long getCurrentProcessingTime() { return 0; }
        };
        assertEquals(List.of(new TimeWindow(0, size)), List.copyOf(assigner.assignWindows(new Observation(), 0, context)));
        assertEquals(List.of(new TimeWindow(0, size)),
                List.copyOf(assigner.assignWindows(new Observation(), size - 1, context)));
        assertEquals(List.of(new TimeWindow(size, 2 * size)),
                List.copyOf(assigner.assignWindows(new Observation(), size, context)));
        assertEquals(List.of(new TimeWindow(-size, 0)),
                List.copyOf(assigner.assignWindows(new Observation(), -1, context)));
    }

    private static class GraphEnvironment extends StreamExecutionEnvironment {
        boolean executed;

        static void install(GraphEnvironment environment) {
            initializeContextEnvironment(configuration -> environment);
        }

        static void reset() {
            resetContextEnvironment();
        }

        @Override
        public JobExecutionResult execute(String name) {
            executed = true;
            return new JobExecutionResult(new JobID(), 0, Map.of());
        }
    }
}
