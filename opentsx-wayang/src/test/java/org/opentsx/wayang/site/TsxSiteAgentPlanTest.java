package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.time.Duration;
import java.time.ZoneId;
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

    @Test @EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
    void lostAckResponseReplaysTheRetainedOutcomeAfterConformanceExpires() throws Exception {
        assertPendingAckReplay(true);
    }

    @Test @EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
    void unsentAckReplaysTheRetainedOutcomeAfterConformanceExpires() throws Exception {
        assertPendingAckReplay(false);
    }

    private void assertPendingAckReplay(boolean afterResponse) throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var otherSite = SiteTestData.key();
        MutableClock clock = new MutableClock();
        var runtime = new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
        String signed = SignedJws.sign(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")), driver.getPrivate());
        AtomicInteger executions = new AtomicInteger();
        List<JsonNode> statuses = new ArrayList<>();
        Path driverDirectory = temporary.resolve("ack-driver"), siteDirectory = temporary.resolve("ack-site");
        try (var tls = EphemeralSiteTlsFixture.create("s1", "s2");
             var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", otherSite.getPublic()), tls.certificateSites(),
                     driverDirectory, clock, (plan, status) -> statuses.add(status.deepCopy()), (plan, delivery) -> fail("unexpected aggregate"))) {
            service.registerQualifiedRuntime("s1", "node-s1", runtime.version(), runtime.digest(), runtime.stateAbi());
            assertEquals("1", service.publish(signed));
            int port = service.start(new InetSocketAddress("127.0.0.1", 0), tls.serverContext()).getAddress().getPort();
            URI endpoint = URI.create("https://localhost:" + port);
            AckFaultClient transport = new AckFaultClient(tls.clientContext("s1"), afterResponse);
            TsxPlanClient client = new TsxPlanClient(endpoint, "s1", transport);
            JsonNode report = SiteTestData.report(client.challenge("wind"), "0123456789abcdef");
            assertEquals("accepted", client.conformance(SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("result").asText());
            try (var agent = new TsxSiteAgent(runtime, driver.getPublic(), SiteTestData.policy(), siteDirectory, clock)) {
                IllegalStateException failure = assertThrows(IllegalStateException.class, () -> agent.pollOnce(client, plan -> {
                    executions.incrementAndGet();
                    return SiteJson.JSON.createObjectNode().put("inputRecords", 0);
                }));
                assertInstanceOf(IOException.class, failure.getCause());
            }
            JsonNode retained = SiteJson.parse(Files.readAllBytes(siteDirectory.resolve("state.json")), 1024 * 1024)
                    .path("deliveryOutcomes").path("1");
            JsonNode acknowledgement = retained.path("acknowledgement");
            assertEquals(RunnerPlan.verify(signed, driver.getPublic()).commandHash(), acknowledgement.path("commandHash").asText());
            JsonNode serverAck = SiteJson.parse(Files.readAllBytes(driverDirectory.resolve("state.json")), 1024 * 1024)
                    .path("sites").path("s1").path("deliveries").path("1").path("ack");
            if (afterResponse) assertEquals(acknowledgement, serverAck);
            else assertTrue(serverAck.isMissingNode());
            clock.now = Instant.parse("2026-11-20T00:00:00Z");
            ObjectNode fresh = SiteTestData.document("solar", 1, "s1").put("issued", "2026-11-20T00:00:00Z").put("expires", "2026-11-21T00:00:00Z");
            assertEquals("2", service.publish(SignedJws.sign(SiteJson.bytes(fresh), driver.getPrivate())));
            try (var restored = new TsxSiteAgent(runtime, driver.getPublic(), SiteTestData.policy(), siteDirectory, clock)) {
                JsonNode result = restored.pollOnce(client, plan -> { fail("retained delivery reactivated"); return null; }).orElseThrow();
                assertEquals(retained.path("outcome"), result);
                assertEquals(1, transport.nextRequests);
                assertEquals(2, transport.ackRequests);
                assertEquals(1, executions.get());
                assertEquals(2, statuses.size());
                TsxPlanClient wrongRole = new TsxPlanClient(endpoint, "s1", tls.clientContext("s2"));
                assertEquals(403, assertThrows(TsxPlanService.HttpFailure.class, () -> wrongRole.ack("1", acknowledgement)).status());
                ObjectNode changed = acknowledgement.deepCopy();
                changed.put("commandHash", "b".repeat(64));
                assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class, () -> client.ack("1", changed)).status());
                assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class,
                        () -> restored.pollOnce(client, plan -> { fail("unqualified fresh delivery activated"); return null; })).status());
                assertEquals(1, executions.get());
                assertEquals("1", SiteJson.parse(Files.readAllBytes(siteDirectory.resolve("state.json")), 1024 * 1024).path("deliveryCursor").asText());
            }
            JsonNode recorded = SiteJson.parse(Files.readAllBytes(driverDirectory.resolve("state.json")), 1024 * 1024)
                    .path("sites").path("s1").path("deliveries").path("1").path("ack");
            assertEquals(acknowledgement, recorded);
        }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = SiteTestData.CLOCK.instant();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private static final class AckFaultClient extends HttpClient {
        private final HttpClient delegate;
        private final boolean afterResponse;
        private boolean pending = true;
        int nextRequests;
        int ackRequests;
        AckFaultClient(SSLContext tls, boolean afterResponse) {
            var parameters = tls.getDefaultSSLParameters();
            parameters.setProtocols(new String[]{"TLSv1.3"});
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            delegate = HttpClient.newBuilder().sslContext(tls).sslParameters(parameters).followRedirects(Redirect.NEVER).build();
            this.afterResponse = afterResponse;
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            boolean ack = request.uri().getPath().endsWith("/ack");
            if (request.uri().getPath().endsWith("/plans")) nextRequests++;
            if (ack) ackRequests++;
            boolean fail = ack && pending;
            if (fail) pending = false;
            if (fail && !afterResponse) throw new IOException("synthetic acknowledgement request loss before send");
            HttpResponse<T> response = delegate.send(request, handler);
            if (fail && afterResponse && response.statusCode() == 204) {
                if (response.body() instanceof InputStream body) body.close();
                throw new IOException("synthetic acknowledgement response loss after confirmed 204");
            }
            return response;
        }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { return delegate.sendAsync(request, handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) { return delegate.sendAsync(request, handler, push); }
    }

}
