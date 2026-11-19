package org.opentsx.wayang.site;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.wayang.federation.AggregateInventory;
import org.opentsx.wayang.federation.AggregateRecord;

import javax.net.ssl.SSLParameters;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RustSiteAdmissionTest {
    @TempDir Path directory;

    @Test void boundedRustStatisticsKeepsTheExactSignedAssignment() {
        var document = rustDocument();
        var plan = RunnerPlan.parse(SiteJson.bytes(document));
        assertEquals("rust", plan.assignment().path("runtime").asText());
        assertEquals(document.path("assignmentText").asText(), plan.exactAssignmentText());
    }

    @Test void javaExecutionCannotAcceptOrResumeABoundedRustAssignment() throws Exception {
        var driver = SiteTestData.key();
        assertThrows(IllegalArgumentException.class, () -> new TsxSiteAgent(rustPin("opentsx.rust.site-state.v1"),
                driver.getPublic(), SiteTestData.policy(), directory.resolve("wrong-runtime"), SiteTestData.CLOCK));
        String compact = SignedJws.sign(SiteJson.bytes(rustDocument()), driver.getPrivate());
        try (var agent = new TsxSiteAgent("s1", "node-s1", SiteTestData.DIGEST, SiteTestData.ABI,
                driver.getPublic(), SiteTestData.policy(), directory, SiteTestData.CLOCK)) {
            var refused = assertThrows(PlanRefusal.class, () -> agent.accept(compact, plan -> {
                fail("the Java executor must not execute Rust"); return null;
            }));
            assertEquals("plan.catalog", refused.code());
            assertEquals("plan.catalog", assertThrows(PlanRefusal.class, () -> agent.resume(compact, plan -> {
                fail("the Java executor must not resume Rust"); return null;
            })).code());
        }
    }

    @Test void qualifiedRustAdmissionBindsTrackArtifactAbiAndInventory() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var document = rustDocument();
        var inventory = inventory(document);
        try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), Map.of(), directory,
                SiteTestData.CLOCK, (plan, status) -> { }, (plan, aggregate) -> { })) {
            service.publish(SignedJws.sign(SiteJson.bytes(document), driver.getPrivate()));
            service.registerQualifiedRuntime("s1", "node-s1", "0.1.0", SiteTestData.DIGEST, "opentsx.rust.site-state.v1", "rust");
            assertEquals(409, assertThrows(TsxPlanService.HttpFailure.class,
                    () -> service.admitAggregateInventory("s1", "wind", inventory)).status());
            ObjectNode report = SiteTestData.report(service.challenge("s1", "wind"), "0123456789abcdef");
            assertEquals("runtime.identity", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("check").asText());
            report.put("track", "B");
            ((ObjectNode) report.path("artifact")).put("stateAbi", "unqualified-abi");
            assertEquals("runtime.identity", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("check").asText());
            ((ObjectNode) report.path("artifact")).put("stateAbi", "opentsx.rust.site-state.v1").put("digest", "sha256:" + "b".repeat(64));
            assertEquals("runtime.identity", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("check").asText());
            ((ObjectNode) report.path("artifact")).put("digest", SiteTestData.DIGEST);
            assertEquals("accepted", service.conformance("s1", SignedJws.sign(SiteJson.bytes(report), site.getPrivate())).path("result").asText());
            var wrongScopes = document.path("federation").path("scopes").deepCopy();
            ((ObjectNode) wrongScopes.get(0)).put("startMicros", "600000000").put("endMicros", "1200000000");
            assertThrows(IllegalArgumentException.class, () -> service.admitAggregateInventory("s1", "wind",
                    new AggregateInventory("wind", 1, document.path("specHash").asText(),
                            document.path("federation").path("expectedAssignments"), wrongScopes)));
            var wrongMembers = document.path("federation").path("expectedAssignments").deepCopy();
            var additional = wrongMembers.get(0).deepCopy();
            ((ObjectNode) additional).put("assignment", "assignment-s2").put("site", "s2").put("node", "node-s2");
            ((com.fasterxml.jackson.databind.node.ArrayNode) wrongMembers).add(additional);
            assertThrows(IllegalArgumentException.class, () -> service.admitAggregateInventory("s1", "wind",
                    new AggregateInventory("wind", 1, document.path("specHash").asText(), wrongMembers,
                            document.path("federation").path("scopes"))));
            service.admitAggregateInventory("s1", "wind", inventory);
            String first = inventory.fingerprint();
            service.admitAggregateInventory("s1", "wind", inventory);
            assertEquals(first, inventory.fingerprint());
            assertEquals(document.path("assignmentText").asText(), new String(SiteJson.bytes(inventory.assignment("assignment-s1")), StandardCharsets.UTF_8));
        }
    }

    @Test void rustInventoryNeedsAnExplicitMatchingPinAndCannotChangeItsAbi() {
        var document = rustDocument();
        var inventory = inventory(document);
        String assignment = document.path("assignmentText").asText();
        assertThrows(IllegalArgumentException.class, () -> inventory.admit(assignment));
        assertThrows(IllegalArgumentException.class, () -> inventory.admitRustBounded(assignment,
                new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "0.1.0", SiteTestData.DIGEST, "opentsx.rust.site-state.v1")));
        assertThrows(IllegalArgumentException.class, () -> inventory.admitRustBounded(assignment,
                new ConformanceHandshake.QualifiedRuntime("s1", "other-node", "0.1.0", SiteTestData.DIGEST, "opentsx.rust.site-state.v1", "rust")));
        assertThrows(IllegalArgumentException.class, () -> inventory.admitRustBounded(assignment,
                new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "0.1.0", "sha256:" + "b".repeat(64), "opentsx.rust.site-state.v1", "rust")));
        inventory.admitRustBounded(assignment, rustPin("opentsx.rust.site-state.v1"));
        assertThrows(IllegalArgumentException.class, () -> inventory.admitRustBounded(assignment, rustPin("changed-test-abi")));
        var other = inventory(document);
        other.admitRustBounded(assignment, rustPin("changed-test-abi"));
        assertNotEquals(inventory.fingerprint(), other.fingerprint());
    }

    @Test void allSevenEndpointsAndRequiredHttpErrorsUseMutualCertificateIdentity() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        var other = SiteTestData.key();
        var document = rustDocument();
        var inventory = inventory(document);
        var statuses = new AtomicInteger();
        var aggregates = new AtomicInteger();
        try (var tls = EphemeralSiteTlsFixture.create("s1", "s2", "unregistered")) {
            var certificateSites = new HashMap<>(tls.certificateSites());
            certificateSites.values().removeIf(value -> value.equals("unregistered"));
            try (var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic(), "s2", other.getPublic()),
                    certificateSites, directory, SiteTestData.CLOCK, (plan, status) -> statuses.incrementAndGet(),
                    (plan, delivery) -> { AggregateRecord.fromDelivery(delivery).forEach(inventory::validate); aggregates.incrementAndGet(); })) {
                String compact = SignedJws.sign(SiteJson.bytes(document), driver.getPrivate());
                service.publish(compact);
                service.registerQualifiedRuntime("s1", "node-s1", "0.1.0", SiteTestData.DIGEST, "opentsx.rust.site-state.v1", "rust");
                int port = service.start(new InetSocketAddress("127.0.0.1", 0), tls.serverContext()).getAddress().getPort();
                String base = "https://localhost:" + port;
                HttpClient client = client(tls, "s1");
                assertEquals(401, request(client(tls, "unregistered"), base, "GET", "/v1/sites/s1/plans?after=0", null, null).statusCode());
                assertEquals(403, request(client(tls, "s2"), base, "GET", "/v1/sites/s1/plans?after=0", null, null).statusCode());
                assertEquals(404, request(client, base, "GET", "/v1/sites/s1/conformance-challenge?job=absent", null, null).statusCode());
                var challenge = request(client, base, "GET", "/v1/sites/s1/conformance-challenge?job=wind", null, null);
                assertEquals(200, challenge.statusCode());
                var report = SiteTestData.report(SiteJson.parse(challenge.body(), 128 * 1024), "0123456789abcdef").put("track", "B");
                var reportResponse = request(client, base, "POST", "/v1/sites/s1/conformance", "application/jose",
                        SignedJws.sign(SiteJson.bytes(report), site.getPrivate()).getBytes(StandardCharsets.US_ASCII));
                assertEquals(200, reportResponse.statusCode());
                assertEquals("accepted", SiteJson.parse(reportResponse.body(), 1024).path("result").asText());
                service.admitAggregateInventory("s1", "wind", inventory);
                var page = request(client, base, "GET", "/v1/sites/s1/plans?after=0", null, null);
                assertEquals(200, page.statusCode());
                assertEquals(compact, SiteJson.parse(page.body(), 256 * 1024).path("deliveries").get(0).path("plan").asText());
                var acknowledgement = SiteJson.JSON.createObjectNode().put("job", "wind").put("planVersion", 1)
                        .put("commandHash", RunnerPlan.verify(compact, driver.getPublic()).commandHash()).put("result", "accepted");
                assertEquals(204, request(client, base, "POST", "/v1/sites/s1/deliveries/1/ack", "application/json", SiteJson.bytes(acknowledgement)).statusCode());
                ObjectNode status = status(document, 0);
                assertEquals(409, request(client, base, "POST", "/v1/sites/s1/jobs/wind/status", "application/json", SiteJson.bytes(status)).statusCode());
                status.put("planVersion", 1);
                assertEquals(204, request(client, base, "POST", "/v1/sites/s1/jobs/wind/status", "application/json", SiteJson.bytes(status)).statusCode());
                var statistics = org.opentsx.wayang.TsxStatsMerge.merge("0123456789abcdef", "PT10M", 0, 600000000, 0.01, List.of());
                var record = AggregateRecord.partial(inventory, "assignment-s1", 0, statistics);
                assertEquals(204, request(client, base, "POST", "/v1/sites/s1/jobs/wind/aggregates", "application/json",
                        SiteJson.bytes(AggregateRecord.toDelivery(List.of(record)))).statusCode());
                var refusal = SiteJson.JSON.createObjectNode().put("planVersion", 1).put("code", "plan.policy").put("message", "synthetic policy refusal");
                assertEquals(204, request(client, base, "POST", "/v1/sites/s1/jobs/wind/refusal", "application/json", SiteJson.bytes(refusal)).statusCode());
                assertEquals(1, statuses.get());
                assertEquals(1, aggregates.get());
            }
        }
    }

    private static ObjectNode rustDocument() {
        var document = SiteTestData.document("wind", 1, "s1");
        var assignment = (ObjectNode) SiteJson.parse(document.path("assignmentText").asText().getBytes(StandardCharsets.UTF_8), 65_536);
        assignment.put("runtime", "rust");
        document.put("assignmentText", new String(SiteJson.bytes(assignment), StandardCharsets.UTF_8));
        return document;
    }

    private static AggregateInventory inventory(ObjectNode document) {
        return new AggregateInventory("wind", 1, document.path("specHash").asText(), document.path("federation").path("expectedAssignments"),
                document.path("federation").path("scopes"));
    }

    private static ConformanceHandshake.QualifiedRuntime rustPin(String abi) {
        return new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "0.1.0", SiteTestData.DIGEST, abi, "rust");
    }

    private static ObjectNode status(ObjectNode document, int version) {
        ObjectNode status = SiteJson.JSON.createObjectNode().put("schema", "opentsx.dag-status.v1").put("dag", "wind").put("job", "wind")
                .put("site", "s1").put("planVersion", version).put("track", "B").put("specHash", document.path("specHash").asText())
                .put("state", "running").put("records", 0).putNull("watermark").put("skippedBlocks", 0).put("at", SiteTestData.CLOCK.instant().toString());
        status.putArray("waitingFor");
        status.putObject("artifact").put("version", "0.1.0").put("digest", SiteTestData.DIGEST);
        return status;
    }

    private static HttpClient client(EphemeralSiteTlsFixture tls, String site) {
        SSLParameters parameters = tls.clientContext(site).getDefaultSSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        return HttpClient.newBuilder().sslContext(tls.clientContext(site)).sslParameters(parameters)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static HttpResponse<byte[]> request(HttpClient client, String base, String method, String path, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (method.equals("GET")) request.GET();
        else request.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void invalidHttpRequestsUseBadRequestError() throws Exception {
        var driver = SiteTestData.key();
        var site = SiteTestData.key();
        try (var tls = EphemeralSiteTlsFixture.create("s1");
             var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()),
                     tls.certificateSites(), directory, SiteTestData.CLOCK, (plan, status) -> { }, (plan, aggregate) -> { })) {
            int port = service.start(new InetSocketAddress("127.0.0.1", 0), tls.serverContext()).getAddress().getPort();
            SSLParameters parameters = tls.clientContext("s1").getDefaultSSLParameters();
            parameters.setProtocols(new String[]{"TLSv1.3"});
            HttpClient client = HttpClient.newBuilder().sslContext(tls.clientContext("s1")).sslParameters(parameters).build();
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + port
                    + "/v1/sites/s1/conformance-challenge?job=invalid%2Fjob")).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(400, response.statusCode());
            assertEquals("bad-request", SiteJson.parse(response.body(), 1024).path("error").asText());
            assertEquals("invalid job query", SiteJson.parse(response.body(), 1024).path("message").asText());
            response = request(client, "https://localhost:" + port, "POST", "/v1/sites/s1/jobs/wind/status",
                    "application/json", "{".getBytes(StandardCharsets.UTF_8));
            assertEquals(400, response.statusCode());
            assertEquals("bad-request", SiteJson.parse(response.body(), 1024).path("error").asText());
        }
    }
}
