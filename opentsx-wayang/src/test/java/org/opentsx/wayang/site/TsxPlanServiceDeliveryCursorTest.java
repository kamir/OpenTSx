package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-020 U01 delivery cursor (S5.3): a site pages by its own delivery sequence, not by a job's plan version. Job B at
 * version 1, published after job A at version 10, is the next delivery; a restarted service keeps both sequences, and
 * a repeated identical acknowledgement is accepted while a changed one is refused.
 */
class TsxPlanServiceDeliveryCursorTest {

    @TempDir Path directory;
    private static final String CERTIFICATE = "c".repeat(64);

    @Test void jobBVersionOneFollowsJobAVersionTenThroughRestartAndDuplicateAcknowledgement() throws Exception {
        KeyPair driver = SiteTestData.key();
        KeyPair site = SiteTestData.key();
        var clock = new SiteStatusTest.FakeClock();
        String a10 = SignedJws.sign(SiteJson.bytes(SiteTestData.document("alpha", 10, "s1")), driver.getPrivate());
        String b1 = SignedJws.sign(SiteJson.bytes(SiteTestData.document("beta", 1, "s1")), driver.getPrivate());
        ObjectNode ackA = acknowledgement(a10, driver);
        ObjectNode ackB = acknowledgement(b1, driver);

        try (var service = open(driver, site, clock)) {
            assertEquals("1", service.publish(a10));
            assertEquals("2", service.publish(b1));
            qualify(service, site, "alpha", "0123456789abcdef", clock);
            qualify(service, site, "beta", "fedcba9876543210", clock);
            JsonNode first = service.next("s1", "0");
            assertEquals("1", first.path("next").asText());
            assertEquals("alpha", first.path("deliveries").get(0).path("job").asText());
            assertEquals(10, first.path("deliveries").get(0).path("planVersion").asInt());
            assertNull(acknowledge(service, "1", ackA));
            JsonNode second = service.next("s1", "1");
            assertEquals("2", second.path("next").asText());
            assertEquals("beta", second.path("deliveries").get(0).path("job").asText());
            assertEquals(1, second.path("deliveries").get(0).path("planVersion").asInt());
            // the acknowledgement of sequence 2 belongs to job B; job A's identity is refused there
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> acknowledge(service, "2", ackA)).status());
            assertNull(acknowledge(service, "2", ackB));
        }

        try (var service = open(driver, site, clock)) {
            qualify(service, site, "alpha", "0123456789abcdef", clock);
            qualify(service, site, "beta", "fedcba9876543210", clock);
            // republishing returns the existing sequences; nothing new is delivered after 2
            assertEquals("1", service.publish(a10));
            assertEquals("2", service.publish(b1));
            JsonNode after = service.next("s1", "2");
            assertEquals("2", after.path("next").asText());
            assertEquals(0, after.path("deliveries").size());
            // a site that lost its cursor after sequence 1 receives job B again, not job A
            assertEquals("beta", service.next("s1", "1").path("deliveries").get(0).path("job").asText());
            assertNull(acknowledge(service, "2", ackB.deepCopy()));
            ObjectNode changed = ackB.deepCopy().put("result", "refused").put("code", "plan.busy").put("message", "busy");
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> acknowledge(service, "2", changed)).status());
            assertEquals("alpha", service.next("s1", "0").path("deliveries").get(0).path("job").asText());
        }
    }

    private TsxPlanService open(KeyPair driver, KeyPair site, SiteStatusTest.FakeClock clock) throws Exception {
        return new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(CERTIFICATE, "s1"),
                directory, clock, (plan, value) -> { }, (plan, value) -> fail("unexpected aggregate"));
    }

    private static void qualify(TsxPlanService service, KeyPair key, String job, String series, SiteStatusTest.FakeClock clock) {
        service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
        var report = SiteTestData.report(service.challenge("s1", job), series);
        report.put("at", clock.instant().toString()).put("expires", clock.instant().plusSeconds(60).toString());
        assertEquals("accepted", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), key.getPrivate())).path("result").asText());
    }

    private static ObjectNode acknowledgement(String signed, KeyPair driver) {
        RunnerPlan plan = RunnerPlan.verify(signed, driver.getPublic());
        return SiteJson.JSON.createObjectNode().put("job", plan.job()).put("planVersion", plan.planVersion())
                .put("commandHash", plan.commandHash()).put("result", "accepted");
    }

    private static JsonNode acknowledge(TsxPlanService service, String sequence, JsonNode body) {
        return service.request("POST", "/v1/sites/s1/deliveries/" + sequence + "/ack", null, CERTIFICATE, "application/json",
                SiteJson.bytes(body));
    }
}
