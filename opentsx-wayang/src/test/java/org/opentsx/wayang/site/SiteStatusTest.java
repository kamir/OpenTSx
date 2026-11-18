package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SiteStatusTest {
    @TempDir Path temporary;

    private static ConformanceHandshake.QualifiedRuntime runtime() {
        return new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
    }

    @Test void durableIntentPrecedesRunningAndLongCallbacksHeartbeatEveryThirtySeconds() throws Exception {
        var key = SiteTestData.key();
        Clock clock = new FakeClock();
        FakeScheduler scheduler = new FakeScheduler((FakeClock) clock);
        String signed = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), key.getPrivate());
        List<JsonNode> statuses = new ArrayList<>();
        try (var agent = new TsxSiteAgent(runtime(), key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), clock, scheduler)) {
            JsonNode outcome = agent.accept(signed, plan -> agent.runWithStatus(plan, admitted -> {
                scheduler.advance(Duration.ofSeconds(95));
                ObjectNode receipt = SiteJson.JSON.createObjectNode().put("inputRecords", 5);
                agent.checkpoint(admitted, SiteJson.JSON.createObjectNode(), SiteJson.JSON.createObjectNode(), receipt);
                return receipt;
            }, value -> {
                try {
                    JsonNode persisted = SiteJson.parse(Files.readAllBytes(temporary.resolve("site/state.json")), 1024 * 1024);
                    assertEquals("accepted", persisted.path("jobs").path("wind").path("outcome").path("state").asText());
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                statuses.add(value.deepCopy());
            }));
            assertEquals(List.of("running", "running", "running", "running", "stopped"), statuses.stream().map(s -> s.path("state").asText()).toList());
            assertEquals(List.of(0L, 0L, 0L, 0L, 5L), statuses.stream().map(s -> s.path("records").longValue()).toList());
            assertEquals("completed", outcome.path("state").asText());
            for (JsonNode status : statuses) SiteStatus.validate(status, RunnerPlan.verify(signed, key.getPublic()), runtime());
            for (int index = 1; index < statuses.size(); index++) assertTrue(Duration.between(Instant.parse(statuses.get(index - 1).path("at").asText()),
                    Instant.parse(statuses.get(index).path("at").asText())).compareTo(Duration.ofSeconds(30)) <= 0);
            scheduler.advance(Duration.ofMinutes(1));
            assertEquals(5, statuses.size());
            assertEquals(outcome, agent.accept(signed, plan -> { fail("accepted retry activated"); return null; }));
            assertEquals(5, statuses.size());
        }
    }

    @Test void heartbeatFailurePreventsCheckpointAndCompletedOutcome() throws Exception {
        var key = SiteTestData.key();
        FakeClock clock = new FakeClock();
        FakeScheduler scheduler = new FakeScheduler(clock);
        String signed = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), key.getPrivate());
        RuntimeException heartbeat = new IllegalStateException("heartbeat was not acknowledged");
        List<JsonNode> statuses = new ArrayList<>();
        try (var agent = new TsxSiteAgent(runtime(), key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), clock, scheduler)) {
            RuntimeException actual = assertThrows(RuntimeException.class, () -> agent.accept(signed, plan -> agent.runWithStatus(plan, admitted -> {
                scheduler.advance(Duration.ofSeconds(30));
                agent.checkpoint(admitted, SiteJson.JSON.createObjectNode(), SiteJson.JSON.createObjectNode(), SiteJson.JSON.createObjectNode().put("inputRecords", 5));
                return SiteJson.JSON.createObjectNode().put("inputRecords", 5);
            }, status -> {
                if (status.path("state").asText().equals("running") && !statuses.isEmpty()) throw heartbeat;
                statuses.add(status.deepCopy());
            })));
            assertSame(heartbeat, actual);
            assertEquals(List.of("running", "failed"), statuses.stream().map(s -> s.path("state").asText()).toList());
            assertEquals(heartbeat.getMessage(), statuses.get(1).path("error").asText());
            assertFalse(Files.exists(agent.checkpointPath(RunnerPlan.verify(signed, key.getPublic()))));
            assertEquals("failed", agent.accept(signed, plan -> { fail("failed grant reactivated"); return null; }).path("state").asText());
        }
    }

    @Test void executionErrorRetainsHeartbeatAndFailedStatusErrorsAsSuppressed() {
        FakeClock clock = new FakeClock();
        FakeScheduler scheduler = new FakeScheduler(clock);
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        RuntimeException source = new IllegalStateException("source failed"), heartbeat = new IllegalStateException("heartbeat failed"), terminal = new IllegalStateException("failed status failed");
        List<JsonNode> sent = new ArrayList<>();
        try {
            SiteStatus status = new SiteStatus(plan, runtime(), clock, scheduler, value -> {
                if (value.path("state").asText().equals("failed")) throw terminal;
                if (!sent.isEmpty()) throw heartbeat;
                sent.add(value);
            });
            assertSame(source, assertThrows(RuntimeException.class, () -> status.execute(admitted -> {
                scheduler.advance(Duration.ofSeconds(30));
                throw source;
            })));
            assertArrayEquals(new Throwable[]{heartbeat, terminal}, source.getSuppressed());
        } finally { scheduler.shutdownNow(); }
    }

    @Test void pollingWithoutAnExplicitVersionFailsBeforeAnyClientCall() throws Exception {
        var key = SiteTestData.key();
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI, key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            assertEquals("polling requires an explicit qualified runtime version", assertThrows(IllegalStateException.class,
                    () -> agent.pollOnce(null, plan -> { fail("unqualified polling activated"); return null; })).getMessage());
        }
    }

    @Test void invalidReceiptCannotPublishStoppedOrComplete() {
        FakeClock clock = new FakeClock();
        FakeScheduler scheduler = new FakeScheduler(clock);
        List<JsonNode> statuses = new ArrayList<>();
        try {
            SiteStatus status = new SiteStatus(RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1"))), runtime(), clock, scheduler, statuses::add);
            assertThrows(IllegalArgumentException.class, () -> status.execute(plan -> SiteJson.JSON.createObjectNode().put("inputRecords", -1)));
            assertEquals(List.of("running", "failed"), statuses.stream().map(s -> s.path("state").asText()).toList());
            assertEquals(0, statuses.get(1).path("records").longValue());
        } finally { scheduler.shutdownNow(); }
    }

    static final class FakeClock extends Clock {
        Instant now = SiteTestData.CLOCK.instant();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    static final class FakeScheduler extends ScheduledThreadPoolExecutor {
        final FakeClock clock;
        final Task future = new Task();
        Runnable action;
        Instant next;
        Duration period;
        FakeScheduler(FakeClock clock) { super(1); this.clock = clock; }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            action = command;
            next = clock.now.plusNanos(unit.toNanos(initialDelay));
            this.period = Duration.ofNanos(unit.toNanos(period));
            return future;
        }
        void advance(Duration duration) {
            Instant end = clock.now.plus(duration);
            while (action != null && !future.isCancelled() && !next.isAfter(end)) {
                clock.now = next;
                next = next.plus(period);
                action.run();
            }
            clock.now = end;
        }
    }

    static final class Task extends FutureTask<Void> implements RunnableScheduledFuture<Void> {
        Task() { super(() -> null); }
        @Override public boolean isPeriodic() { return true; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
