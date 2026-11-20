package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TsxPlanServiceStatusReplayTest {
    @TempDir Path directory;
    private static final String CERTIFICATE = "c".repeat(64);

    @Test void stoppedRetryAfterConformanceExpiryUsesTheDurableOutcomeOnce() throws Exception {
        terminalRetry("stopped");
    }

    @Test void failedRetryAfterConformanceExpiryUsesTheDurableOutcomeOnce() throws Exception {
        terminalRetry("failed");
    }

    private void terminalRetry(String terminal) throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var other = SiteTestData.key();
        var clock = new SiteStatusTest.FakeClock();
        var callbacks = new AtomicInteger();
        var document = SiteTestData.document("wind", 1, "s1");
        var status = status(document, terminal);
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", other.getPublic()),
                Map.of(CERTIFICATE, "s1", "d".repeat(64), "s2"), directory, clock,
                (plan, value) -> callbacks.incrementAndGet(), (plan, value) -> fail("unexpected aggregate"))) {
            service.publish(SignedJws.sign(SiteJson.bytes(document), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            var report = SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef");
            report.put("expires", clock.instant().plusSeconds(1).toString());
            assertEquals("accepted", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("result").asText());
            assertNull(request(service, status, CERTIFICATE));
            assertEquals(1, callbacks.get());
        }
        JsonNode recorded = SiteJson.parse(Files.readAllBytes(directory.resolve("state.json")), 1024 * 1024)
                .path("statuses").path("s1").path("wind");
        assertArrayEquals(SiteJson.bytes(status), SiteJson.bytes(recorded));
        clock.now = clock.now.plus(Duration.ofSeconds(2));
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", other.getPublic()),
                Map.of(CERTIFICATE, "s1", "d".repeat(64), "s2"), directory, clock,
                (plan, value) -> callbacks.incrementAndGet(), (plan, value) -> fail("unexpected aggregate"))) {
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            assertNull(request(service, status, CERTIFICATE));
            assertNull(request(service, status.deepCopy(), CERTIFICATE));
            assertEquals(1, callbacks.get());
            assertEquals(401, assertThrows(TsxPlanService.HttpFailure.class, () -> request(service, status, null)).status());
            assertEquals(403, assertThrows(TsxPlanService.HttpFailure.class, () -> request(service, status, "d".repeat(64))).status());
            for (String field : new String[]{"records", "at", "state", "extra", "order"}) {
                ObjectNode changed = status.deepCopy();
                switch (field) {
                    case "records" -> changed.put("records", 6);
                    case "at" -> changed.put("at", clock.instant().toString());
                    case "state" -> changed.put("state", "running");
                    case "extra" -> changed.put("extra", true);
                    case "order" -> {
                        var entries = new ArrayList<Map.Entry<String, JsonNode>>();
                        changed.fields().forEachRemaining(entries::add);
                        Collections.reverse(entries);
                        changed.removeAll();
                        entries.forEach(entry -> changed.set(entry.getKey(), entry.getValue()));
                    }
                    default -> throw new IllegalStateException("unknown test mutation");
                }
                assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> request(service, changed, CERTIFICATE)).status(), field);
            }
            ObjectNode older = status.deepCopy().put("planVersion", 0);
            var stale = assertThrows(TsxPlanService.HttpFailure.class, () -> request(service, older, CERTIFICATE));
            assertEquals(409, stale.status());
            assertEquals("status version is not current", stale.getMessage());
            ObjectNode identity = status.deepCopy().put("specHash", "b".repeat(64));
            assertEquals(400, assertThrows(TsxPlanService.HttpFailure.class, () -> request(service, identity, CERTIFICATE)).status());
            assertEquals(1, callbacks.get());
            assertArrayEquals(SiteJson.bytes(recorded), SiteJson.bytes(SiteJson.parse(Files.readAllBytes(directory.resolve("state.json")), 1024 * 1024)
                    .path("statuses").path("s1").path("wind")));
        }
    }

    private static JsonNode request(TsxPlanService service, JsonNode status, String certificate) {
        return service.request("POST", "/v1/sites/s1/jobs/wind/status", null, certificate,
                "application/json", SiteJson.bytes(status));
    }

    private static ObjectNode status(JsonNode document, String terminal) {
        var value = SiteJson.JSON.createObjectNode().put("schema", "opentsx.dag-status.v1")
                .put("dag", "wind").put("job", "wind").put("site", "s1").put("planVersion", 1)
                .put("track", "A").put("specHash", document.path("specHash").asText()).put("state", terminal)
                .put("records", 5).putNull("watermark").put("skippedBlocks", 0).put("at", SiteTestData.CLOCK.instant().toString());
        value.putArray("waitingFor");
        value.putObject("artifact").put("version", "3.0.0").put("digest", SiteTestData.DIGEST);
        if (terminal.equals("failed")) value.put("error", "synthetic execution failure");
        return value;
    }
}
