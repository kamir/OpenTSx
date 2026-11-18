package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.wayang.federation.AggregateRecord;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class CompletenessIntegrationTest {
    @TempDir Path directory;

    @Test void stoppedSiteLeavesImmutableIncompleteRecordsThenAppendsFinalRevisionOne() throws Exception {
        try (var fixture = new FederationFixture(directory);
             var session = fixture.conformanceSession()) {
            for (String site : List.of("s1", "s2", "s3")) assertEquals("accepted", session.conform(site).path("result").asText());
            session.stop("s3");
            long started = System.nanoTime();
            session.execute(List.of("s1", "s2"));
            assertTrue(session.merged().isEmpty());
            session.expireMissing();
            assertEquals(420, session.merged().size());
            for (var record : session.merged()) {
                assertEquals(0, record.revision());
                assertEquals("false", record.header("tsx-final"));
                assertEquals("s3", record.header("tsx-missing-sites"));
            }
            var initial = session.storedMerged();
            assertEquals(420, initial.size());
            session.restoreCoordinator();
            session.restart("s3");
            session.execute(List.of("s3"));
            var result = session.result(System.nanoTime() - started);
            assertEquals(840, result.merged().size());
            assertEquals(420, result.driverOutput().size());
            assertEquals(420, result.windows().size());
            for (var record : result.merged().subList(420, 840)) {
                assertEquals(1, record.revision());
                assertEquals("true", record.header("tsx-final"));
                assertEquals("", record.header("tsx-missing-sites"));
            }
            var stored = session.storedMerged();
            assertEquals(840, stored.size());
            var byPosition = new HashMap<String, org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>>();
            stored.forEach(r -> assertNull(byPosition.put(r.partition() + ":" + r.offset(), r)));
            for (var original : initial) {
                var current = byPosition.get(original.partition() + ":" + original.offset());
                assertNotNull(current);
                assertArrayEquals(original.key(), current.key());
                assertArrayEquals(original.value(), current.value());
                assertEquals(original.timestamp(), current.timestamp());
                assertEquals(AggregateRecord.fromConsumerRecord(original), AggregateRecord.fromConsumerRecord(current));
            }
        }
    }
}
