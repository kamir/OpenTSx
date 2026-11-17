package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustRolloutTest {
    private static final String CLOCK = "1790812800000000";
    private static final String OUTPUT = "tsx.rust.rollout.clean.v2";
    private static final List<String> TOPICS = List.of("tsx.rust.worker-1.episodes.v2", "tsx.rust.worker-2.episodes.v2", "tsx.rust.worker-3.episodes.v2");
    private static final List<RustRollout.Worker> WORKERS = IntStream.rangeClosed(1, 3)
            .mapToObj(index -> new RustRollout.Worker("worker-" + index, "http://runner-" + index + ":8090")).toList();
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);

    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }

    private static ObjectNode dag() {
        return (ObjectNode) RustRollout.json("""
                {"schema":"opentsx.dag.v2","dag":"one-ui-dag","version":1,
                "source":{"kind":"kafscale-broker","topics":["tsx.rust.worker-1.episodes.v2","tsx.rust.worker-2.episodes.v2","tsx.rust.worker-3.episodes.v2"],"series":["1111111111111111","2222222222222222","3333333333333333"]},
                "nodes":[{"id":"decode","op":"decode.avro-soe"},
                {"id":"range","op":"dq.checks","in":["decode"],"params":{"min":0,"max":100,"mode":"gap"}},
                {"id":"convert","op":"convert.unit","in":["range"],"params":{"factor":0.001,"offset":0,"unit":"MW"}},
                {"id":"sample","op":"resample","in":["convert"],"params":{"every":"PT20S","agg":"MEAN"}}],
                "sinks":[{"from":"sample","schema":"opentsx.episode.v2","topic":"tsx.rust.rollout.clean.v2","signal":{"system":"rust-demo","id":"one-signal","grade":"clean"}}]}
                """);
    }

    private static final class Fake implements RustWorkerClient, RustRollout.Metadata {
        final Map<String, JsonNode> prepared = new ConcurrentHashMap<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        boolean collision;
        boolean failPrepare;
        boolean forgedReceipt;
        boolean unavailableActivation;
        boolean oversizedCapacity;
        boolean failPublication;
        int inputCapacity = 128;
        long prefix;

        RustRollout rollout() {
            return new RustRollout("kafscale-broker:39093", Path.of("../systems/processors"), WORKERS, this, this);
        }

        @Override
        public JsonNode get(RustRollout.Worker worker, String path) {
            if (path.equals("/v1/capabilities")) {
                ObjectNode cap = object().put("schema", "opentsx.local-worker.v1").put("node", worker.node()).put("site", "compose")
                        .put("artifactDigest", ARTIFACT).put("version", "0.1.0").put("catalog", "tsx-catalog/1")
                        .put("requestSchema", "opentsx.local-bounded-run.v1").put("stateAbi", "opentsx.rust.clean-state.v1").put("fingerprint", "8985f02b48c77195");
                RustRollout.OPERATORS.forEach(cap.putArray("operators")::add);
                cap.putObject("limits").put("records", 10_000).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                        .put("samples", 100_000).put("outputBytes", 16_777_216).put("series", 64).put("stateBytes", 1_048_576)
                        .put("partitions", inputCapacity).put("requestBytes", 65_536).put("episodeSamples", 25_000);
                if (oversizedCapacity) ((ObjectNode) cap.path("limits")).put("records", new java.math.BigInteger("18446744073709561616"));
                return cap;
            }
            JsonNode status = prepared.get(worker.node());
            if (status == null) throw new RustWorkerClient.Rejected(404, "no prepared job");
            return status;
        }

        @Override
        public JsonNode post(RustRollout.Worker worker, String path, String body) {
            if (path.equals("/v1/prepare")) {
                events.add("prepare:" + worker.node());
                if (failPrepare && worker.node().equals("worker-2")) throw new RustWorkerClient.Rejected(422, "bad fixture");
                if (prepared.containsKey(worker.node())) return prepared.get(worker.node()).deepCopy();
                JsonNode request = RustRollout.json(body);
                JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
                ObjectNode status = object().put("schema", "opentsx.local-worker-status.v1").put("requestHash", ExecutionAssignments.sha256(body))
                        .put("node", worker.node()).put("site", "compose").put("artifactDigest", ARTIFACT).put("state", "prepared").put("activationRetryable", false)
                        .put("inputTopic", assignment.path("inputs").get(0).path("topic").asText());
                String id = worker.node().substring(worker.node().length() - 1).repeat(16);
                status.putArray("inputSeries").add(id);
                status.putArray("outputSeries").add(collision ? "a".repeat(16) : id);
                prepared.put(worker.node(), status);
                return status.deepCopy();
            }
            ObjectNode status = (ObjectNode) prepared.get(worker.node());
            if (path.startsWith("/v1/abort/")) {
                events.add("abort:" + worker.node());
                status.put("state", "cancelled");
                return status.deepCopy();
            }
            assertTrue(path.startsWith("/v1/activate/"));
            assertEquals(3, prepared.size());
            assertTrue(events.stream().filter(event -> event.startsWith("prepare:")).count() >= 3);
            events.add("activate:" + worker.node());
            if (unavailableActivation) throw new IllegalStateException("activation response lost");
            if (failPublication && worker.node().equals("worker-2")) return status.put("state", "failed").put("activationRetryable", true).deepCopy();
            JsonNode request = requests.get(worker.node());
            JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
            ObjectNode receipt = object().put("schema", "opentsx.local-receipt.v1").put("requestHash", status.path("requestHash").asText())
                    .put("inputTopic", status.path("inputTopic").asText()).put("outputTopic", OUTPUT).put("inputRecords", assignment.path("inputs").get(0).path("positions").size() * 2)
                    .put("outputRecords", 2).put("stateDigest", "b".repeat(64)).put("outputDigest", "c".repeat(64));
            for (String field : List.of("artifactDigest", "deployment", "generation", "assignment", "node")) receipt.set(field, assignment.path(field));
            ArrayNode positions = receipt.putArray("positions");
            assignment.path("inputs").get(0).path("positions").forEach(cursor -> positions.addObject().put("partition", cursor.path("partition").asText()).put("next", cursor.path("to").asText()));
            if (forgedReceipt) receipt.put("requestHash", "d".repeat(64));
            status.put("state", "completed").put("activationRetryable", false).set("receipt", receipt);
            return status.deepCopy();
        }

        final Map<String, JsonNode> requests = new ConcurrentHashMap<>();

        ObjectNode plan(ObjectNode dag) {
            ObjectNode plan = rollout().plan("deployment-1", RustRollout.stringify(dag), CLOCK);
            plan.path("assignments").forEach(unit -> requests.put(unit.path("node").asText(), RustRollout.json(unit.path("requestText").asText())));
            return plan;
        }

        @Override
        public RustRollout.Inventory inspect(List<String> topics, String output) {
            Map<String, List<RustRollout.Position>> input = new java.util.LinkedHashMap<>();
            for (int index = 0; index < topics.size(); index++) {
                input.put(topics.get(index), IntStream.rangeClosed(0, index).mapToObj(partition -> new RustRollout.Position(partition, prefix, 2)).toList());
            }
            return new RustRollout.Inventory(input, 3, false);
        }

        @Override
        public void provision(String topic, int count, boolean mayCreate) {
            events.add("provision");
            assertEquals(OUTPUT, topic);
            assertEquals(3, count);
        }
    }

    @Test
    void workerTransportUsesHttp11WithoutAnUpgradeHandshake() throws ReflectiveOperationException {
        RustWorkerClient transport = RustWorkerClient.http();
        var field = transport.getClass().getDeclaredField("client");
        field.setAccessible(true);
        var client = (java.net.http.HttpClient) field.get(transport);
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, client.version());
    }

    @Test
    void oneOriginalUiDagLowersToThreeCompleteTopicsAndDistinctFragments() {
        Fake fake = new Fake();
        String text = "\n" + RustRollout.stringify(dag()) + "\n";
        JsonNode plan = fake.rollout().plan("deployment-1", text, CLOCK);
        assertEquals(3, plan.path("assignments").size());
        List<String> envelopes = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            JsonNode unit = plan.path("assignments").get(index);
            JsonNode request = RustRollout.json(unit.path("requestText").asText());
            JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
            assertEquals(text, assignment.path("sourceDagText").asText());
            assertEquals(ExecutionAssignments.sha256(text), assignment.path("specHash").asText());
            assertEquals("fragment-" + (index + 1), assignment.path("fragment").asText());
            JsonNode input = assignment.path("inputs").get(0);
            assertEquals(TOPICS.get(index), input.path("topic").asText());
            assertEquals(index + 1, input.path("positions").size());
            assertEquals(Integer.toString(index + 1), input.path("partitionCount").asText());
            envelopes.add(request.path("assignmentText").asText());
        }
        ExecutionAssignments.validateSet(envelopes);
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void realWayangStagePreparesEveryWholeTopicBeforeAnyActivation() {
        Fake fake = new Fake();
        JsonNode result = fake.rollout().execute(fake.plan(dag()));
        assertEquals("completed", result.path("state").asText());
        assertEquals(3, result.path("assignments").size());
        int first = IntStream.range(0, fake.events.size()).filter(index -> fake.events.get(index).startsWith("activate:")).min().orElseThrow();
        assertEquals(3, fake.events.subList(0, first).stream().filter(event -> event.startsWith("prepare:")).count());
    }

    @Test
    void unorderedUiNodeStorageStillUsesItsConnectedExecutionOrder() {
        Fake fake = new Fake();
        ObjectNode dag = dag();
        ArrayNode original = (ArrayNode) dag.path("nodes");
        ArrayNode reversed = JsonNodeFactory.instance.arrayNode();
        for (int index = original.size() - 1; index >= 0; index--) reversed.add(original.get(index));
        dag.set("nodes", reversed);
        assertEquals("completed", fake.rollout().execute(fake.plan(dag)).path("state").asText());
    }

    @Test
    void crossTopicDerivedIdentityCollisionAbortsEveryPrivatePreparation() {
        Fake fake = new Fake();
        fake.collision = true;
        JsonNode result = fake.rollout().execute(fake.plan(dag()));
        assertEquals("failed", result.path("state").asText());
        assertEquals(3, fake.events.stream().filter(event -> event.startsWith("abort:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("activate:")));
    }

    @Test
    void failedWorkerPreparationNeverActivatesTheRemainingPreparedWorkers() {
        Fake fake = new Fake();
        fake.failPrepare = true;
        assertEquals("failed", fake.rollout().execute(fake.plan(dag())).path("state").asText());
        assertEquals(2, fake.events.stream().filter(event -> event.startsWith("abort:")).count());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("activate:")));
    }

    @Test
    void receiptForgeryAndUnknownActivationNeverBecomeCompletedOutcomes() {
        Fake forged = new Fake();
        forged.forgedReceipt = true;
        assertThrows(RuntimeException.class, () -> forged.rollout().execute(forged.plan(dag())));
        Fake unknown = new Fake();
        unknown.unavailableActivation = true;
        assertThrows(RuntimeException.class, () -> unknown.rollout().execute(unknown.plan(dag())));
    }

    @Test
    void unqualifiedOperatorsTopicSplitsAndRetentionLossRejectBeforeDispatch() {
        Fake fake = new Fake();
        ObjectNode unsupported = dag();
        ((ObjectNode) unsupported.path("nodes").get(1)).put("op", "fft");
        assertThrows(IllegalArgumentException.class, () -> fake.plan(unsupported));
        ObjectNode duplicate = dag();
        ((ArrayNode) duplicate.path("source").path("topics")).set(1, duplicate.path("source").path("topics").get(0));
        assertThrows(IllegalArgumentException.class, () -> fake.plan(duplicate));
        fake.inputCapacity = 2;
        assertThrows(IllegalArgumentException.class, () -> fake.plan(dag()));
        fake.inputCapacity = 128;
        fake.prefix = 1;
        assertThrows(IllegalArgumentException.class, () -> fake.plan(dag()));
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void interruptedPublicationKeepsItsPlanRetryableAndReusesCompletedPeers() {
        Fake fake = new Fake();
        fake.failPublication = true;
        ObjectNode plan = fake.plan(dag());
        RustRolloutServer server = new RustRolloutServer(fake.rollout());
        assertThrows(RuntimeException.class, () -> server.request("POST", "/v1/execute", RustRollout.stringify(plan)));
        assertTrue(fake.prepared.get("worker-2").path("activationRetryable").asBoolean());
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("abort:")));
        fake.failPublication = false;
        assertEquals("completed", server.request("POST", "/v1/execute", RustRollout.stringify(plan)).path("state").asText());
        assertEquals(3, fake.prepared.size());
    }

    @Test
    void preparationFailureAfterPartialActivationCannotCancelRemainingUnits() {
        Fake fake = new Fake();
        fake.failPublication = true;
        ObjectNode plan = fake.plan(dag());
        assertThrows(RuntimeException.class, () -> fake.rollout().execute(plan));
        fake.failPrepare = true;
        assertThrows(RuntimeException.class, () -> fake.rollout().execute(plan));
        assertFalse(fake.events.stream().anyMatch(event -> event.startsWith("abort:")));
    }

    @Test
    void capabilityIntegersCannotWrapIntoAnAdmittedCapacity() {
        Fake fake = new Fake();
        fake.oversizedCapacity = true;
        assertThrows(IllegalArgumentException.class, () -> fake.plan(dag()));
        assertTrue(fake.events.isEmpty());
    }

    @Test
    void changedPlansAndWorkerEndpointsRejectBeforeWorkerOrBrokerWrites() {
        Fake fake = new Fake();
        ObjectNode plan = fake.plan(dag());
        ((ObjectNode) plan.path("assignments").get(0)).put("endpoint", "http://unconfigured:8090");
        plan.put("planHash", RustRollout.hash(plan));
        assertThrows(IllegalArgumentException.class, () -> fake.rollout().execute(plan));
        assertTrue(fake.events.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RustRollout.json("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> RustRollout.json("null"));
    }

    @Test
    void serverCachesOnlyMatchingPhysicalIdentityAndPreservesFailureOutcomes() {
        Fake fake = new Fake();
        ObjectNode plan = fake.plan(dag());
        RustRolloutServer server = new RustRolloutServer(fake.rollout());
        JsonNode first = server.request("POST", "/v1/execute", RustRollout.stringify(plan));
        int events = fake.events.size();
        assertEquals(first, server.request("POST", "/v1/execute", RustRollout.stringify(plan)));
        assertEquals(events, fake.events.size());
        plan.put("createdAtMicros", "1790812800000001").put("planHash", RustRollout.hash(plan));
        assertThrows(IllegalArgumentException.class, () -> server.request("POST", "/v1/execute", RustRollout.stringify(plan)));
    }
}
