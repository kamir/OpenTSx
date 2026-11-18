package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConformanceHandshakeTest {
    @TempDir Path temporary;

    private ConformanceHandshake.QualifiedRuntime pin(String site) {
        return new ConformanceHandshake.QualifiedRuntime(site, "node-" + site, "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
    }

    @Test void wrongFingerprintAndCollidingSeriesRefuseWithExactChecks() {
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        var challenge = ConformanceHandshake.challenge(plan, pin("s1"));
        ObjectNode report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("topics").get(0)).putArray("fingerprints").add("0000000000000000");
        assertEquals("topics.fingerprints", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        report = SiteTestData.report(challenge, "0123456789abcdef");
        assertEquals("series.disjoint", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of("0123456789abcdef"), SiteTestData.CLOCK).path("check").asText());
        assertEquals("accepted", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("result").asText());
    }

    @Test void duplicateMissingAndUndetailedChecksCannotSatisfyAdmission() {
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        var challenge = ConformanceHandshake.challenge(plan, pin("s1"));
        ObjectNode report = SiteTestData.report(challenge, "0123456789abcdef");
        var checks = report.putArray("checks");
        for (int index = 0; index < 10; index++) checks.addObject().put("id", "vectors.wire").put("result", "pass").put("detail", "executed synthetic check");
        assertEquals("vectors.wire", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("checks").get(2)).put("detail", " ");
        assertEquals("vectors.operators", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        report = ConformanceHandshake.report(challenge, report.path("topics"), Set.of(), Map.of(), SiteTestData.CLOCK);
        assertEquals("vectors.wire", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
    }

    @Test void staleUnqualifiedAndChangedAdmissionContextsRefuse() {
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        ObjectNode report = SiteTestData.report(ConformanceHandshake.challenge(plan, pin("s1")), "0123456789abcdef");
        var unknown = new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "3.0.0", "sha256:" + "b".repeat(64), SiteTestData.ABI);
        assertEquals("runtime.identity", ConformanceHandshake.validate(SiteJson.bytes(report), plan, unknown, Set.of(), SiteTestData.CLOCK).path("check").asText());
        report.put("expires", SiteTestData.CLOCK.instant().toString());
        assertEquals("runtime.identity", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        ObjectNode changed = SiteTestData.document("wind", 1, "s1");
        ((ObjectNode) changed.path("federation")).put("maxDelay", "PT2M");
        RunnerPlan other = RunnerPlan.parse(SiteJson.bytes(changed));
        assertNotEquals(ConformanceHandshake.challenge(plan, pin("s1")).path("contractHash"), ConformanceHandshake.challenge(other, pin("s1")).path("contractHash"));
    }

    @Test void seriesCountOrderingAndTopicPartitionsAreCheckedSemantically() {
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        var challenge = ConformanceHandshake.challenge(plan, pin("s1"));
        ObjectNode report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("series")).put("count", 2);
        assertEquals("series.disjoint", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("topics").get(0)).put("partitions", 2);
        assertEquals("topics.partitions", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
    }

    @Test void serviceRequiresQualifiedPinsSignedReportsAndExactCertificateSites() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of("a".repeat(64), "s1"),
                temporary.resolve("driver"), SiteTestData.CLOCK, (p, v) -> fail("unexpected status"), (p, v) -> fail("unexpected aggregate"))) {
            service.publish(SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), driver.getPrivate()));
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.challenge("s1", "wind")).status());
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.next("s1", "0")).status());
            var report = SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef");
            assertThrows(PlanRefusal.class, () -> service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), driver.getPrivate())));
            assertEquals("accepted", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("result").asText());
            assertEquals(401, assertThrows(TsxPlanService.HttpFailure.class, () -> service.request("GET", "/v1/sites/s1/plans", "after=0", "b".repeat(64), null, new byte[0])).status());
            assertEquals(403, assertThrows(TsxPlanService.HttpFailure.class, () -> service.request("GET", "/v1/sites/s2/plans", "after=0", "a".repeat(64), null, new byte[0])).status());
            assertEquals("1", service.request("GET", "/v1/sites/s1/plans", "after=0", "a".repeat(64), null, new byte[0]).path("next").asText());
        }
    }

    @Test void independentJobVersionsUseDurableSiteDeliverySequenceAndAck() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        String first = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 8, "s1")), driver.getPrivate());
        String second = SignedJws.sign(SiteJson.bytes(SiteTestData.document("solar", 1, "s1")), driver.getPrivate());
        ObjectNode ack;
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(), temporary.resolve("driver"), SiteTestData.CLOCK, (p, v) -> { }, (p, v) -> { })) {
            assertEquals("1", service.publish(first));
            assertEquals("1", service.publish(first));
            assertEquals("2", service.publish(second));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            for (String job : Set.of("wind", "solar")) service.conformance("s1", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s1", job), "0123456789abcdef")), site.getPrivate()));
            assertEquals(8, service.next("s1", "0").path("deliveries").get(0).path("planVersion").asInt());
            assertEquals(1, service.next("s1", "1").path("deliveries").get(0).path("planVersion").asInt());
            ack = SiteJson.JSON.createObjectNode().put("job", "wind").put("planVersion", 8).put("commandHash", RunnerPlan.verify(first, driver.getPublic()).commandHash()).put("result", "accepted");
            service.ack("s1", "1", ack);
            service.ack("s1", "1", ack);
            ObjectNode bad = ack.deepCopy().put("planVersion", 1);
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.ack("s1", "1", bad)).status());
            assertThrows(PlanRefusal.class, () -> service.next("s1", "01"));
        }
        try (var restored = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(), temporary.resolve("driver"), SiteTestData.CLOCK, (p, v) -> { }, (p, v) -> { })) {
            assertEquals("2", restored.publish(second));
            restored.ack("s1", "1", ack);
        }
    }

    @Test void signedExpectedMembershipAndScopesMustAgreeAcrossSites() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var first = SiteTestData.document("wind", 1, "s1");
        var second = SiteTestData.document("wind", 1, "s2");
        SiteTestData.membership(first, second);
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", site.getPublic()), Map.of(), temporary.resolve("driver"), SiteTestData.CLOCK, (p, v) -> { }, (p, v) -> { })) {
            service.publish(SignedJws.sign(SiteJson.bytes(first), driver.getPrivate()));
            ((ObjectNode) second.path("federation")).put("maxDelay", "PT2M");
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(SignedJws.sign(SiteJson.bytes(second), driver.getPrivate()))).status());
        }
    }

    @Test void outputTopicBindingPartitionsAndEncoderFingerprintAreRequired() {
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        var challenge = ConformanceHandshake.challenge(plan, pin("s1"));
        ObjectNode report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("topics").get(1)).put("binding", "site");
        assertEquals("topics.names", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("topics").get(1)).put("partitions", 2);
        assertEquals("topics.partitions", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
        report = SiteTestData.report(challenge, "0123456789abcdef");
        ((ObjectNode) report.path("topics").get(1)).putArray("fingerprints").add("8985f02b48c77195");
        assertEquals("topics.fingerprints", ConformanceHandshake.validate(SiteJson.bytes(report), plan, pin("s1"), Set.of(), SiteTestData.CLOCK).path("check").asText());
    }

    @Test void incompleteStatusIsRefusedBeforeItsCallback() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(),
                temporary.resolve("status-driver"), SiteTestData.CLOCK, (plan, value) -> received.incrementAndGet(), (plan, value) -> { })) {
            service.publish(SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.conformance("s1", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef")), site.getPrivate()));
            ObjectNode incomplete = SiteJson.JSON.createObjectNode().put("schema", "opentsx.dag-status.v1")
                    .put("site", "s1").put("job", "wind").put("planVersion", 1);
            assertEquals(400, assertThrows(TsxPlanService.HttpFailure.class, () -> service.status("s1", "wind", incomplete)).status());
            assertEquals(0, received.get());
        }
    }


    private static ObjectNode status(String state) {
        ObjectNode value = SiteJson.JSON.createObjectNode().put("schema", "opentsx.dag-status.v1").put("dag", "wind").put("job", "wind")
                .put("site", "s1").put("planVersion", 1).put("track", "A").put("specHash", SiteTestData.document("wind", 1, "s1").path("specHash").asText())
                .put("state", state).put("records", state.equals("stopped") ? 5 : 0).putNull("watermark").put("skippedBlocks", 0)
                .put("at", SiteTestData.CLOCK.instant().toString());
        value.putArray("waitingFor");
        value.putObject("artifact").put("version", "3.0.0").put("digest", SiteTestData.DIGEST);
        return value;
    }

    @Test void callbacksDoNotHoldTheAdmissionMonitorAndReplacementWaitsForTheirAcknowledgement() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        TsxPlanService[] holder = new TsxPlanService[1];
        String newer = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 2, "s1")), driver.getPrivate());
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(), temporary.resolve("callbacks"), SiteTestData.CLOCK,
                (plan, value) -> {
                    assertFalse(Thread.holdsLock(holder[0]));
                    assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> holder[0].publish(newer)).status());
                    callbacks.incrementAndGet();
                }, (plan, value) -> {
                    assertFalse(Thread.holdsLock(holder[0]));
                    assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> holder[0].publish(newer)).status());
                    holder[0].status("s1", "wind", status("stopped"));
                    assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> holder[0].publish(newer)).status());
                    callbacks.incrementAndGet();
                })) {
            holder[0] = service;
            service.publish(SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.conformance("s1", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef")), site.getPrivate()));
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(newer)).status());
            ObjectNode delivery = SiteJson.JSON.createObjectNode().put("schema", "opentsx.aggregate-delivery.v1");
            delivery.putArray("records").addObject().putObject("headers").put("tsx-site", "s1").put("tsx-job", "wind").put("tsx-plan-version", "1");
            service.aggregates("s1", "wind", delivery);
            assertEquals(2, callbacks.get());
            assertEquals("2", service.publish(newer));
        }
    }

    @Test void malformedCountersArtifactAndFailedStatusCannotReachTheirCallback() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(), temporary.resolve("bad-status"),
                SiteTestData.CLOCK, (plan, value) -> received.incrementAndGet(), (plan, value) -> { })) {
            service.publish(SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.conformance("s1", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef")), site.getPrivate()));
            for (String field : java.util.List.of("records", "skippedBlocks")) {
                ObjectNode invalid = status("running").put(field, -1);
                assertEquals(400, assertThrows(TsxPlanService.HttpFailure.class, () -> service.status("s1", "wind", invalid)).status());
            }
            ObjectNode artifact = status("running");
            ((ObjectNode) artifact.path("artifact")).put("digest", "sha256:" + "b".repeat(64));
            assertEquals(400, assertThrows(TsxPlanService.HttpFailure.class, () -> service.status("s1", "wind", artifact)).status());
            assertEquals(400, assertThrows(TsxPlanService.HttpFailure.class, () -> service.status("s1", "wind", status("failed"))).status());
            assertEquals(0, received.get());
            service.status("s1", "wind", status("running").put("extraReaderField", true));
            assertEquals(1, received.get());
        }
    }


    @Test void aNewJobGenerationWaitsForEveryPreviousSiteToStop() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        ObjectNode first = SiteTestData.document("wind", 1, "s1"), second = SiteTestData.document("wind", 1, "s2");
        SiteTestData.membership(first, second);
        ObjectNode nextFirst = SiteTestData.document("wind", 2, "s1"), nextSecond = SiteTestData.document("wind", 2, "s2");
        SiteTestData.membership(nextFirst, nextSecond);
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", site.getPublic()), Map.of(),
                temporary.resolve("job-generations"), SiteTestData.CLOCK, (plan, value) -> { }, (plan, value) -> { })) {
            service.publish(SignedJws.sign(SiteJson.bytes(first), driver.getPrivate()));
            service.publish(SignedJws.sign(SiteJson.bytes(second), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.registerQualifiedRuntime("s2", "node-s2", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.conformance("s1", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef")), site.getPrivate()));
            service.conformance("s2", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s2", "wind"), "fedcba9876543210")), site.getPrivate()));
            service.status("s1", "wind", status("stopped"));
            String next = SignedJws.sign(SiteJson.bytes(nextFirst), driver.getPrivate());
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(next)).status());
            ObjectNode secondStopped = status("stopped").put("site", "s2");
            service.status("s2", "wind", secondStopped);
            assertEquals("2", service.publish(next));
        }
    }


    @Test void anUnacknowledgedTerminalStatusCannotRetireItsAssignment() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        RuntimeException unavailable = new IllegalStateException("status Kafka acknowledgement failed");
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(), temporary.resolve("status-ack"),
                SiteTestData.CLOCK, (plan, value) -> { throw unavailable; }, (plan, value) -> { })) {
            service.publish(SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.conformance("s1", SignedJws.sign(SiteJson.bytes(SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef")), site.getPrivate()));
            assertSame(unavailable, assertThrows(RuntimeException.class, () -> service.status("s1", "wind", status("stopped"))));
            String next = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 2, "s1")), driver.getPrivate());
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> service.publish(next)).status());
        }
    }

}
