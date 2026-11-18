package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.wayang.federation.AggregateRecord;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class JobStateRouteIntegrationTest {
    @TempDir(factory = FederationLogDirectory.class, cleanup = CleanupMode.ON_SUCCESS) Path directory;

    private static List<AggregateRecord> sorted(List<AggregateRecord> records) {
        return records.stream().sorted(Comparator.comparing(AggregateRecord::key).thenComparingLong(AggregateRecord::revision)).toList();
    }

    @Test void authenticatedJobStateAndSignalClusterRoutesKeepIdenticalFullRecords() throws Exception {
        try (var fixture = new FederationFixture(directory)) {
            var signal = fixture.routeS();
            var job = fixture.routeJ();
            assertEquals(sorted(signal.partials()), sorted(job.partials()));
            assertEquals(sorted(signal.merged()), sorted(job.merged()));
            assertEquals(420, job.windows().size());
            var baseline = fixture.centralizedTransfer();
            assertEquals(3, baseline.size());
            assertEquals(3600, baseline.stream().mapToLong(FederationFixture.ByteRow::produceRecords).sum());
            for (var row : baseline) {
                assertTrue(row.produceFrameBytes() > 0);
                assertEquals(0, row.produceErrors());
            }
            for (var row : signal.bytes()) {
                assertEquals(420, row.produceRecords());
                assertTrue(row.produceFrameBytes() > 0);
                assertEquals(0, row.produceErrors());
            }
            for (var row : job.bytes()) {
                assertEquals(420, row.httpRecords());
                assertTrue(row.httpBodyBytes() > 0);
                assertTrue(row.tlsClientBytes() > row.httpBodyBytes());
            }
        }
    }
}
