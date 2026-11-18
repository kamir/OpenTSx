package org.opentsx.core.config;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {
            "optional-defaults-absent", "explicit-file-source-precedence", "profile-source-precedence",
            "canonical-selector-before-jvm-alias", "uppercase-selector-alias", "environment-and-live-jvm",
            "explicit-file-missing", "explicit-file-directory", "explicit-file-malformed", "explicit-file-empty",
            "explicit-profile-missing", "explicit-profile-directory", "explicit-profile-malformed",
            "working-default-directory", "working-default-malformed",
            "classpath-default-open", "classpath-default-read", "classpath-default-parse", "classpath-default-close",
            "classpath-open-atomic", "classpath-read-atomic", "classpath-parse-atomic", "classpath-close-atomic",
            "file-parse-atomic", "file-missing-atomic", "file-directory-atomic", "classpath-absent-atomic",
            "classpath-commit-after-close",
            "turkish-mapped-key", "turkish-prefixed-key", "turkish-uppercase-prefix", "turkish-dot-mapping",
            "turkish-live-jvm", "turkish-literal-env"
    })
    void configurationSourcesFailExplicitlyAndPreserveLookupAndCommitSemantics(String scenario) throws Exception {
        Path output = directory.resolve("probe-output.txt");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), ConfigManagerProbe.class.getName(), scenario));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        builder.environment().put("OPENTSX_CONFIG_FILE", "environment-selected.properties");
        if (!scenario.equals("canonical-selector-before-jvm-alias")) {
            builder.environment().remove("OPENTSX_CONFIG_FILE");
        }
        builder.environment().put("fixture.id", "literal");
        builder.environment().put("OPENTSX_FIXTURE_ID", "mapped");
        builder.environment().put("OPENTSX_SECOND_ID", "second-mapped");
        builder.environment().put("OPENTSX_INTEGRATION_ID", "integration-mapped");
        builder.environment().put("OPENTSX_GROUP_ID", "group-mapped");
        builder.environment().put("OPENTSX_FIXTURE_INTEGRATION_ID", "fixture-integration-mapped");
        if (scenario.equals("turkish-literal-env")) builder.environment().put("integration.id", "literal");
        Process process = builder.start();
        int status = process.waitFor();
        String text = Files.readString(output);
        assertFalse(text.contains(ConfigManagerProbe.CONTENT_MARKER), "Configuration failure exposed fixture content");
        assertEquals(0, status, text);
        assertEquals("Configuration probe passed\n", text.replace("\r\n", "\n"));
    }
}
