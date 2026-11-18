package org.opentsx.connectors.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredFilesTest {

    private static final String PROFILE_VARIABLE = "OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME";
    private static final String TOPIC_MAP_VARIABLE = "OPENTSX_TOPIC_MAP_FILE_NAME";

    @TempDir
    Path dir;

    @Test
    void aConfiguredProfileThatCannotBeReadStopsTheToolAndNamesThePath() throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/opentsx-local.props"), "bootstrap.servers=fallback:9092\n");
        Result result = run(Map.of(PROFILE_VARIABLE, "absent.props"));
        assertNotEquals(0, result.status(), result.output());
        assertTrue(result.output().contains(PROFILE_VARIABLE + ": \"absent.props\" cannot be read ("
                + dir.toRealPath().resolve("absent.props") + ")"), result.output());
        assertFalse(result.output().contains("fallback:9092"), result.output());
    }

    @Test
    void aConfiguredProfileFallsBackToItsTemplate() throws Exception {
        Files.writeString(dir.resolve("local.props.example"), "bootstrap.servers=template:9092\n");
        Result result = run(Map.of(PROFILE_VARIABLE, "local.props"));
        assertEquals(0, result.status(), result.output());
        assertTrue(result.output().contains("BOOTSTRAP=template:9092"), result.output());
    }

    @Test
    void aConfiguredTopicMapThatCannotBeReadStopsTheToolAndNamesThePath() throws Exception {
        Files.writeString(dir.resolve("local.props"), "bootstrap.servers=localhost:9092\n");
        Result result = run(Map.of(PROFILE_VARIABLE, "local.props", TOPIC_MAP_VARIABLE, "absent.def"));
        assertNotEquals(0, result.status(), result.output());
        assertTrue(result.output().contains(TOPIC_MAP_VARIABLE + ": \"absent.def\" cannot be read ("
                + dir.toRealPath().resolve("absent.def") + ")"), result.output());
    }

    private Result run(Map<String, String> environment) throws Exception {
        Path output = dir.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"),
                LoadProfile.class.getName()))
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove(PROFILE_VARIABLE);
        builder.environment().remove(TOPIC_MAP_VARIABLE);
        builder.environment().putAll(environment);
        int status = builder.start().waitFor();
        return new Result(status, Files.readString(output));
    }

    private record Result(int status, String output) {}

    public static class LoadProfile {
        public static void main(String[] args) {
            OpenTSxClusterLink.init();
            System.out.println("BOOTSTRAP=" + OpenTSxClusterLink.getClientProperties().getProperty("bootstrap.servers"));
        }
    }
}
