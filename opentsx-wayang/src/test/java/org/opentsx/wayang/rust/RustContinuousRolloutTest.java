package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustContinuousRolloutTest {
    private static final String CLOCK = "1790812800000000";
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String OUTPUT = "tsx.continuous.clean.v2";
    private static final List<RustRollout.Worker> WORKERS = List.of(
            new RustRollout.Worker("worker-1", "http://worker-1:8090"),
            new RustRollout.Worker("worker-2", "http://worker-2:8090"),
            new RustRollout.Worker("worker-3", "http://worker-3:8090"));
    private static final String SOURCE = """
            {"schema":"opentsx.dag.v2","dag":"continuous-fixture","source":{"kind":"kafscale-broker",
            "topics":["tsx.continuous.first.v2","tsx.continuous.second.v2","tsx.continuous.third.v2"],
            "series":["1111111111111111","2222222222222222","3333333333333333"]},
            "nodes":[{"id":"decode","op":"decode.avro-soe"},{"id":"convert","op":"convert.unit","in":["decode"],
            "params":{"factor":0.001,"offset":0,"unit":"MW"}}],"sinks":[{"from":"convert",
            "schema":"opentsx.episode.v2","topic":"tsx.continuous.clean.v2","signal":{"system":"test","id":"power","grade":"clean"}}]}
            """;

    private static ObjectNode object() { return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); }
    private static JsonNode request(JsonNode plan, int index) { return RustRollout.json(plan.path("assignments").get(index).path("requestText").asText()); }
    private static JsonNode assignment(JsonNode plan, int index) { return RustRollout.json(request(plan, index).path("assignmentText").asText()); }

    private static final class Fake implements RustWorkerClient, RustRollout.Metadata {
        final Map<String, JsonNode> statuses = new ConcurrentHashMap<>();
        final Map<String, JsonNode> requests = new ConcurrentHashMap<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<String, Integer> prepareFailures = new ConcurrentHashMap<>();
        final Set<String> failAfterPreparation = ConcurrentHashMap.newKeySet();
        final long[][] ends = {{8}, {8, 8}, {8, 8, 8}};
        final long[][] starts = {{0}, {0, 0}, {0, 0, 0}};
        int records = 10_000;
        int outputPartitions = 3;
        boolean missingOutput;
        boolean incremental = true;

        RustRollout rollout() {
            return new RustRollout("kafscale-broker:39093", Path.of("../systems/processors"), WORKERS, this, this);
        }

        @Override
        public JsonNode get(RustRollout.Worker worker, String path) {
            if (path.equals("/v1/capabilities")) {
                ObjectNode value = object().put("schema", "opentsx.local-worker.v1").put("node", worker.node()).put("site", "compose")
                        .put("artifactDigest", ARTIFACT).put("version", "0.1.0").put("catalog", "tsx-catalog/1")
                        .put("requestSchema", "opentsx.local-bounded-run.v1").put("stateAbi", "opentsx.rust.clean-state.v1")
                        .put("fingerprint", "8985f02b48c77195");
                if (incremental) value.put("incrementalRequestSchema", RustRollout.INCREMENT_SCHEMA);
                RustRollout.OPERATORS.forEach(value.putArray("operators")::add);
                value.putObject("limits").put("records", records).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                        .put("samples", 100_000).put("outputBytes", 16_777_216).put("series", 64).put("stateBytes", 1_048_576)
                        .put("partitions", 128).put("episodeSamples", 25_000).put("requestBytes", 65_536).put("incrementalChains", 1);
                return value;
            }
            JsonNode status = statuses.get(path.substring(path.lastIndexOf('/') + 1));
            if (status == null) throw new Rejected(404, "request is absent");
            return status.deepCopy();
        }

        @Override
        public JsonNode post(RustRollout.Worker worker, String path, String body) {
            if (path.equals("/v1/prepare")) {
                String hash = ExecutionAssignments.sha256(body);
                events.add("prepare:" + hash);
                if (!failAfterPreparation.contains(worker.node())) failPreparation(worker);
                if (statuses.containsKey(hash)) {
                    failPreparation(worker);
                    return statuses.get(hash).deepCopy();
                }
                JsonNode request = RustRollout.json(body);
                requests.put(hash, request);
                JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
                ObjectNode status = object().put("schema", "opentsx.local-worker-status.v1").put("requestHash", hash)
                        .put("node", worker.node()).put("site", "compose").put("artifactDigest", ARTIFACT)
                        .put("state", "prepared").put("activationRetryable", false)
                        .put("inputTopic", assignment.path("inputs").get(0).path("topic").asText());
                status.set("sequence", request.path("sequence"));
                status.set("previousRequestHash", request.path("previousRequestHash"));
                String id = worker.node().substring(worker.node().length() - 1).repeat(16);
                status.putArray("inputSeries").add(id);
                status.putArray("outputSeries").add(id);
                statuses.put(hash, status);
                failPreparation(worker);
                return status.deepCopy();
            }
            String hash = path.substring(path.lastIndexOf('/') + 1);
            ObjectNode status = (ObjectNode) statuses.get(hash);
            if (path.startsWith("/v1/abort/")) {
                events.add("abort:" + hash);
                return status.put("state", "cancelled").deepCopy();
            }
            assertTrue(path.startsWith("/v1/activate/"));
            if (status.path("state").asText().equals("completed")) return status.deepCopy();
            events.add("activate:" + hash);
            JsonNode request = requests.get(hash);
            JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
            ObjectNode receipt = object().put("schema", "opentsx.local-receipt.v1").put("requestHash", hash)
                    .put("inputTopic", status.path("inputTopic").asText()).put("outputTopic", OUTPUT)
                    .put("stateDigest", "b".repeat(64)).put("outputDigest", "c".repeat(64));
            for (String field : List.of("artifactDigest", "deployment", "generation", "assignment", "node")) receipt.set(field, assignment.path(field));
            long count = 0;
            ArrayNode positions = receipt.putArray("positions");
            for (JsonNode cursor : assignment.path("inputs").get(0).path("positions")) {
                count += Long.parseLong(cursor.path("to").asText()) - Long.parseLong(cursor.path("from").asText());
                positions.addObject().put("partition", cursor.path("partition").asText()).put("next", cursor.path("to").asText());
            }
            receipt.put("inputRecords", count).put("outputRecords", count);
            status.put("state", "completed").set("receipt", receipt);
            return status.deepCopy();
        }

        private void failPreparation(RustRollout.Worker worker) {
            Integer status = prepareFailures.get(worker.node());
            if (status == null) return;
            if (status == 0) throw new IllegalStateException("preparation response lost");
            throw new Rejected(status, "worker preparation refused");
        }

        @Override
        public RustRollout.Inventory inspect(List<String> topics, String output) {
            Map<String, List<RustRollout.Position>> inputs = new LinkedHashMap<>();
            for (int index = 0; index < topics.size(); index++) {
                List<RustRollout.Position> positions = new ArrayList<>();
                for (int partition = 0; partition < ends[index].length; partition++) positions.add(new RustRollout.Position(partition, starts[index][partition], ends[index][partition]));
                inputs.put(topics.get(index), positions);
            }
            return new RustRollout.Inventory(inputs, outputPartitions, missingOutput);
        }

        @Override
        public void provision(String output, int partitions, boolean create) {
            assertEquals(OUTPUT, output);
            assertEquals(3, partitions);
        }
    }


    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "503,false", "503,true", "429,false", "500,true"})
    void uncertainPreparationRetainsPrivateCandidatesAndRetriesTheExactPlan(int status, boolean persisted) {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        fake.prepareFailures.put("worker-2", status);
        if (persisted) fake.failAfterPreparation.add("worker-2");
        RustRolloutServer server = new RustRolloutServer(rollout);
        String text = RustRollout.stringify(plan);
        assertThrows(RuntimeException.class, () -> server.request("POST", "/v1/execute", text));
        assertTrue(fake.events.stream().noneMatch(event -> event.startsWith("abort:") || event.startsWith("activate:")));
        assertTrue(fake.statuses.values().stream().allMatch(value -> value.path("state").asText().equals("prepared")));
        assertEquals(persisted ? 3 : 2, fake.statuses.size());
        Map<String, JsonNode> retained = new LinkedHashMap<>(fake.requests);
        fake.prepareFailures.clear();
        assertEquals("completed", server.request("POST", "/v1/execute", text).path("state").asText());
        retained.forEach((hash, request) -> assertEquals(request, fake.requests.get(hash)));
        assertEquals(3, fake.events.stream().filter(event -> event.startsWith("activate:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("abort:")));
        JsonNode repeated = server.request("POST", "/v1/execute", text);
        assertEquals("completed", repeated.path("state").asText());
        assertEquals(3, fake.events.stream().filter(event -> event.startsWith("activate:")).count());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 413, 422})
    void conclusivePreparationRefusalCancelsPreparedPeersWithoutActivation(int status) {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        fake.prepareFailures.put("worker-2", status);
        assertEquals("failed", rollout.execute(plan).path("state").asText());
        assertEquals(2, fake.events.stream().filter(event -> event.startsWith("abort:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("activate:")));
    }

    @Test
    void mixedUncertainAndConclusivePreparationRefusalsRetainCandidatesUntilAllOutcomesAreKnown() {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        fake.prepareFailures.put("worker-2", 422);
        fake.prepareFailures.put("worker-3", 503);
        fake.failAfterPreparation.add("worker-3");
        assertThrows(RuntimeException.class, () -> rollout.execute(plan));
        assertEquals(2, fake.statuses.size());
        assertTrue(fake.events.stream().noneMatch(event -> event.startsWith("abort:") || event.startsWith("activate:")));
        fake.prepareFailures.remove("worker-3");
        assertEquals("failed", rollout.execute(plan).path("state").asText());
        assertEquals(2, fake.events.stream().filter(event -> event.startsWith("abort:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("activate:")));
    }

    @Test
    void recoveredPreparationStillRejectsCrossTopicOwnershipCollisionsBeforeActivation() {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        fake.prepareFailures.put("worker-2", 0);
        fake.failAfterPreparation.add("worker-2");
        assertThrows(RuntimeException.class, () -> rollout.execute(plan));
        fake.prepareFailures.clear();
        ObjectNode status = (ObjectNode) fake.statuses.get(RustRollout.requestHash(plan.path("assignments").get(0)));
        status.putArray("outputSeries").add("3".repeat(16));
        assertEquals("failed", rollout.execute(plan).path("state").asText());
        assertEquals(3, fake.events.stream().filter(event -> event.startsWith("abort:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("activate:")));
    }

    @Test
    void boundedPreparationKeepsItsExistingCancellationAfterALostResponse() {
        Fake fake = new Fake();
        for (long[] ends : fake.ends) java.util.Arrays.fill(ends, 1);
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.plan("bounded-1", SOURCE, CLOCK);
        fake.prepareFailures.put("worker-2", 0);
        fake.failAfterPreparation.add("worker-2");
        assertEquals("failed", rollout.execute(plan).path("state").asText());
        assertEquals(3, fake.events.stream().filter(event -> event.startsWith("abort:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("activate:")));
    }

    @Test
    void firstCycleUsesWholeTopicsAndTheConservativePerTopicRecordBudget() {
        Fake fake = new Fake();
        JsonNode plan = fake.rollout().planContinuous("continuous-1", SOURCE, CLOCK);
        assertEquals(RustRollout.CONTINUOUS_SCHEMA, plan.path("schema").asText());
        assertEquals("1", plan.path("sequence").asText());
        assertTrue(plan.path("previousPlanHash").isNull());
        for (int index = 0; index < 3; index++) {
            JsonNode request = request(plan, index);
            assertEquals(RustRollout.INCREMENT_SCHEMA, request.path("schema").asText());
            assertEquals("1", request.path("sequence").asText());
            assertTrue(request.path("previousRequestHash").isNull());
            JsonNode positions = assignment(plan, index).path("inputs").get(0).path("positions");
            assertEquals(index + 1, positions.size());
            assertEquals(4, java.util.stream.StreamSupport.stream(positions.spliterator(), false).mapToLong(p -> Long.parseLong(p.path("to").asText())).sum());
        }
        assertEquals("2", assignment(plan, 2).path("inputs").get(0).path("positions").get(0).path("to").asText());
    }

    @Test
    void successorsPreserveContextAndAdvanceFromTheVerifiedReceiptsWithRotatingPriority() {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout();
        JsonNode first = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        JsonNode completed = rollout.execute(first);
        JsonNode second = rollout.next(first, completed).path("plan");
        assertEquals("2", second.path("sequence").asText());
        assertEquals(first.path("planHash"), second.path("previousPlanHash"));
        for (String field : List.of("deployment", "sourceDagText", "specHash", "createdAtMicros")) assertEquals(first.path(field), second.path(field));
        for (int index = 0; index < 3; index++) {
            assertEquals(RustRollout.requestHash(first.path("assignments").get(index)), request(second, index).path("previousRequestHash").asText());
            ObjectNode original = assignment(first, index).deepCopy();
            ObjectNode successor = assignment(second, index).deepCopy();
            original.remove("inputs"); successor.remove("inputs");
            assertEquals(original, successor);
            JsonNode before = assignment(first, index).path("inputs").get(0).path("positions");
            JsonNode after = assignment(second, index).path("inputs").get(0).path("positions");
            for (int partition = 0; partition < before.size(); partition++) assertEquals(before.get(partition).path("to"), after.get(partition).path("from"));
        }
        JsonNode positions = assignment(second, 2).path("inputs").get(0).path("positions");
        assertEquals(2, Long.parseLong(positions.get(1).path("to").asText()) - Long.parseLong(positions.get(1).path("from").asText()));
        assertEquals("completed", rollout.execute(second).path("state").asText());
    }

    @Test
    void globallyIdleDoesNotCreateOrExecuteAnotherCycle() {
        Fake fake = new Fake();
        for (long[] ends : fake.ends) java.util.Arrays.fill(ends, 1);
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        JsonNode completed = rollout.execute(plan);
        fake.events.clear();
        JsonNode answer = rollout.next(plan, completed);
        assertEquals(object().put("state", "idle").putNull("plan"), answer);
        assertTrue(fake.events.isEmpty());
        fake.ends[0][0] = 2;
        JsonNode ready = rollout.next(plan, completed);
        assertEquals("ready", ready.path("state").asText());
        assertEquals(3, ready.path("plan").path("assignments").size());
        JsonNode idle = assignment(ready.path("plan"), 2).path("inputs").get(0).path("positions");
        idle.forEach(cursor -> assertEquals(cursor.path("from"), cursor.path("to")));
    }

    @Test
    void successorsRejectMissingOrForgedReceiptsAndIncompleteInventory() {
        Fake fake = new Fake();
        RustRollout rollout = fake.rollout();
        JsonNode plan = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        ObjectNode result = rollout.execute(plan);
        ObjectNode forged = result.deepCopy();
        ((ObjectNode) forged.path("assignments").get(0).path("receipt")).put("requestHash", "d".repeat(64));
        assertThrows(IllegalStateException.class, () -> rollout.next(plan, forged));
        ObjectNode incomplete = result.deepCopy();
        ((ArrayNode) incomplete.path("assignments")).remove(0);
        assertThrows(IllegalArgumentException.class, () -> rollout.next(plan, incomplete));
        fake.starts[0][0] = 5;
        assertThrows(IllegalArgumentException.class, () -> rollout.next(plan, result));
        fake.starts[0][0] = 0;
        fake.outputPartitions = 2;
        assertThrows(IllegalArgumentException.class, () -> rollout.next(plan, result));
        fake.outputPartitions = 3;
        fake.missingOutput = true;
        assertThrows(IllegalArgumentException.class, () -> rollout.next(plan, result));
    }

    @Test
    void continuousAdmissionRequiresTheIncrementCapability() {
        Fake fake = new Fake(); fake.incremental = false;
        assertThrows(IllegalArgumentException.class, () -> fake.rollout().planContinuous("continuous-1", SOURCE, CLOCK));
        assertFalse(fake.rollout().capabilities().path("profiles").toString().contains("continuous"));
    }

    @Test
    void numericPredecessorHashesAreRejectedBeforeWorkerDispatch() {
        Fake fake = new Fake(); RustRollout rollout = fake.rollout();
        JsonNode first = rollout.planContinuous("continuous-1", SOURCE, CLOCK);
        ObjectNode next = rollout.next(first, rollout.execute(first)).path("plan").deepCopy();
        next.put("previousPlanHash", new java.math.BigInteger("1".repeat(64)));
        next.put("planHash", RustRollout.hash(next));
        fake.events.clear();
        assertThrows(IllegalArgumentException.class, () -> rollout.execute(next));
        assertTrue(fake.events.isEmpty());
        ObjectNode requestHash = rollout.next(first, rollout.execute(first)).path("plan").deepCopy();
        ObjectNode request = request(requestHash, 0).deepCopy();
        request.put("previousRequestHash", new java.math.BigInteger("1".repeat(64)));
        ((ObjectNode) requestHash.path("assignments").get(0)).put("requestText", RustRollout.stringify(request));
        requestHash.put("planHash", RustRollout.hash(requestHash));
        fake.events.clear();
        assertThrows(IllegalArgumentException.class, () -> rollout.execute(requestHash));
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void serverAcceptsSuccessorsWithoutAccumulatingCompletedCycleCacheEntries() throws ReflectiveOperationException {
        Fake fake = new Fake(); fake.records = 1;
        for (long[] ends : fake.ends) java.util.Arrays.fill(ends, 100);
        RustRolloutServer server = new RustRolloutServer(fake.rollout());
        ObjectNode body = object().put("deployment", "continuous-1").put("dagText", SOURCE).put("createdAtMicros", CLOCK).put("profile", "continuous");
        JsonNode plan = server.request("POST", "/v1/plan", RustRollout.stringify(body));
        for (int cycle = 1; cycle <= 65; cycle++) {
            JsonNode result = server.request("POST", "/v1/execute", RustRollout.stringify(plan));
            assertEquals("completed", result.path("state").asText());
            ObjectNode next = object(); next.set("plan", plan); next.set("result", result);
            JsonNode response = server.request("POST", "/v1/next", RustRollout.stringify(next));
            assertEquals("ready", response.path("state").asText());
            plan = response.path("plan");
        }
        var field = RustRolloutServer.class.getDeclaredField("jobs"); field.setAccessible(true);
        assertEquals(0, ((Map<?, ?>) field.get(server)).size());
        assertEquals("66", plan.path("sequence").asText());
        assertNotEquals(request(plan, 0).path("previousRequestHash").asText(), RustRollout.requestHash(plan.path("assignments").get(0)));
    }
}
