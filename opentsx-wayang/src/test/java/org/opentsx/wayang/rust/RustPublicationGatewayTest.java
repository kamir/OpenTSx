package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustPublicationGatewayTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String OUTPUT = "tsx.publication.clean.v2";
    private static final List<String> TOPICS = List.of("tsx.publication.first.v2", "tsx.publication.second.v2", "tsx.publication.third.v2");
    private static final List<RustPublicationGateway.Record> RECORDS = List.of(
            new RustPublicationGateway.Record("0123456789abcdef", 1_790_812_800_000L, new byte[]{1, 2, (byte) 255}));

    @TempDir Path temporary;

    private static ObjectNode object() { return JSON.objectNode(); }

    private static ObjectNode plan(String deployment, String output, int factor) {
        ObjectNode dag = object().put("schema", "opentsx.dag.v2").put("dag", "publication-fixture");
        ObjectNode source = dag.putObject("source").put("kind", "kafscale-broker");
        TOPICS.forEach(source.putArray("topics")::add);
        source.putArray("series").add("1111111111111111").add("2222222222222222").add("3333333333333333");
        ArrayNode nodes = dag.putArray("nodes");
        nodes.addObject().put("id", "decode").put("op", "decode.avro-soe");
        ObjectNode convert = nodes.addObject().put("id", "convert").put("op", "convert.unit");
        convert.putArray("in").add("decode");
        convert.putObject("params").put("factor", factor).put("offset", 0).put("unit", "MW");
        dag.putArray("sinks").addObject().put("from", "convert").put("schema", "opentsx.episode.v2").put("topic", output)
                .putObject("signal").put("system", "test").put("id", "publication").put("grade", "clean");
        List<RustRollout.Worker> workers = new ArrayList<>();
        for (int index = 1; index <= 3; index++) workers.add(new RustRollout.Worker("worker-" + index, "http://worker-" + index + ":8090"));
        RustWorkerClient client = new RustWorkerClient() {
            @Override public JsonNode get(RustRollout.Worker worker, String path) {
                assertEquals("/v1/capabilities", path);
                ObjectNode cap = object().put("schema", "opentsx.local-worker.v1").put("node", worker.node()).put("site", "compose")
                        .put("artifactDigest", "sha256:" + "a".repeat(64)).put("version", "0.1.0").put("catalog", "tsx-catalog/1")
                        .put("requestSchema", "opentsx.local-bounded-run.v1").put("incrementalRequestSchema", RustRollout.INCREMENT_SCHEMA)
                        .put("stateAbi", "opentsx.rust.clean-state.v1").put("fingerprint", "8985f02b48c77195");
                RustRollout.OPERATORS.forEach(cap.putArray("operators")::add);
                cap.putObject("limits").put("records", 10_000).put("payloadBytes", 1_048_576).put("inputBytes", 16_777_216)
                        .put("samples", 100_000).put("outputBytes", 16_777_216).put("series", 64).put("stateBytes", 1_048_576)
                        .put("partitions", 128).put("requestBytes", 65_536).put("episodeSamples", 25_000).put("incrementalChains", 1);
                return cap;
            }
            @Override public JsonNode post(RustRollout.Worker worker, String path, String body) {
                throw new IllegalStateException("planning does not mutate workers");
            }
        };
        RustRollout.Metadata metadata = new RustRollout.Metadata() {
            @Override public RustRollout.Inventory inspect(List<String> topics, String outputTopic) {
                LinkedHashMap<String, List<RustRollout.Position>> inputs = new LinkedHashMap<>();
                for (int index = 0; index < topics.size(); index++) {
                    List<RustRollout.Position> positions = new ArrayList<>();
                    for (int partition = 0; partition <= index; partition++) positions.add(new RustRollout.Position(partition, 0, 1));
                    inputs.put(topics.get(index), positions);
                }
                return new RustRollout.Inventory(inputs, 3, false);
            }
            @Override public void provision(String topic, int count, boolean mayCreate) {
                throw new IllegalStateException("planning does not publish data");
            }
        };
        return new RustRollout("read-proxy:9092", Path.of("../systems/processors"), workers, client, metadata)
                .planContinuous(deployment, RustRollout.stringify(dag), "1790812800000000");
    }

    private static ObjectNode status(String epoch) {
        return object().put("state", "prepared").put("publicationEpoch", epoch)
                .put("preparedOutputDigest", RustPublicationGateway.digest(RECORDS)).put("preparedOutputRecords", "1");
    }

    private static ObjectNode successor(JsonNode previous) {
        ObjectNode next = previous.deepCopy();
        long sequence = Long.parseLong(previous.path("sequence").asText()) + 1;
        next.put("sequence", Long.toString(sequence)).put("previousPlanHash", previous.path("planHash").asText());
        for (int index = 0; index < next.path("assignments").size(); index++) {
            ObjectNode unit = (ObjectNode) next.path("assignments").get(index);
            JsonNode prior = previous.path("assignments").get(index);
            ObjectNode request = (ObjectNode) RustRollout.json(unit.path("requestText").asText());
            request.put("sequence", Long.toString(sequence)).put("previousRequestHash", RustRollout.requestHash(prior));
            ObjectNode assignment = (ObjectNode) RustRollout.json(request.path("assignmentText").asText());
            for (JsonNode value : assignment.path("inputs").get(0).path("positions")) {
                ObjectNode position = (ObjectNode) value;
                long from = Long.parseLong(position.path("to").asText());
                position.put("from", Long.toString(from)).put("to", Long.toString(from + 1));
            }
            request.put("assignmentText", RustRollout.stringify(assignment));
            unit.put("requestText", RustRollout.stringify(request));
        }
        next.put("planHash", RustRollout.hash(next));
        return next;
    }

    private static ObjectNode body(JsonNode unit, String epoch) {
        ObjectNode body = object().put("epoch", epoch).put("requestText", unit.path("requestText").asText());
        ArrayNode records = body.putArray("records");
        for (RustPublicationGateway.Record record : RECORDS) {
            ObjectNode value = records.addObject().put("key", record.key()).put("timestamp", record.timestamp());
            ArrayNode payload = value.putArray("payload");
            for (byte octet : record.payload()) payload.add(Byte.toUnsignedInt(octet));
        }
        return body;
    }

    private static void complete(RustPublicationGateway gateway, JsonNode plan, String epoch) {
        for (JsonNode unit : plan.path("assignments")) {
            gateway.register(plan, unit, status(epoch));
            String credential = gateway.grant(plan.path("deployment").asText(), unit.path("node").asText()).path("token").asText();
            gateway.publish(credential, body(unit, epoch));
        }
    }

    @Test
    void registeredPreparedBytesPublishOnceAndReturnTheSameReceiptAfterGatewayRestart() throws Exception {
        Path state = temporary.resolve("gateway");
        AtomicInteger writes = new AtomicInteger();
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        JsonNode unit = plan.path("assignments").get(0);
        String credential;
        JsonNode receipt;
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> {
            JsonNode durable = RustRollout.json(Files.readString(state.resolve("ledger.json")));
            assertEquals("publishing", durable.path("payload").path("requests").path(RustRollout.requestHash(unit)).path("state").asText());
            assertEquals(OUTPUT, topic);
            assertEquals(3, partitions);
            assertEquals(RustPublicationGateway.digest(RECORDS), RustPublicationGateway.digest(records));
            writes.incrementAndGet();
        })) {
            assertEquals("1", gateway.admit(plan));
            credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, body(unit, "1")));
            gateway.register(plan, unit, status("1"));
            receipt = gateway.publish(credential, body(unit, "1"));
            assertEquals("1", receipt.path("outputRecords").asText());
            assertEquals(RustRollout.requestHash(unit), receipt.path("requestHash").asText());
            assertEquals(receipt, gateway.publish(credential, body(unit, "1")));
            assertEquals(1, writes.get());
            assertFalse(receipt.toString().contains(credential));
        }
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> writes.incrementAndGet())) {
            assertEquals(receipt, gateway.publish(credential, body(unit, "1")));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void resumeRevokesSavedPreparedAuthorityAndRejectsForgedCurrentEpochBeforeWriterIo() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        JsonNode unit = plan.path("assignments").get(0);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> writes.incrementAndGet())) {
            gateway.admit(plan);
            String oldCredential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            complete(gateway, plan, "1");
            JsonNode changed = gateway.rotate("resume-first", "resume", "1", plan, null);
            assertEquals("2", changed.path("publicationEpoch").asText());
            assertFalse(gateway.accepts(oldCredential));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(oldCredential, body(unit, "1")));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(oldCredential, body(unit, "2")));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.register(plan, unit, status("1")));
            assertEquals(3, writes.get());
            String current = gateway.grant("deployment-first", "worker-1").path("token").asText();
            ObjectNode next = successor(plan);
            JsonNode nextUnit = next.path("assignments").get(0);
            gateway.register(next, nextUnit, status("2"));
            gateway.publish(current, body(nextUnit, "2"));
            assertEquals(4, writes.get());
            assertEquals(changed, gateway.rotate("resume-first", "resume", "1", plan, null));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.rotate("resume-first", "resume", "2", plan, null));
            assertEquals("2", gateway.epoch("deployment-first"));
        }
    }

    @Test
    void uncertainBrokerOutcomeSurvivesRestartAndBlocksReplayRotationAndRetirement() throws Exception {
        Path state = temporary.resolve("gateway");
        AtomicInteger writes = new AtomicInteger();
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        JsonNode unit = plan.path("assignments").get(0);
        String credential;
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> {
            writes.incrementAndGet();
            throw new IOException("delivery acknowledgement was lost");
        })) {
            gateway.admit(plan);
            credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            gateway.register(plan, unit, status("1"));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, body(unit, "1")));
            assertEquals(1, writes.get());
        }
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> writes.incrementAndGet())) {
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, body(unit, "1")));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.register(plan, unit, status("1")));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.rotate("resume-first", "resume", "1", plan, null));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.retire("retire-first", "deployment-first", "1"));
            assertEquals("1", gateway.epoch("deployment-first"));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void restartRequiresChangedDagSameWholeTopicsNewDeploymentAndNewOutput() throws Exception {
        ObjectNode first = plan("deployment-first", OUTPUT, 2);
        ObjectNode replacement = plan("deployment-second", "tsx.publication.replacement.v2", 3);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> { })) {
            gateway.admit(first);
            complete(gateway, first, "1");
            String oldCredential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            ObjectNode sameDag = replacement.deepCopy();
            sameDag.set("specHash", first.path("specHash"));
            ObjectNode otherInput = replacement.deepCopy();
            ((ObjectNode) otherInput.path("assignments").get(0)).put("inputTopic", "tsx.other.input.v2");
            for (ObjectNode invalid : List.of(first, plan("deployment-second", OUTPUT, 3), sameDag, otherInput)) {
                assertThrows(RustPublicationGateway.Refused.class, () -> gateway.rotate("restart-invalid", "restart", "1", first, invalid));
                assertTrue(gateway.accepts(oldCredential));
                assertEquals("1", gateway.epoch("deployment-first"));
            }
            JsonNode result = gateway.rotate("restart-valid", "restart", "1", first, replacement);
            assertEquals("deployment-second", result.path("deployment").asText());
            assertEquals("2", result.path("publicationEpoch").asText());
            assertFalse(gateway.accepts(oldCredential));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.admit(first));
            for (int index = 0; index < 3; index++) {
                JsonNode grant = gateway.grant("deployment-second", "worker-" + (index + 1));
                assertEquals(TOPICS.get(index), grant.path("inputTopic").asText());
                assertEquals("tsx.publication.replacement.v2", grant.path("outputTopic").asText());
            }
            assertEquals(result, gateway.rotate("restart-valid", "restart", "1", first, replacement));
        }
    }

    @Test
    void immutableBindingChangesCannotRegisterPublishOrRotateTheActiveAuthority() throws Exception {
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> { })) {
            gateway.admit(plan);
            JsonNode unit = plan.path("assignments").get(0);
            for (String change : List.of("clock", "endpoint", "bootstrap", "partitionCount", "limit", "spec")) {
                ObjectNode altered = plan.deepCopy();
                ObjectNode alteredUnit = (ObjectNode) altered.path("assignments").get(0);
                ObjectNode request = (ObjectNode) RustRollout.json(alteredUnit.path("requestText").asText());
                switch (change) {
                    case "clock" -> altered.put("createdAtMicros", "1790812800000001");
                    case "endpoint" -> alteredUnit.put("endpoint", "http://other-worker:8090");
                    case "bootstrap" -> ((ObjectNode) request.path("bindings").path("local")).put("bootstrap", "other-read-proxy:9092");
                    case "partitionCount" -> ((ObjectNode) request.path("sink")).put("partitionCount", "2");
                    case "limit" -> ((ObjectNode) request.path("limits")).put("samples", 9);
                    case "spec" -> altered.put("specHash", org.opentsx.model.contracts.ExecutionAssignments.sha256("other-spec"));
                    default -> throw new IllegalStateException("unknown fixture mutation");
                }
                alteredUnit.put("requestText", RustRollout.stringify(request));
                assertThrows(RustPublicationGateway.Refused.class, () -> gateway.admit(altered));
                assertThrows(RustPublicationGateway.Refused.class, () -> gateway.register(altered, alteredUnit, status("1")));
                assertThrows(RustPublicationGateway.Refused.class, () -> gateway.rotate("resume-altered", "resume", "1", altered, null));
            }
            gateway.register(plan, unit, status("1"));
            assertEquals("1", gateway.epoch("deployment-first"));
        }
    }

    @Test
    void registeredDigestCountAndRecordBoundaryRejectTamperingBeforeWriterIo() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        JsonNode unit = plan.path("assignments").get(0);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> writes.incrementAndGet())) {
            gateway.admit(plan);
            String credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            gateway.register(plan, unit, status("1"));
            List<ObjectNode> altered = new ArrayList<>();
            ObjectNode payload = body(unit, "1");
            ((ArrayNode) payload.path("records").get(0).path("payload")).set(0, JSON.numberNode(3));
            altered.add(payload);
            ObjectNode timestamp = body(unit, "1");
            ((ObjectNode) timestamp.path("records").get(0)).put("timestamp", 0);
            altered.add(timestamp);
            ObjectNode octet = body(unit, "1");
            ((ArrayNode) octet.path("records").get(0).path("payload")).set(0, JSON.numberNode(256));
            altered.add(octet);
            ObjectNode count = body(unit, "1");
            ((ArrayNode) count.path("records")).removeAll();
            altered.add(count);
            ObjectNode extra = body(unit, "1");
            extra.put("extra", true);
            altered.add(extra);
            for (ObjectNode invalid : altered) assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, invalid));
            assertEquals(0, writes.get());
            gateway.publish(credential, body(unit, "1"));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void retirementRevokesAuthorityPersistsEpochAndNeverReopensTheDeploymentIdentity() throws Exception {
        Path state = temporary.resolve("gateway");
        ObjectNode first = plan("deployment-first", OUTPUT, 2);
        String credential;
        JsonNode retired;
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            gateway.admit(first);
            credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            retired = gateway.retire("stop-first", "deployment-first", "1");
            assertEquals("2", retired.path("publicationEpoch").asText());
            assertFalse(gateway.accepts(credential));
            assertEquals(retired, gateway.retire("stop-first", "deployment-first", "1"));
        }
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            assertEquals("2", gateway.epoch("deployment-first"));
            assertFalse(gateway.accepts(credential));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.admit(first));
            assertEquals("3", gateway.admit(plan("deployment-second", "tsx.publication.replacement.v2", 3)));
            assertEquals(retired, gateway.retire("stop-first", "deployment-first", "1"));
        }
    }

    @Test
    void storageIsPrivateAndExclusivelyLockedAndMissingOrCorruptLedgersFailClosed() throws Exception {
        Path state = temporary.resolve("gateway");
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            gateway.admit(plan("deployment-first", OUTPUT, 2));
            assertThrows(Exception.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state));
            for (String file : List.of("ledger.json", "lock", "initialized")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(state.resolve(file)));
            }
        }
        byte[] saved = Files.readAllBytes(state.resolve("ledger.json"));
        Files.writeString(state.resolve("ledger.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
        Files.delete(state.resolve("ledger.json"));
        assertThrows(IOException.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
        Files.write(state.resolve("ledger.json"), saved);
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            assertEquals("1", gateway.epoch("deployment-first"));
        }
    }

    @Test
    void evictedCompletedRequestsCannotBeRegisteredAgainAndRepublishedInTheSameEpoch() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObjectNode first = plan("deployment-first", OUTPUT, 2);
        JsonNode firstUnit = first.path("assignments").get(0);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> writes.incrementAndGet())) {
            gateway.admit(first);
            String credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            ObjectNode cycle = first;
            for (int sequence = 1; sequence <= 4; sequence++) {
                JsonNode unit = cycle.path("assignments").get(0);
                gateway.register(cycle, unit, status("1"));
                gateway.publish(credential, body(unit, "1"));
                cycle = successor(cycle);
            }
            assertEquals(4, writes.get());
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.register(first, firstUnit, status("1")));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, body(firstUnit, "1")));
            assertEquals(4, writes.get());
        }
    }

    @Test
    void validEnvelopeChecksumCannotHideAnActiveEpochThatConflictsWithTheTopicFence() throws Exception {
        Path state = temporary.resolve("gateway");
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            gateway.admit(plan("deployment-first", OUTPUT, 2));
        }
        ObjectNode envelope = (ObjectNode) RustRollout.json(Files.readString(state.resolve("ledger.json")));
        ((ObjectNode) envelope.path("payload").path("topics")).put(TOPICS.get(0), "2");
        envelope.put("checksum", org.opentsx.model.contracts.ExecutionAssignments.sha256(RustRollout.stringify(envelope.path("payload"))));
        Files.writeString(state.resolve("ledger.json"), RustRollout.stringify(envelope));
        assertThrows(IOException.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
    }

    @Test
    void anExistingLedgerWithoutItsInitializationMarkerIsNeverAccepted() throws Exception {
        Path state = temporary.resolve("gateway");
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            gateway.admit(plan("deployment-first", OUTPUT, 2));
        }
        Files.delete(state.resolve("initialized"));
        assertThrows(IOException.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
        assertTrue(Files.exists(state.resolve("ledger.json")));
    }

    @Test
    void failedInitialLedgerCommitLeavesAnInitializationMarkerAndCannotRestartWithAnEmptyAuthority() throws Exception {
        Path state = temporary.resolve("gateway");
        Files.createDirectories(state);
        Files.createDirectory(state.resolve("ledger.pending"));
        assertThrows(IOException.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
        assertTrue(Files.isRegularFile(state.resolve("initialized")));
        assertFalse(Files.exists(state.resolve("ledger.json")));
        Files.delete(state.resolve("ledger.pending"));
        assertThrows(IOException.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
    }

    @Test
    void sixtyThreeRotationsReserveTheLastOperationSlotForRetirement() throws Exception {
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> { })) {
            gateway.admit(plan);
            complete(gateway, plan, "1");
            for (int index = 1; index <= 63; index++) {
                JsonNode result = gateway.rotate("resume-" + index, "resume", Integer.toString(index), plan, null);
                assertEquals(Integer.toString(index + 1), result.path("publicationEpoch").asText());
            }
            assertEquals("64", gateway.epoch("deployment-first"));
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.rotate("resume-full", "resume", "64", plan, null));
            JsonNode retired = gateway.retire("retire-final", "deployment-first", "64");
            assertEquals("65", retired.path("publicationEpoch").asText());
            assertEquals("retired", retired.path("state").asText());
            assertEquals(retired, gateway.retire("retire-final", "deployment-first", "64"));
        }
    }

    @Test
    void aStaleCompletedCycleCannotRotateAuthorityAndAnExactRetrySurvivesLaterHeads() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObjectNode first = plan("deployment-first", OUTPUT, 2);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> writes.incrementAndGet())) {
            gateway.admit(first);
            complete(gateway, first, "1");
            ObjectNode second = successor(first);
            complete(gateway, second, "1");
            assertEquals(6, writes.get());
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.rotate("resume-stale", "resume", "1", first, null));
            assertEquals("1", gateway.epoch("deployment-first"));
            JsonNode rotated = gateway.rotate("resume-current", "resume", "1", second, null);
            assertEquals("2", rotated.path("publicationEpoch").asText());
            complete(gateway, successor(second), "2");
            assertEquals(9, writes.get());
            assertEquals(rotated, gateway.rotate("resume-current", "resume", "1", second, null));
            assertEquals("2", gateway.epoch("deployment-first"));
        }
    }

    @Test
    void aRetiredGroupCannotLoseOrLowerItsPersistedTopicFences() throws Exception {
        Path state = temporary.resolve("gateway");
        try (RustPublicationGateway gateway = new RustPublicationGateway(state, (topic, partitions, records) -> { })) {
            gateway.admit(plan("deployment-first", OUTPUT, 2));
            gateway.retire("stop-first", "deployment-first", "1");
        }
        ObjectNode original = (ObjectNode) RustRollout.json(Files.readString(state.resolve("ledger.json")));
        for (boolean missing : List.of(true, false)) {
            ObjectNode altered = original.deepCopy();
            ObjectNode topics = (ObjectNode) altered.path("payload").path("topics");
            if (missing) topics.remove(TOPICS.get(0)); else topics.put(TOPICS.get(0), "1");
            altered.put("checksum", org.opentsx.model.contracts.ExecutionAssignments.sha256(RustRollout.stringify(altered.path("payload"))));
            Files.writeString(state.resolve("ledger.json"), RustRollout.stringify(altered));
            assertThrows(IOException.class, () -> new RustPublicationGateway(state, (topic, partitions, records) -> { }));
        }
    }

    @Test
    void numericPublicationEpochIsRejectedBeforeWriterIoAndDoesNotConsumeThePreparedRequest() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        JsonNode unit = plan.path("assignments").get(0);
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, records) -> writes.incrementAndGet())) {
            gateway.admit(plan);
            gateway.register(plan, unit, status("1"));
            String credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            ObjectNode invalid = body(unit, "1").put("epoch", 1);
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, invalid));
            assertEquals(0, writes.get());
            gateway.publish(credential, body(unit, "1"));
            assertEquals(1, writes.get());
        }
    }

    @Test
    void numericRecordKeyIsRejectedBeforeWriterIoAndDoesNotConsumeThePreparedRequest() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObjectNode plan = plan("deployment-first", OUTPUT, 2);
        JsonNode unit = plan.path("assignments").get(0);
        RustPublicationGateway.Record original = RECORDS.get(0);
        List<RustPublicationGateway.Record> records = List.of(new RustPublicationGateway.Record("1111111111111111", original.timestamp(), original.payload()));
        try (RustPublicationGateway gateway = new RustPublicationGateway(temporary.resolve("gateway"), (topic, partitions, output) -> writes.incrementAndGet())) {
            gateway.admit(plan);
            gateway.register(plan, unit, status("1").put("preparedOutputDigest", RustPublicationGateway.digest(records)));
            String credential = gateway.grant("deployment-first", "worker-1").path("token").asText();
            ObjectNode valid = body(unit, "1");
            ((ObjectNode) valid.path("records").get(0)).put("key", "1111111111111111");
            ObjectNode invalid = valid.deepCopy();
            ((ObjectNode) invalid.path("records").get(0)).put("key", 1_111_111_111_111_111L);
            assertThrows(RustPublicationGateway.Refused.class, () -> gateway.publish(credential, invalid));
            assertEquals(0, writes.get());
            gateway.publish(credential, valid);
            assertEquals(1, writes.get());
        }
    }
}
