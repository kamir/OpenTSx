package org.opentsx.demo.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigTest {

    static final String VARIABLE = "OPENTSX_LOG_LEVEL";

    @TempDir
    Path dir;

    @Test
    void theVariableSetsTheRootLevel() throws Exception {
        Result result = run(Map.of(VARIABLE, "debug"));
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("ROOT_LEVEL=FINE"), result.output());
    }

    @Test
    void thePropertiesFileSetsTheRootLevel() throws Exception {
        Files.writeString(dir.resolve("opentsx.properties"), "opentsx.log.level=trace\n");
        Result result = run(Map.of());
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("ROOT_LEVEL=FINEST"), result.output());
    }

    @Test
    void theVariableWinsOverThePropertiesFile() throws Exception {
        Files.writeString(dir.resolve("opentsx.properties"), "opentsx.log.level=trace\n");
        Result result = run(Map.of(VARIABLE, "error"));
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("ROOT_LEVEL=SEVERE"), result.output());
    }

    @Test
    void anEmptyVariableLeavesThePropertiesFileLevel() throws Exception {
        Files.writeString(dir.resolve("opentsx.properties"), "opentsx.log.level=trace\n");
        Result result = run(Map.of(VARIABLE, ""));
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("ROOT_LEVEL=FINEST"), result.output());
    }

    @Test
    void blanksAroundALevelAreIgnored() throws Exception {
        Files.writeString(dir.resolve("opentsx.properties"), "opentsx.log.level=debug  \n");
        Result result = run(Map.of());
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("ROOT_LEVEL=FINE"), result.output());
    }

    @Test
    void withoutALevelTheRootLevelIsInfo() throws Exception {
        Result result = run(Map.of());
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("ROOT_LEVEL=INFO"), result.output());
    }

    @Test
    void aLevelOutsideTheRegistryIsRefusedNamingTheVariable() throws Exception {
        Result result = run(Map.of(VARIABLE, "verbose"));
        assertEquals(1, result.status(), result.output());
        assertTrue(result.output().contains(VARIABLE
                + ": \"verbose\" is not allowed; expected one of error, warn, info, debug, trace"), result.output());
    }

    @Test
    void aLevelOutsideTheRegistryInThePropertiesFileIsRefusedNamingTheKey() throws Exception {
        Files.writeString(dir.resolve("opentsx.properties"), "opentsx.log.level=INFO\n");
        Result result = run(Map.of());
        assertEquals(1, result.status(), result.output());
        assertTrue(result.output().contains(
                "opentsx.log.level: \"INFO\" is not allowed; expected one of error, warn, info, debug, trace"),
                result.output());
    }

    private Result run(Map<String, String> environment) throws Exception {
        Path output = dir.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"),
                ShowRootLevel.class.getName()))
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove(VARIABLE);
        builder.environment().putAll(environment);
        int status = builder.start().waitFor();
        return new Result(status, Files.readString(output));
    }

    private record Result(int status, String output) {}

    public static class ShowRootLevel {
        public static void main(String[] args) throws Exception {
            ProductionConfig config = new ProductionConfig();
            System.out.println("ROOT_LEVEL=" + Logger.getLogger("").getLevel());
            config.shutdown();
        }
    }
}
