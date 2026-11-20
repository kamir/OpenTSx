package org.opentsx.lg;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttExampleTest {

    private static final String PASSWORD_FILE = "src/main/mqtt-example/volume1/docker/mqtt/config/passwd";

    @Test
    void theBootstrapCreatesThePasswordFileAndGitIgnoresIt() throws Exception {
        String bootstrap = Files.readString(Path.of("src/main/mqtt-example/01_bootstrap.sh"));

        assertTrue(bootstrap.contains("mosquitto_passwd -c ./volume1/docker/mqtt/config/passwd "), bootstrap);
        assertTrue(Files.readAllLines(Path.of(".gitignore")).contains("/" + PASSWORD_FILE));
    }
}
