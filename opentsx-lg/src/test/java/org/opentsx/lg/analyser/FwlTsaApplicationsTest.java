package org.opentsx.lg.analyser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FwlTsaApplicationsTest {

    @TempDir
    Path dir;

    private String previousProfile;

    @BeforeEach
    void useProbeProfile() throws Exception {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, "bootstrap.servers=broker.example:9092\nschema.registry.url=http://registry.example:8081\n");
        OpenTSxClusterLink.PROPS_FN = profile.toString();
    }

    @AfterEach
    void restoreProfile() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
    }

    private static void assertFromProfile(Properties props) {
        assertEquals("broker.example:9092", props.getProperty("bootstrap.servers"));
        assertEquals("http://registry.example:8081", props.getProperty("schema.registry.url"));
    }

    @Test
    void theStreamsApplicationTakesTheAddressesFromTheProfile() {
        assertFromProfile(FWL_TSA_KStreamsApplication.getProperties());
    }

    @Test
    void theTableApplicationTakesTheAddressesFromTheProfile() {
        assertFromProfile(FWL_TSA_KTableApplication.getProperties());
    }
}
