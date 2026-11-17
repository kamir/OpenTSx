package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TsxSiteAgentPlanTest {
    @TempDir Path temporary;

    @Test void sixBadPlansRefuseWithExactCodesAndAcceptedRetriesNeverReactivate() throws Exception {
        var driver = SiteTestData.key();
        var doc = SiteTestData.document("wind", 2, "s1");
        String signed = SignedJws.sign(SiteJson.bytes(doc), driver.getPrivate());
        AtomicInteger executions = new AtomicInteger();
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                driver.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            String[] parts = signed.split("\\.");
            String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
            assertEquals("plan.signature", assertThrows(PlanRefusal.class, () -> agent.accept(tampered, p -> null)).code());
            String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + "." + parts[2];
            assertEquals("plan.signature", assertThrows(PlanRefusal.class, () -> agent.accept(none, p -> null)).code());
            var expired = doc.deepCopy().put("expires", "2026-11-17T01:00:00Z");
            assertEquals("plan.expired", assertThrows(PlanRefusal.class, () -> agent.accept(SignedJws.sign(SiteJson.bytes(expired), driver.getPrivate()), p -> null)).code());
            var other = SiteTestData.document("wind", 2, "s2");
            assertEquals("plan.site", assertThrows(PlanRefusal.class, () -> agent.accept(SignedJws.sign(SiteJson.bytes(other), driver.getPrivate()), p -> null)).code());
            var result = agent.accept(signed, p -> { executions.incrementAndGet(); return SiteJson.JSON.createObjectNode().put("records", 5); });
            assertEquals(result, agent.accept(signed, p -> { fail("accepted retry reactivated"); return null; }));
            var old = SiteTestData.document("wind", 1, "s1");
            assertEquals("plan.version", assertThrows(PlanRefusal.class, () -> agent.accept(SignedJws.sign(SiteJson.bytes(old), driver.getPrivate()), p -> null)).code());
            var changed = doc.deepCopy().put("expires", "2026-11-19T00:00:00Z");
            assertEquals("plan.version", assertThrows(PlanRefusal.class, () -> agent.accept(SignedJws.sign(SiteJson.bytes(changed), driver.getPrivate()), p -> null)).code());
        }
        Clock afterExpiry = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
        try (var restored = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                driver.getPublic(), SiteTestData.policy(), temporary.resolve("site"), afterExpiry)) {
            assertEquals("completed", restored.accept(signed, p -> { fail("expired accepted retry reactivated"); return null; }).path("state").asText());
        }
        assertEquals(1, executions.get());
    }

    @Test void unknownKeysPaddingHeaderChangesAndMalformedUtf8Reject() throws Exception {
        var driver = SiteTestData.key();
        var other = SiteTestData.key();
        byte[] payload = SiteJson.bytes(SiteTestData.document("wind", 1, "s1"));
        String signed = SignedJws.sign(payload, driver.getPrivate());
        assertArrayEquals(payload, SignedJws.verify(signed, driver.getPublic()));
        assertEquals("plan.signature", assertThrows(PlanRefusal.class, () -> SignedJws.verify(signed, other.getPublic())).code());
        assertThrows(PlanRefusal.class, () -> SignedJws.verify(signed + "=", driver.getPublic()));
        String alternate = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"EdDSA\",\"jku\":\"https://invalid.example\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(PlanRefusal.class, () -> SignedJws.verify(alternate + signed.substring(signed.indexOf('.')), driver.getPublic()));
        assertThrows(PlanRefusal.class, () -> SignedJws.sign(new byte[]{(byte) 0xc3, 0x28}, driver.getPrivate()));
        assertThrows(PlanRefusal.class, () -> SignedJws.sign("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8), driver.getPrivate()));
    }

    @Test void retainedCheckpointMustMatchExactAdmittedIdentity() throws Exception {
        var key = SiteTestData.key();
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            Path path = agent.checkpointPath(plan);
            Files.createDirectories(path.getParent());
            ObjectNode saved = SiteJson.JSON.createObjectNode().put("schema", "opentsx.site-checkpoint.v1");
            saved.set("identity", agent.checkpointIdentity(plan));
            saved.putObject("positions");
            saved.putObject("state");
            saved.putObject("receipt");
            SiteState.write(path, saved);
            assertDoesNotThrow(() -> agent.requireContinuation(plan, path));
            ((ObjectNode) saved.path("identity")).put("stateAbi", "incompatible");
            SiteState.write(path, saved);
            assertEquals("plan.invalid", assertThrows(PlanRefusal.class, () -> agent.requireContinuation(plan, path)).code());
            assertThrows(PlanRefusal.class, () -> agent.requireContinuation(plan, temporary.resolve("outside.json")));
        }
    }

    @Test void persistedIntentSurvivesFailureAndRequiresExplicitCompatibleRecovery() throws Exception {
        var key = SiteTestData.key();
        String signed = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), key.getPrivate());
        AtomicInteger calls = new AtomicInteger();
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            assertThrows(IllegalStateException.class, () -> agent.accept(signed, p -> {
                calls.incrementAndGet();
                assertTrue(Files.exists(temporary.resolve("site/state.json")));
                throw new IllegalStateException("synthetic execution failure");
            }));
        }
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            assertEquals("failed", agent.accept(signed, p -> { calls.incrementAndGet(); return SiteJson.JSON.createObjectNode(); }).path("state").asText());
            assertThrows(PlanRefusal.class, () -> agent.resume(signed, p -> SiteJson.JSON.createObjectNode()));
        }
        assertEquals(1, calls.get());
    }

    @Test void localPolicyAndUnqualifiedArtifactsRefuseBeforeExecution() throws Exception {
        var key = SiteTestData.key();
        var document = SiteTestData.document("wind", 1, "s1");
        try (var agent = new TsxSiteAgent("s1", "node-s1", "sha256:" + "b".repeat(64), SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("artifact"), SiteTestData.CLOCK)) {
            assertEquals("plan.catalog", assertThrows(PlanRefusal.class, () -> agent.accept(SignedJws.sign(SiteJson.bytes(document), key.getPrivate()), p -> null)).code());
        }
        var policy = (ObjectNode) SiteTestData.policy();
        ((ObjectNode) policy.path("outputs")).putArray("signal");
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), policy, temporary.resolve("policy"), SiteTestData.CLOCK)) {
            assertEquals("plan.policy", assertThrows(PlanRefusal.class, () -> agent.accept(SignedJws.sign(SiteJson.bytes(document), key.getPrivate()), p -> null)).code());
        }
        document.put("planVersion", 2147483648L);
        assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(document)));
    }

    @Test void explicitRecoveryRequiresTheSameGrantAndRetainedAtomicCheckpoint() throws Exception {
        var key = SiteTestData.key();
        String signed = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), key.getPrivate());
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            assertThrows(IllegalStateException.class, () -> agent.accept(signed, plan -> {
                assertTrue(agent.checkpoint(plan).isEmpty());
                var positions = SiteJson.JSON.createObjectNode();
                positions.putObject(SiteTestData.INPUT).put("0", "5");
                agent.checkpoint(plan, positions, SiteJson.JSON.createObjectNode().put("count", 5), SiteJson.JSON.createObjectNode().put("acknowledged", true));
                throw new IllegalStateException("synthetic interruption after checkpoint");
            }));
        }
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            var result = agent.resume(signed, plan -> {
                JsonNode saved = agent.checkpoint(plan).orElseThrow();
                assertEquals(5, saved.path("state").path("count").asInt());
                assertEquals("5", saved.path("positions").path(SiteTestData.INPUT).path("0").asText());
                return SiteJson.JSON.createObjectNode().put("resumed", true);
            });
            assertTrue(result.path("result").path("resumed").asBoolean());
            assertEquals(result, agent.resume(signed, plan -> { fail("completed recovery reactivated"); return null; }));
        }
    }

    @Test void missingChangedAndOffGridScopesRefuseBeforeActivation() {
        ObjectNode document = SiteTestData.document("wind", 1, "s1");
        ((ObjectNode) document.path("federation")).remove("scopes");
        assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(document)));
        ObjectNode invalid = SiteTestData.document("wind", 1, "s1");
        ((ObjectNode) invalid.path("federation").path("scopes").get(0)).put("startMicros", "1").put("endMicros", "600000001");
        assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(invalid)));
        ObjectNode valid = SiteTestData.document("wind", 1, "s1");
        RunnerPlan first = RunnerPlan.parse(SiteJson.bytes(valid));
        ((ObjectNode) valid.path("federation")).put("maxDelay", "PT2M");
        RunnerPlan second = RunnerPlan.parse(SiteJson.bytes(valid));
        assertNotEquals(first.checkpointIdentity(SiteTestData.ABI), second.checkpointIdentity(SiteTestData.ABI));
    }

    @Test void outputBindingsAndByteLimitsFailClosed() {
        ObjectNode identity = SiteTestData.document("wind", 1, "s1");
        ((ObjectNode) identity.path("outputs").get(0)).put("sink", "unbound");
        assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(identity)));
        ObjectNode fingerprint = SiteTestData.document("wind", 1, "s1");
        ((ObjectNode) fingerprint.path("outputs").get(0)).putArray("fingerprints").add("8985f02b48c77195");
        assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(fingerprint)));
        assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(new byte[SiteJson.MAX_BYTES + 1]));
        assertThrows(PlanRefusal.class, () -> SiteJson.parse("{\"value\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8), SiteJson.MAX_BYTES));
    }

    @Test void unadmittedExecutionCannotReadOrCreateAContinuation() throws Exception {
        var key = SiteTestData.key();
        RunnerPlan plan = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                key.getPublic(), SiteTestData.policy(), temporary.resolve("site"), SiteTestData.CLOCK)) {
            assertThrows(PlanRefusal.class, () -> agent.checkpoint(plan));
            assertThrows(PlanRefusal.class, () -> agent.checkpoint(plan, SiteJson.JSON.createObjectNode(),
                    SiteJson.JSON.createObjectNode(), SiteJson.JSON.createObjectNode()));
            assertFalse(Files.exists(agent.checkpointPath(plan)));
        }
    }
}
