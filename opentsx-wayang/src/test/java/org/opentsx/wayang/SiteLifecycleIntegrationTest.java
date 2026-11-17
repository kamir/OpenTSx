package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class SiteLifecycleIntegrationTest {
    @TempDir Path temporary;

    @Test void aBoundedSitePublishesItsActualLifecycleThroughHttpAndAcknowledgedKafka() throws Exception {
        try (var fixture = new FederationFixture(temporary.resolve("lifecycle")); var session = fixture.conformanceSession()) {
            assertEquals("accepted", session.conform("s1").path("result").asText());
            session.execute(List.of("s1"));
            List<JsonNode> statuses = session.statuses("s1");
            assertTrue(statuses.size() >= 2);
            assertEquals("running", statuses.get(0).path("state").asText());
            assertEquals(0, statuses.get(0).path("records").longValue());
            JsonNode stopped = statuses.get(statuses.size() - 1);
            assertEquals("stopped", stopped.path("state").asText());
            assertEquals(fixture.data().sites().get("s1").size(), stopped.path("records").longValue());
            String digest = SiteConformanceChecks.artifactDigest();
            String version = SiteConformanceChecks.version(Path.of("..").toAbsolutePath().normalize());
            for (JsonNode status : statuses) {
                assertEquals("opentsx.dag-status.v1", status.path("schema").asText());
                assertEquals("s1", status.path("site").asText());
                assertEquals("A", status.path("track").asText());
                assertEquals(1, status.path("planVersion").asInt());
                assertEquals(fixture.specHash(), status.path("specHash").asText());
                assertEquals(status.path("job"), status.path("dag"));
                assertEquals(version, status.path("artifact").path("version").asText());
                assertEquals(digest, status.path("artifact").path("digest").asText());
                assertTrue(status.path("watermark").isNull());
                assertTrue(status.path("waitingFor").isArray());
                assertTrue(status.path("waitingFor").isEmpty());
                assertEquals(0, status.path("skippedBlocks").longValue());
                assertFalse(status.has("error"));
            }
            ObjectMapper json = new ObjectMapper();
            List<JsonNode> stored = new ArrayList<>();
            for (var record : session.storedStatuses()) {
                JsonNode status = json.readTree(record.value());
                assertEquals(status.path("job").asText() + "|s1", new String(record.key(), StandardCharsets.UTF_8));
                assertEquals("opentsx.dag-status.v1", new String(record.headers().lastHeader("tsx-schema").value(), StandardCharsets.UTF_8));
                stored.add(status);
            }
            assertEquals(statuses, stored);
        }
    }

    @Test void aCallbackRefusalRemainsThePrimaryFailureAndPublishesFailedStatus() throws Exception {
        try (var fixture = new FederationFixture(temporary.resolve("failure")); var session = fixture.conformanceSession()) {
            assertEquals("accepted", session.conform("s1").path("result").asText());
            var failure = new org.opentsx.wayang.site.PlanRefusal("plan.invalid", "synthetic execution refusal");
            assertSame(failure, assertThrows(org.opentsx.wayang.site.PlanRefusal.class,
                    () -> session.poll("s1", plan -> { throw failure; })));
            List<JsonNode> statuses = session.statuses("s1");
            assertEquals(List.of("running", "failed"), statuses.stream().map(s -> s.path("state").asText()).toList());
            assertEquals(failure.getMessage(), statuses.get(1).path("error").asText());
            assertEquals(0, statuses.get(1).path("records").longValue());
            ObjectMapper json = new ObjectMapper();
            List<JsonNode> stored = new ArrayList<>();
            for (var record : session.storedStatuses()) stored.add(json.readTree(record.value()));
            assertEquals(statuses, stored);
        }
    }

}
