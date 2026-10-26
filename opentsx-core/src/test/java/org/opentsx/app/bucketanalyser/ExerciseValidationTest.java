package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseValidationTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"02", "03"})
    void exercisesPassInStandaloneProcess(String episode) throws Exception {
        compile(episode, false);
        Result result = run(episode);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Exercises Passed: 4 / 4"), result.output());
        assertFalse(result.output().contains("FAIL"), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"02", "03"})
    void failedAssertionReturnsNonzeroStatus(String episode) throws Exception {
        compile(episode, true);
        Result result = run(episode);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("SOME EXERCISES NEED REVIEW"), result.output());
    }

    @Test
    void runnerUsesMavenValidationCases() throws Exception {
        Result result = runScript("--quick", 0);
        assertEquals(0, result.exitCode(), result.output());
        String invocation = Files.readString(directory.resolve("invocation.txt"));
        assertTrue(invocation.contains("-Dtest=ExerciseValidationTest"), invocation);
        assertTrue(Files.readString(directory.resolve("test-summary.json")).contains("\"failed\": 0"));
    }

    @Test
    void runnerPropagatesMavenFailureThroughTee() throws Exception {
        Result result = runScript("--validation-only", 42);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(Files.readString(directory.resolve("test-summary.json")).contains("\"failed\": 1"));
        assertTrue(Files.readString(directory.resolve("test-report.txt")).contains("Maven validation failed"));
    }

    @Test
    void runnerRejectsUnknownMode() throws Exception {
        Result result = runScript("--unknown", 0);
        assertEquals(2, result.exitCode(), result.output());
        assertFalse(Files.exists(directory.resolve("invocation.txt")));
    }

    @Test
    void fullRunnerFailsForMissingDemoScripts() throws Exception {
        Result result = runScript("--full", 0);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(Files.readString(directory.resolve("test-report.txt"))
                .contains("FAIL: bin/episode_02_create_timeseries.sh"));
    }

    private Result runScript(String mode, int mavenStatus) throws Exception {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        Path runner = bin.resolve("run_all_tests.sh");
        Files.copy(Path.of("..", "bin", "run_all_tests.sh"), runner);
        Path maven = bin.resolve("mvn");
        Files.writeString(maven, "#!/bin/bash\nprintf '%s\\n' \"$@\" > invocation.txt\nexit " + mavenStatus + "\n");
        assertTrue(maven.toFile().setExecutable(true));
        Path output = directory.resolve("runner-output.txt");
        ProcessBuilder builder = new ProcessBuilder("bash", runner.toString(), mode);
        builder.environment().put("PATH", bin + java.io.File.pathSeparator + System.getenv("PATH"));
        Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        int status = process.waitFor();
        return new Result(status, Files.readString(output));
    }

    private void compile(String episode, boolean corruptFixture) throws Exception {
        Path source = Path.of("..", "tests", "validation");
        String name = "Episode" + episode + "ValidationTests";
        String text = Files.readString(source.resolve(name + ".java"));
        if (corruptFixture) {
            String before = episode.equals("02") ? "2 * x + 5" : "5.0 * Math.sin";
            String after = episode.equals("02") ? "2 * x + 6" : "0.0 * Math.sin";
            assertTrue(text.contains(before), "Missing fixture expression: " + before);
            text = text.replace(before, after);
        }
        Path copied = directory.resolve(name + ".java");
        Files.writeString(copied, text);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-cp", classpath(), "-d", directory.toString(),
                source.resolve("ExerciseValidator.java").toString(), copied.toString());
        assertEquals(0, status, diagnostics.toString());
    }

    private Result run(String episode) throws Exception {
        Path output = directory.resolve("output.txt");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", directory + java.io.File.pathSeparator + classpath(),
                "org.opentsx.tests.validation.Episode" + episode + "ValidationTests"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        int status = process.waitFor();
        return new Result(status, Files.readString(output));
    }

    private String classpath() {
        return System.getProperty("surefire.test.class.path");
    }

    private record Result(int exitCode, String output) {}
}
