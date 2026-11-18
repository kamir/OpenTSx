package org.opentsx.wayang.federation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.wayang.TsxCheckpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.wayang.federation.FederationFixtures.*;

class CompletenessCoordinatorTest {

    @TempDir Path directory;

    @Test void allSixArrivalOrdersProduceTheSameFinalRecord() {
        var inventory = inventory();
        AggregateRecord baseline = null;
        var orders = List.of(List.of("s1", "s2", "s3"), List.of("s1", "s3", "s2"), List.of("s2", "s1", "s3"),
                List.of("s2", "s3", "s1"), List.of("s3", "s1", "s2"), List.of("s3", "s2", "s1"));
        int i = 0;
        for (var order : orders) {
            var coordinator = new CompletenessCoordinator(inventory, Duration.ofSeconds(5), 0.01, new MutableClock(), directory.resolve("order-" + i++));
            for (int n = 0; n < 3; n++) {
                String site = order.get(n);
                var result = coordinator.accept(partial(inventory, site, 0, Integer.parseInt(site.substring(1)), Double.NaN));
                assertEquals(n == 2 ? 1 : 0, result.size());
                if (n == 2) {
                    var merged = result.get(0);
                    assertEquals("true", merged.header("tsx-final"));
                    assertEquals("", merged.header("tsx-missing-sites"));
                    assertEquals(0, merged.revision());
                    assertEquals("wind|" + GROUP + "|PT10M|0", merged.key());
                    assertEquals(3, merged.statistics().getValidCount());
                    assertEquals(3, merged.statistics().getNanCount());
                    if (baseline == null) baseline = merged;
                    else assertEquals(baseline, merged);
                }
            }
        }
    }

    @Test void timeoutThenRestartAndLateMemberAppendANewRevision() throws Exception {
        var inventory = inventory();
        var clock = new MutableClock();
        Path state = directory.resolve("merge.json");
        var coordinator = new CompletenessCoordinator(inventory, Duration.ofSeconds(5), 0.01, clock, state);
        assertTrue(coordinator.accept(partial(inventory, "s2", 0, 2)).isEmpty());
        assertTrue(coordinator.accept(partial(inventory, "s1", 0, 1)).isEmpty());
        clock.now = Instant.ofEpochSecond(605);
        assertTrue(coordinator.advance().isEmpty());
        clock.now = clock.now.plusNanos(1000);
        var incomplete = coordinator.advance().get(0);
        assertEquals("false", incomplete.header("tsx-final"));
        assertEquals("s3", incomplete.header("tsx-missing-sites"));
        assertEquals(0, incomplete.revision());
        byte[] initial = incomplete.value();
        assertTrue(coordinator.advance().isEmpty());
        coordinator = new CompletenessCoordinator(inventory, Duration.ofSeconds(5), 0.01, clock, state);
        assertEquals(List.of(incomplete), coordinator.pending());
        coordinator.acknowledge(incomplete);
        coordinator.acknowledge(incomplete);
        var complete = coordinator.accept(partial(inventory, "s3", 0, 3)).get(0);
        assertEquals("true", complete.header("tsx-final"));
        assertEquals(1, complete.revision());
        assertArrayEquals(initial, incomplete.value());
        assertEquals(2, incomplete.statistics().getValidCount());
        assertEquals(3, complete.statistics().getValidCount());
        var restored = new CompletenessCoordinator(inventory, Duration.ofSeconds(5), 0.01, clock, state);
        assertEquals(List.of(complete), restored.pending());
        assertTrue(restored.accept(partial(inventory, "s3", 0, 3)).isEmpty());
        restored.acknowledge(complete);
        assertTrue(new CompletenessCoordinator(inventory, Duration.ofSeconds(5), 0.01, clock, state).pending().isEmpty());
        assertTrue(Files.size(state) > 0);
    }

