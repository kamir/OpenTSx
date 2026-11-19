package org.opentsx.lg.klatency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.klatency.LatencyTestEventProducer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestResponseAnalysisProducerTest {

    @TempDir
    Path dir;

    private String previousProfile;

    @BeforeEach
    void useUnreachableBroker() throws Exception {
        previousProfile = OpenTSxClusterLink.PROPS_FN;
        Path profile = dir.resolve("probe.props");
        Files.writeString(profile, "bootstrap.servers=127.0.0.1:1\nmax.block.ms=0\n");
        OpenTSxClusterLink.PROPS_FN = profile.toString();
    }

    @AfterEach
    void restoreProfile() {
        OpenTSxClusterLink.PROPS_FN = previousProfile;
    }

    @Test
    void aFailedSendStopsTheRounds() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RequestResponseAnalysisProducer.sendTestEvents(new LatencyTestEventProducer(), 3));
        assertEquals("latency_benchmark_request: could not send the record; Topic latency_benchmark_request not present"
                + " in metadata after 0 ms.", e.getMessage());
    }
}
