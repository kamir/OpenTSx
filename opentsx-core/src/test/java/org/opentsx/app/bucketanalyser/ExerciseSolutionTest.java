package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExerciseSolutionTest {

    static final String EXERCISE_3 = "org.opentsx.exercises.swe.solutions.Exercise3_LoadAndTransform";

    @TempDir
    Path directory;

    Path classes;
    Path data;

    @BeforeEach
    void compileExercise3() throws Exception {
        classes = Files.createDirectories(directory.resolve("classes"));
        Path source = Path.of("..", "exercises", "solutions", "swe-track", "episode-02",
                "Exercise3_LoadAndTransform.java");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-cp", classpath(), "-d", classes.toString(), source.toString());
        assertEquals(0, status, diagnostics.toString());
        data = Files.createDirectories(directory.resolve("run").resolve("sample_data"));
    }

    @Test
    void exercise3ReadsTheSampleDataWithItsHeader() throws Exception {
        Files.copy(Path.of("..", "sample_data", "sensor_data.csv"), data.resolve("sensor_data.csv"));

        Result result = run();

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("Number of points: 4"), result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void exercise3StopsOnABrokenFileWithOneLine() throws Exception {
        Files.writeString(data.resolve("sensor_data.csv"), "timestamp,temperature_c\n2024-01-01T00:00:00Z,warm\n");

        Result result = run();

        assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
        assertEquals("sample_data/sensor_data.csv:2: \"warm\" is not a number; expected a decimal number such as 21.1\n",
                result.stderr());
    }

    private Result run() throws Exception {
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes + java.io.File.pathSeparator + classpath(), EXERCISE_3);
        ProcessBuilder builder = new ProcessBuilder(command).directory(data.getParent().toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        int status = builder.start().waitFor();
        return new Result(status, Files.readString(stdout), Files.readString(stderr));
    }

    private String classpath() {
        return System.getProperty("surefire.test.class.path");
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