    @Test void missingAllMembersStillEmitsEmptyWireNanWithoutClaimingFinality() {
        var clock = new MutableClock();
        clock.now = Instant.ofEpochSecond(3601);
        var coordinator = new CompletenessCoordinator(inventory(), Duration.ZERO, 0.01, clock, directory.resolve("empty"));
        var emitted = coordinator.advance();
        assertEquals(2, emitted.size());
        for (var record : emitted) {
            assertEquals("false", record.header("tsx-final"));
            assertEquals("s1,s2,s3", record.header("tsx-missing-sites"));
            assertEquals(0, record.statistics().getValidCount());
            assertTrue(Double.isNaN(record.statistics().getMean()));
            assertTrue(Double.isNaN(record.statistics().getMin()));
            assertTrue(Double.isNaN(record.statistics().getM2()));
        }
        assertTrue(coordinator.advance().isEmpty());
    }

    @Test void duplicateConflictStaleAndReplacementDoNotDoubleCount() throws Exception {
        var inventory = inventory();
        Path state = directory.resolve("dedup");
        var coordinator = new CompletenessCoordinator(inventory, Duration.ZERO, 0.01, new MutableClock(), state);
        var first = partial(inventory, "s1", 0, 1);
        coordinator.accept(first);
        byte[] before = Files.readAllBytes(state);
        assertTrue(coordinator.accept(first).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> coordinator.accept(partial(inventory, "s1", 0, 2)));
        assertThrows(IllegalArgumentException.class, () -> coordinator.accept(partial(inventory, "s2", 1, 2)));
        assertArrayEquals(before, Files.readAllBytes(state));
        coordinator.accept(partial(inventory, "s1", 2, 4, 5));
        assertThrows(IllegalArgumentException.class, () -> coordinator.accept(partial(inventory, "s1", 1, 4)));
        coordinator.accept(partial(inventory, "s2", 0, 2));
        var finalRecord = coordinator.accept(partial(inventory, "s3", 0, 3)).get(0);
        assertEquals(4, finalRecord.statistics().getValidCount());
        assertEquals(3.5, finalRecord.statistics().getMean());
        assertThrows(IllegalArgumentException.class, () -> coordinator.acknowledge(AggregateRecord.merged(inventory, 99, statistics("PT10M", 1), List.of())));
    }

    @Test void failedPersistencePoisonsTheInstanceAndIncompatibleStateIsRejected() throws Exception {
        Path parent = directory.resolve("file");
        Files.writeString(parent, "not a directory");
        var inventory = inventory();
        var coordinator = new CompletenessCoordinator(inventory, Duration.ZERO, 0.01, new MutableClock(), parent.resolve("state"));
        assertThrows(IllegalStateException.class, () -> coordinator.accept(partial(inventory, "s1", 0, 1)));
        assertThrows(IllegalStateException.class, coordinator::pending);
        Path state = directory.resolve("wrong-context");
        new TsxCheckpoint("wind", 1, "0".repeat(64), "aggregate-merge", Map.of(), Map.of()).write(state);
        assertThrows(IllegalStateException.class, () -> new CompletenessCoordinator(inventory, Duration.ZERO, 0.01, new MutableClock(), state));
        Files.writeString(state, "{}");
        assertThrows(IllegalStateException.class, () -> new CompletenessCoordinator(inventory, Duration.ZERO, 0.01, new MutableClock(), state));
    }

    @Test void exactNanOnlyEmptyAndNonemptyPayloadsSurvivePendingRestart() {
        var inventory = inventory();
        var clock = new MutableClock();
        Path state = directory.resolve("nan");
        var coordinator = new CompletenessCoordinator(inventory, Duration.ZERO, 0.01, clock, state);
        coordinator.accept(partial(inventory, "s1", 0, Double.NaN));
        coordinator.accept(partial(inventory, "s2", 0));
        var merged = coordinator.accept(partial(inventory, "s3", 0)).get(0);
        assertEquals(0, merged.statistics().getValidCount());
        assertEquals(1, merged.statistics().getNanCount());
        assertTrue(Double.isNaN(merged.statistics().getMean()));
        var restored = new CompletenessCoordinator(inventory, Duration.ZERO, 0.01, clock, state).pending().get(0);
        assertEquals(merged, restored);
        assertArrayEquals(new OpenTsxAvro().encode(merged.statistics()), restored.value());
        assertThrows(IllegalArgumentException.class, () -> AggregateRecord.toDelivery(List.of(merged)));
    }
}
