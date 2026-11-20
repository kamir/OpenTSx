package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.contracts.PhysicalDeployments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustPhysicalDeploymentTest {
    private static final RustRollout.Worker WORKER = new RustRollout.Worker("worker-1", "http://runner-1:8090");
    private static final Path CATALOG = Path.of("..", "systems", "processors");

    private static JsonNode vector() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Path.of("..", "docs", "datamodel-2026", "testvectors", "physical-deployment.v1.json").toFile());
        for (JsonNode vector : vectors.path("cases")) if (vector.path("name").asText().equals("complete-bounded-deployment")) return vector;
        throw new IllegalStateException("shared physical deployment case is missing");
    }

    private static final class Fake implements RustWorkerClient, RustRollout.Metadata, RustRollout.AdmissionFacts {
        final JsonNode fixture;
        final ObjectNode report;
        final List<String> events = new ArrayList<>();
        final List<String> preparedTexts = new ArrayList<>();
        ObjectNode status;
        JsonNode request;
        String requestHash;
        String now = "120";
        boolean changedIncarnation;
        boolean changeAfterPrepare;

        Fake() throws Exception {
            fixture = vector();
            JsonNode physical = RustRollout.json(fixture.path("input").asText());
            report = (ObjectNode) RustRollout.json(physical.path("capabilities").get(0).path("reportText").asText());
        }

        RustRollout rollout(boolean facts) {
            return new RustRollout("kafscale-broker:39093", CATALOG, List.of(WORKER), this, this, null, facts ? this : null);
        }

        String source() { return RustRollout.json(fixture.path("input").asText()).path("sourceDagText").asText(); }

        @Override
        public RustRollout.Observation observe(JsonNode physical) {
            ObjectNode facts = fixture.path("facts").deepCopy();
            ObjectNode capability = (ObjectNode) facts.path("capabilities").get(0);
            String text = RustRollout.stringify(report);
            capability.put("reportText", text).put("digest", ExecutionAssignments.sha256(text));
            if (changedIncarnation) ((ObjectNode) facts.path("topics").get(0)).put("incarnation", "recreated-topic");
            return new RustRollout.Observation(facts, now);
        }

        @Override
        public JsonNode get(RustRollout.Worker worker, String path) {
            if (path.equals("/v1/capabilities")) return report.deepCopy();
            if (status == null) throw new RustWorkerClient.Rejected(404, "no prepared job");
            return status.deepCopy();
        }

        @Override
        public JsonNode post(RustRollout.Worker worker, String path, String body) {
            if (path.equals("/v1/prepare-physical")) {
                events.add("prepare"); preparedTexts.add(body);
                JsonNode wrapper = RustRollout.json(body);
                assertEquals(RustPhysicalDeployment.REQUEST_SCHEMA, wrapper.path("schema").asText());
                PhysicalDeployments.admit(wrapper.path("physicalText").asText(), observe(RustRollout.json(wrapper.path("physicalText").asText())).facts(), now);
                request = RustRollout.json(wrapper.path("requestText").asText());
                assertEquals(10000, request.path("limits").path("samples").asInt());
                requestHash = ExecutionAssignments.sha256(body);
                JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
                if (status == null) {
                    status = (ObjectNode) RustRollout.json("{}");
                    status.put("schema", "opentsx.local-worker-status.v1").put("requestHash", requestHash).put("node", "worker-1")
                            .put("site", "compose").put("artifactDigest", report.path("artifactDigest").asText()).put("state", "prepared")
                            .put("activationRetryable", false).put("inputTopic", assignment.path("inputs").get(0).path("topic").asText());
                    status.putArray("inputSeries").add("0000000000000001");
                    status.putArray("outputSeries").add("0000000000000001");
                }
                if (changeAfterPrepare) changedIncarnation = true;
                return status.deepCopy();
            }
            if (path.startsWith("/v1/abort/")) return status.put("state", "cancelled").deepCopy();
            assertEquals("/v1/activate/" + requestHash, path);
            events.add("activate");
            JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
            ObjectNode receipt = (ObjectNode) RustRollout.json("{}");
            receipt.put("schema", "opentsx.local-receipt.v1").put("requestHash", requestHash)
                    .put("inputTopic", assignment.path("inputs").get(0).path("topic").asText())
                    .put("outputTopic", request.path("sink").path("topic").asText()).put("inputRecords", 4).put("outputRecords", 2)
                    .put("stateDigest", "b".repeat(64)).put("outputDigest", "c".repeat(64));
            for (String field : List.of("artifactDigest", "deployment", "generation", "assignment", "node")) receipt.set(field, assignment.path(field));
            ArrayNode positions = receipt.putArray("positions");
            assignment.path("inputs").get(0).path("positions").forEach(cursor -> positions.addObject().put("partition", cursor.path("partition").asText()).put("next", cursor.path("to").asText()));
            status.put("state", "completed").set("receipt", receipt);
            return status.deepCopy();
        }

        @Override
        public RustRollout.Inventory inspect(List<String> topics, String output) {
            events.add("inspect");
            return new RustRollout.Inventory(Map.of(topics.get(0), List.of(new RustRollout.Position(0, 0, 2), new RustRollout.Position(1, 0, 2))), 2, false);
        }

        @Override
        public void provision(String output, int partitions, boolean mayCreate) { events.add("provision"); assertFalse(mayCreate); }
    }

    @Test
    void providesOptInPhysicalDeployment() throws Exception {
        assertNotNull(RustRollout.class.getMethod("planPhysical", String.class, String.class, String.class));
        assertNotNull(RustRollout.class.getMethod("validatePhysical", JsonNode.class));
    }

    @Test
    void defaultProviderRefusesBeforeExternalWork() throws Exception {
        Fake fake = new Fake();
        PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class,
                () -> fake.rollout(false).planPhysical("wind-physical", fake.source(), "110"));
        assertEquals("deployment.incarnation", error.code());
        assertTrue(fake.events.isEmpty());
        assertFalse(fake.rollout(false).capabilities().path("profiles").toString().contains("physical"));
    }

    @Test
    void runsTheActualWayangStageAndRetriesTheIdenticalWorkerRequest() throws Exception {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout(true);
        ObjectNode plan = rollout.planPhysical("wind-physical", fake.source(), "110");
        assertEquals(PhysicalDeployments.SCHEMA, plan.path("schema").asText());
        assertTrue(rollout.capabilities().path("profiles").toString().contains("physical"));
        JsonNode first = rollout.execute(plan);
        ObjectNode reordered = (ObjectNode) RustRollout.json("{}");
        List<String> fields = new ArrayList<>(); plan.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.reverse(fields); fields.forEach(field -> reordered.set(field, plan.get(field)));
        JsonNode second = rollout.execute(reordered);
        assertEquals("completed", first.path("state").asText());
        assertEquals(first, second);
        assertEquals(plan.path("planHash"), first.path("planHash"));
        assertEquals(2, fake.preparedTexts.size());
        assertEquals(fake.preparedTexts.get(0), fake.preparedTexts.get(1));
        JsonNode wrapper = RustRollout.json(fake.preparedTexts.get(0));
        assertEquals(plan.path("assignmentTexts").get(0), RustRollout.json(wrapper.path("requestText").asText()).path("assignmentText"));
    }

    @Test
    void revalidatesTrustedFactsBeforeCachedServerRetry() throws Exception {
        Fake fake = new Fake();
        RustRolloutServer server = new RustRolloutServer(fake.rollout(true));
        String input = RustRollout.stringify(((ObjectNode) RustRollout.json("{}")).put("deployment", "wind-physical")
                .put("dagText", fake.source()).put("createdAtMicros", "110").put("profile", "physical"));
        JsonNode plan = server.request("POST", "/v1/plan", input);
        JsonNode result = server.request("POST", "/v1/execute", RustRollout.stringify(plan));
        assertEquals(result, server.request("POST", "/v1/execute", RustRollout.stringify(plan)));
        assertEquals(1, fake.preparedTexts.size());
        fake.now = "201";
        PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class,
                () -> server.request("POST", "/v1/execute", RustRollout.stringify(plan)));
        assertEquals("deployment.freshness", error.code());
        assertEquals(1, fake.preparedTexts.size());
    }

    @Test
    void changedIncarnationRefusesBeforeProvisionOrPrepare() throws Exception {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout(true);
        JsonNode plan = rollout.planPhysical("wind-physical", fake.source(), "110");
        fake.events.clear(); fake.changedIncarnation = true;
        PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class, () -> rollout.execute(plan));
        assertEquals("deployment.incarnation", error.code());
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void rechecksIncarnationAfterPrepareBeforeActivation() throws Exception {
        Fake fake = new Fake(); RustRollout rollout = fake.rollout(true);
        JsonNode plan = rollout.planPhysical("wind-physical", fake.source(), "110");
        fake.changeAfterPrepare = true;
        PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class, () -> rollout.execute(plan));
        assertEquals("deployment.incarnation", error.code());
        assertTrue(fake.events.contains("prepare"));
        assertFalse(fake.events.contains("activate"));
    }

    @Test
    void refusesPhysicalRequestsThroughTheLegacyEntryPoint() throws Exception {
        Fake fake = new Fake(); RustRollout rollout = fake.rollout(true);
        JsonNode plan = rollout.planPhysical("wind-physical", fake.source(), "110");
        JsonNode disguised = RustPhysicalDeployment.local(plan, "kafscale-broker:39093", List.of(WORKER));
        fake.events.clear();
        assertThrows(IllegalArgumentException.class, () -> rollout.execute(disguised));
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void reportsTheOriginalInvalidParameterLocation() throws Exception {
        Fake fake = new Fake(); RustRollout rollout = fake.rollout(true);
        ObjectNode raw = (ObjectNode) RustRollout.json(fake.source());
        ((ObjectNode) raw.path("nodes").get(1).path("params")).put("factor", "invalid");
        PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class,
                () -> rollout.planPhysical("wind-physical", RustRollout.stringify(raw), "110"));
        assertEquals("deployment.computation", error.code());
        assertEquals("convert", error.node());
        assertEquals("/nodes/1/params/factor", error.field());
    }

    @Test
    void effectiveDefaultsShareIdentityAndSignedZeroRemainsDistinct() throws Exception {
        Fake fake = new Fake(); RustRollout rollout = fake.rollout(true);
        ObjectNode raw = (ObjectNode) RustRollout.json(fake.source());
        ((ObjectNode) raw.path("nodes").get(1).path("params")).remove("offset");
        JsonNode omitted = rollout.planPhysical("wind-physical", RustRollout.stringify(raw), "110");
        ((ObjectNode) raw.path("nodes").get(1).path("params")).put("offset", 0.0);
        JsonNode positive = rollout.planPhysical("wind-physical", RustRollout.stringify(raw), "110");
        JsonNode negative = rollout.planPhysical("wind-physical", fake.source(), "110");
        assertEquals(omitted.path("computationHash"), positive.path("computationHash"));
        assertNotEquals(negative.path("computationHash"), positive.path("computationHash"));
        assertNotEquals(omitted.path("specHash"), positive.path("specHash"));
    }

    @Test
    void rejectsARehashedComputationThatDoesNotDescribeOriginalText() throws Exception {
        Fake fake = new Fake(); RustRollout rollout = fake.rollout(true);
        ObjectNode plan = rollout.planPhysical("wind-physical", fake.source(), "110");
        ObjectNode logical = (ObjectNode) PhysicalDeployments.decodeComputation(plan.path("computationText").asText());
        ((ObjectNode) logical.path("nodes").get(1).path("params")).put("factor", 2.0);
        String text = PhysicalDeployments.encodeComputation(logical);
        plan.put("computationText", text).put("computationHash", ExecutionAssignments.sha256(text));
        ObjectNode assignment = (ObjectNode) RustRollout.json(plan.path("assignmentTexts").get(0).asText());
        ObjectNode fragment = (ObjectNode) RustRollout.json(assignment.path("fragmentText").asText());
        ((ObjectNode) fragment.path("nodes").get(1).path("params")).put("factor", 2.0);
        String fragmentText = RustRollout.stringify(fragment);
        assignment.put("fragmentText", fragmentText).put("fragmentHash", ExecutionAssignments.sha256(fragmentText));
        ((ArrayNode) plan.path("assignmentTexts")).set(0, assignment.textNode(RustRollout.stringify(assignment)));
        plan.put("planHash", PhysicalDeployments.planHash(plan));
        PhysicalDeployments.validate(RustRollout.stringify(plan));
        PhysicalDeployments.Refused error = assertThrows(PhysicalDeployments.Refused.class, () -> rollout.validatePhysical(plan));
        assertEquals("deployment.computation", error.code());
    }
}
