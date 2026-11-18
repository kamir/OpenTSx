package org.opentsx.wayang;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

@Execution(ExecutionMode.SAME_THREAD)
class FederationLogRetentionTest {

    private static ProbeState current;
    private final List<Path> ownedDirectories = new ArrayList<>();

    @AfterEach
    void removeSyntheticLogs() throws Exception {
        for (Path directory : ownedDirectories) {
            if (!Files.exists(directory)) continue;
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        current = null;
    }

    @ParameterizedTest
    @MethodSource("integrationClasses")
    void failedTestRetainsAllBrokerLogsAndTheOriginalException(Class<?> integration) throws Exception {
        Result result = run(integration, FailurePoint.TEST, "body");
        assertFailure(result);
        assertTrue(result.state.bodyRan);
        assertEquals(1, result.state.closeCalls);
        assertRetained(result);
    }

    @ParameterizedTest
    @MethodSource("integrationClasses")
    void failedFixtureConstructionRetainsAllBrokerLogsAndTheOriginalException(Class<?> integration) throws Exception {
        Result result = run(integration, FailurePoint.CONSTRUCTOR, "constructor");
        assertFailure(result);
        assertFalse(result.state.bodyRan);
        assertEquals(0, result.state.closeCalls);
        assertRetained(result);
    }

    @ParameterizedTest
    @MethodSource("integrationClasses")
    void failedFixtureCloseRetainsAllBrokerLogsAndTheOriginalException(Class<?> integration) throws Exception {
        Result result = run(integration, FailurePoint.CLOSE, "close");
        assertFailure(result);
        assertTrue(result.state.bodyRan);
        assertEquals(1, result.state.closeCalls);
        assertRetained(result);
    }

    @ParameterizedTest
    @MethodSource("integrationClasses")
    void successfulTestsCleanTheirTemporaryDirectory(Class<?> integration) throws Exception {
        Result result = run(integration, FailurePoint.NONE, "success");
        assertEquals(1, result.summary.getTestsFoundCount());
        assertEquals(1, result.summary.getTestsStartedCount());
        assertEquals(1, result.summary.getTestsSucceededCount());
        assertEquals(0, result.summary.getTotalFailureCount());
        assertTrue(result.state.bodyRan);
        assertEquals(1, result.state.closeCalls);
        assertNotNull(result.state.directory);
        assertFalse(Files.exists(result.state.directory));
    }

    @ParameterizedTest
    @MethodSource("integrationClasses")
    void separateFailedRunsRetainDistinctUnmodifiedLogDirectories(Class<?> integration) throws Exception {
        Result first = run(integration, FailurePoint.TEST, "first");
        Result second = run(integration, FailurePoint.TEST, "second");
        assertFailure(first);
        assertFailure(second);
        assertNotEquals(first.state.directory, second.state.directory);
        assertRetained(first);
        assertRetained(second);
    }

    private static Stream<Class<?>> integrationClasses() {
        return Stream.of(FederatedStatsIntegrationTest.class, CompletenessIntegrationTest.class,
                JobStateRouteIntegrationTest.class, ConformanceHandshakeTest.class, SiteLifecycleIntegrationTest.class);
    }

    private Result run(Class<?> integration, FailurePoint failurePoint, String attempt) throws Exception {
        List<Field> directories = Arrays.stream(integration.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(TempDir.class)).toList();
        assertEquals(1, directories.size());
        TempDir policy = directories.get(0).getAnnotation(TempDir.class);
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(LifecycleProbe.class))
                .filters(EngineFilter.includeEngines("junit-jupiter")).enableImplicitConfigurationParameters(false);
        if (policy.factory() != TempDirFactory.class) {
            request.configurationParameter(TempDir.DEFAULT_FACTORY_PROPERTY_NAME, policy.factory().getName());
        }
        if (policy.cleanup() != CleanupMode.DEFAULT) {
            request.configurationParameter(TempDir.DEFAULT_CLEANUP_MODE_PROPERTY_NAME, policy.cleanup().name());
        }
        String nested = integration == ConformanceHandshakeTest.class ? "fingerprint"
                : integration == SiteLifecycleIntegrationTest.class ? "lifecycle" : "";
        current = new ProbeState(failurePoint, nested, attempt);
        ProbeState state = current;
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        try {
            LauncherFactory.create().execute(request.build(), listener);
        } finally {
            if (state.directory != null) ownedDirectories.add(state.directory);
        }
        return new Result(state, listener.getSummary());
    }

    private void assertFailure(Result result) {
        assertEquals(1, result.summary.getTestsFoundCount());
        assertEquals(1, result.summary.getTestsStartedCount());
        assertEquals(1, result.summary.getTestsFailedCount());
        assertEquals(0, result.summary.getTestsSkippedCount());
        assertEquals(0, result.summary.getTestsAbortedCount());
        assertEquals(1, result.summary.getTotalFailureCount());
        assertSame(result.state.failure, result.summary.getFailures().get(0).getException());
    }

    private void assertRetained(Result result) throws IOException {
        Path directory = result.state.directory;
        assertNotNull(directory);
        assertTrue(Files.isDirectory(directory), "Failed-test logs were deleted");
        assertTrue(directory.toAbsolutePath().normalize().startsWith(
                Path.of("target", "federation-test-logs").toAbsolutePath().normalize()));
        Path brokers = directory.resolve(result.state.nested).resolve("brokers");
        for (String site : List.of("s1", "s2", "s3", "signal")) {
            assertEquals(result.state.attempt + " " + site + " synthetic broker output\n",
                    Files.readString(brokers.resolve(site + ".log")));
        }
    }

    static class LifecycleProbe {
        @TempDir Path directory;
        SyntheticFixture fixture;

        @BeforeEach
        void constructFixture() throws IOException {
            fixture = new SyntheticFixture(directory);
        }

        @Test
        void exerciseFixture() {
            current.bodyRan = true;
            if (current.failurePoint == FailurePoint.TEST) throw current.failure;
        }

        @AfterEach
        void closeFixture() {
            if (fixture != null) fixture.close();
        }
    }

    static class SyntheticFixture implements AutoCloseable {
        SyntheticFixture(Path directory) throws IOException {
            current.directory = directory;
            Path brokers = Files.createDirectories(directory.resolve(current.nested).resolve("brokers"));
            for (String site : List.of("s1", "s2", "s3", "signal")) {
                Files.writeString(brokers.resolve(site + ".log"), current.attempt + " " + site + " synthetic broker output\n");
            }
            if (current.failurePoint == FailurePoint.CONSTRUCTOR) throw current.failure;
        }

        @Override
        public void close() {
            current.closeCalls++;
            if (current.failurePoint == FailurePoint.CLOSE) throw current.failure;
        }
    }

    private enum FailurePoint { NONE, TEST, CONSTRUCTOR, CLOSE }

    private static class ProbeState {
        final FailurePoint failurePoint;
        final String nested;
        final String attempt;
        final IllegalStateException failure = new IllegalStateException("Synthetic fixture failure");
        Path directory;
        boolean bodyRan;
        int closeCalls;

        ProbeState(FailurePoint failurePoint, String nested, String attempt) {
            this.failurePoint = failurePoint;
            this.nested = nested;
            this.attempt = attempt;
        }
    }

    private record Result(ProbeState state, TestExecutionSummary summary) {}
}
