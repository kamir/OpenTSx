package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TsxPlanServiceRetirementTest {
    @TempDir Path directory;
    private static final String CERTIFICATE = "c".repeat(64);

    @Test void replacementWaitsForTheDurableDeliveryAckAndLostRepliesRemainRecoverable() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var other = SiteTestData.key();
        var clock = new SiteStatusTest.FakeClock();
        var callbacks = new AtomicInteger();
        var first = SiteTestData.document("wind", 1, "s1");
        String original = SignedJws.sign(SiteJson.bytes(first), driver.getPrivate());
        String replacement = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 2, "s1")), driver.getPrivate());
        var terminal = stopped(first);
        var ack = acknowledgement(original, driver);
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", other.getPublic()),
                Map.of(CERTIFICATE, "s1", "d".repeat(64), "s2"), directory, clock,
                (plan, value) -> callbacks.incrementAndGet(), (plan, value) -> fail("unexpected aggregate"))) {
            assertEquals("1", service.publish(original));
            qualify(service, "s1", site, "0123456789abcdef", clock);
            assertNull(status(service, terminal));
            var busy = assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(replacement));
            assertEquals(409, busy.status());
            assertTrue(busy.getMessage().startsWith("plan.busy:"));
            assertEquals("1", service.publish(original));
            clock.now = clock.now.plusSeconds(2);
            assertNull(status(service, terminal));
            assertEquals(1, callbacks.get());
            var changed = ack.deepCopy().put("commandHash", "b".repeat(64));
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> acknowledge(service, changed, CERTIFICATE)).status());
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(replacement)).status());
            assertThrows(IOException.class, () -> {
                acknowledge(service, ack, CERTIFICATE);
                throw new IOException("synthetic acknowledgement response lost");
            });
        }
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", other.getPublic()),
                Map.of(CERTIFICATE, "s1", "d".repeat(64), "s2"), directory, clock,
                (plan, value) -> callbacks.incrementAndGet(), (plan, value) -> fail("unexpected aggregate"))) {
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            assertEquals("2", service.publish(replacement));
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> status(service, terminal)).status());
            assertEquals(401, assertThrows(TsxPlanService.HttpFailure.class, () -> acknowledge(service, ack, null)).status());
            assertEquals(403, assertThrows(TsxPlanService.HttpFailure.class, () -> acknowledge(service, ack, "d".repeat(64))).status());
            assertNull(acknowledge(service, ack, CERTIFICATE));
            assertNull(acknowledge(service, ack.deepCopy(), CERTIFICATE));
            assertEquals(1, callbacks.get());
            qualify(service, "s1", site, "0123456789abcdef", clock);
            JsonNode next = service.next("s1", "1");
            assertEquals("2", next.path("next").asText());
            assertEquals(2, next.path("deliveries").get(0).path("planVersion").asInt());
        }
    }

    @Test void aNewGenerationWaitsForEveryPreviousSitesDeliveryAcknowledgement() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var first = SiteTestData.document("wind", 1, "s1");
        var second = SiteTestData.document("wind", 1, "s2");
        SiteTestData.membership(first, second);
        var nextFirst = SiteTestData.document("wind", 2, "s1");
        var nextSecond = SiteTestData.document("wind", 2, "s2");
        SiteTestData.membership(nextFirst, nextSecond);
        String firstSigned = SignedJws.sign(SiteJson.bytes(first), driver.getPrivate());
        String secondSigned = SignedJws.sign(SiteJson.bytes(second), driver.getPrivate());
        String replacement = SignedJws.sign(SiteJson.bytes(nextFirst), driver.getPrivate());
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", site.getPublic()),
                Map.of(), directory, SiteTestData.CLOCK, (plan, value) -> { }, (plan, value) -> fail("unexpected aggregate"))) {
            assertEquals("1", service.publish(firstSigned));
            assertEquals("1", service.publish(secondSigned));
            qualify(service, "s1", site, "0123456789abcdef", SiteTestData.CLOCK);
            qualify(service, "s2", site, "fedcba9876543210", SiteTestData.CLOCK);
            service.status("s1", "wind", stopped(first));
            service.status("s2", "wind", stopped(second));
            service.ack("s1", "1", acknowledgement(firstSigned, driver));
            var busy = assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(replacement));
            assertEquals(409, busy.status());
            assertTrue(busy.getMessage().startsWith("plan.busy:"));
            service.ack("s2", "1", acknowledgement(secondSigned, driver));
            assertEquals("2", service.publish(replacement));
            assertEquals("2", service.publish(SignedJws.sign(SiteJson.bytes(nextSecond), driver.getPrivate())));
            service.ack("s2", "1", acknowledgement(secondSigned, driver));
        }
    }

    private static void qualify(TsxPlanService service, String site, KeyPair key, String series, Clock clock) {
        service.registerQualifiedRuntime(site, "node-" + site, "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
        var report = SiteTestData.report(service.challenge(site, "wind"), series);
        report.put("at", clock.instant().toString()).put("expires", clock.instant().plusSeconds(1).toString());
        assertEquals("accepted", service.conformance(site, SignedJws.sign(SiteJson.bytes(report), key.getPrivate())).path("result").asText());
    }

    private static ObjectNode acknowledgement(String signed, KeyPair driver) {
        RunnerPlan plan = RunnerPlan.verify(signed, driver.getPublic());
        return SiteJson.JSON.createObjectNode().put("job", plan.job()).put("planVersion", plan.planVersion())
                .put("commandHash", plan.commandHash()).put("result", "accepted");
    }

    private static JsonNode acknowledge(TsxPlanService service, JsonNode body, String certificate) {
        return service.request("POST", "/v1/sites/s1/deliveries/1/ack", null, certificate, "application/json", SiteJson.bytes(body));
    }

    private static JsonNode status(TsxPlanService service, JsonNode body) {
        return service.request("POST", "/v1/sites/s1/jobs/wind/status", null, CERTIFICATE, "application/json", SiteJson.bytes(body));
    }

    private static ObjectNode stopped(JsonNode document) {
        var value = SiteJson.JSON.createObjectNode().put("schema", "opentsx.dag-status.v1")
                .put("dag", "wind").put("job", "wind").put("site", document.path("site").asText()).put("planVersion", 1)
                .put("track", "A").put("specHash", document.path("specHash").asText()).put("state", "stopped")
                .put("records", 5).putNull("watermark").put("skippedBlocks", 0).put("at", SiteTestData.CLOCK.instant().toString());
        value.putArray("waitingFor");
        value.putObject("artifact").put("version", "3.0.0").put("digest", SiteTestData.DIGEST);
        return value;
    }
}
