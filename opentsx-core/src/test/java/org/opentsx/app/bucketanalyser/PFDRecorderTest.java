package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PFDRecorderTest {

    @Test
    void aConfigurationThatCannotLoadFailsEveryStartWithItsMessage(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("output.txt");
        Path errors = dir.resolve("errors.txt");
        Path missing = dir.resolve("missing.properties");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dopentsx.config.file=" + missing,
                "-cp", System.getProperty("surefire.test.class.path"),
                StartWithoutConfiguration.class.getName()))
                .directory(dir.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
        int status = builder.start().waitFor();
        String printed = Files.readString(output);

        assertEquals(0, status, printed + Files.readString(errors));
        String refusal = "IllegalStateException " + missing + ": no such file; give an existing file\n";
        assertEquals("first " + refusal + "second " + refusal, printed, Files.readString(errors));
    }

    public static class StartWithoutConfiguration {
        public static void main(String[] args) {
            start("first");
            start("second");
        }

        private static void start(String call) {
            try {
                PFDRecorder.getInstance();
                System.out.println(call + " started");
            } catch (Throwable e) {
                System.out.println(call + " " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }
}
