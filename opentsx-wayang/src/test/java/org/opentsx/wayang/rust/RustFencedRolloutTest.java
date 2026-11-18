package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;

class RustFencedRolloutTest {
    @TempDir Path directory;

    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }

    private static final class Fenced extends RustContinuousRolloutTest.Fake {
        final RustPublicationGateway gateway;
        final Map<String, JsonNode> grants = new HashMap<>();
        final AtomicInteger installations = new AtomicInteger();
        boolean empty = true;
        boolean forgeCompletion;
        boolean failInstallation;

        Fenced(RustPublicationGateway gateway) { this.gateway = gateway; }

        @Override RustRollout rollout() {
            return new RustRollout("kafscale-read:39093", Path.of("../systems/processors"),
                    RustContinuousRolloutTest.WORKERS, this, this, gateway);
        }

        @Override public JsonNode get(RustRollout.Worker worker, String route) {
            if (route.equals("/v1/publication")) {
                JsonNode grant = grants.get(worker.node());
                ObjectNode current = object();
                for (String field : List.of("publicationEpoch", "deployment", "operation")) {
                    if (grant == null) current.putNull(field);
                    else current.set(field, grant.path(field.equals("publicationEpoch") ? "epoch" : field));
                }
                return current;
            }
            JsonNode value = super.get(worker, route);
            if (route.equals("/v1/capabilities")) ((ObjectNode) value).put("publicationFencing", true);
            return value;
        }

        @Override public JsonNode post(RustRollout.Worker worker, String route, String body) {
            if (route.equals("/v1/publication")) {
                if (failInstallation && worker.node().equals("worker-2")) throw new IllegalStateException("installation reply unavailable");
                JsonNode installation = RustRollout.json(body);
                JsonNode grant = installation.path("grant");
                JsonNode current = grants.get(worker.node());
                if (current != null && !current.path("deployment").equals(grant.path("deployment")) && !installation.path("reset").asBoolean()) {
                    throw new RustWorkerClient.Rejected(409, "publication resume cannot change deployment identity");
                }
                grants.put(worker.node(), grant);
                installations.incrementAndGet();
                return object().put("state", "installed").setAll(Map.of("operation", grant.path("operation"),
                        "publicationEpoch", grant.path("epoch"), "node", grant.path("node")));
            }
            if (route.startsWith("/v1/activate/")) {
                String hash = route.substring(route.lastIndexOf('/') + 1);
                JsonNode saved = statuses.get(hash);
                if (!saved.path("state").asText().equals("completed")) {
                    JsonNode request = requests.get(hash);
                    ObjectNode publication = object().put("epoch", grants.get(worker.node()).path("epoch").asText())
                            .put("requestText", RustRollout.stringify(request));
                    var records = publication.putArray("records");
                    for (RustPublicationGateway.Record record : outputs(request)) records.addObject().put("key", record.key())
                            .put("timestamp", record.timestamp()).putArray("payload").add(1);
                    gateway.publish(grants.get(worker.node()).path("token").asText(), publication);
                }
            }
            ObjectNode status = (ObjectNode) super.post(worker, route, body);
            if (route.equals("/v1/prepare")) {
                JsonNode request = RustRollout.json(body);
                status.put("publicationEpoch", grants.get(worker.node()).path("epoch").asText())
                        .put("preparedOutputDigest", RustPublicationGateway.digest(outputs(request)))
                        .put("preparedOutputRecords", Integer.toString(outputs(request).size()));
                statuses.put(status.path("requestHash").asText(), status.deepCopy());
            } else if (route.startsWith("/v1/activate/")) {
                ObjectNode receipt = (ObjectNode) status.path("receipt");
                receipt.set("outputTopic", requests.get(status.path("requestHash").asText()).path("sink").path("topic"));
                receipt.put("outputDigest", forgeCompletion ? "f".repeat(64) : RustPublicationGateway.digest(outputs(requests.get(status.path("requestHash").asText()))));
                statuses.put(status.path("requestHash").asText(), status.deepCopy());
            }
            return status;
        }

        private static List<RustPublicationGateway.Record> outputs(JsonNode request) {
            JsonNode assignment = RustRollout.json(request.path("assignmentText").asText());
            long count = 0;
            for (JsonNode position : assignment.path("inputs").get(0).path("positions")) count += Long.parseLong(position.path("to").asText()) - Long.parseLong(position.path("from").asText());
            List<RustPublicationGateway.Record> outputs = new ArrayList<>();
            for (long index = 0; index < count; index++) outputs.add(new RustPublicationGateway.Record("1111111111111111", 1_000 + index, new byte[]{1}));
            return outputs;
        }

        @Override public void provision(String output, int partitions, boolean create) { assertEquals(3, partitions); }
        @Override public void requireEmpty(String output, int partitions) {
            if (!empty) throw new IllegalArgumentException("fenced deployment requires an empty output topic");
        }
    }

    private static JsonNode plan(Fenced fake, String deployment, String source) {
        return fake.rollout().planContinuous(deployment, source, "1790812800000000");
    }

    private static ObjectNode replacement(JsonNode plan, JsonNode result, String mode, JsonNode next) {
        ObjectNode request = object().put("operation", "switch-1").put("mode", mode).put("expectedEpoch", "1");
        request.set("plan", plan); request.set("result", result);
        if (next == null) request.putNull("replacement"); else request.set("replacement", next);
        return request;
    }

    @Test void completedRetryAfterRetirementDoesNotReopenAuthorityOrPublish() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            JsonNode plan = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            JsonNode result = fake.rollout().execute(plan);
            assertEquals("1", result.path("publicationEpoch").asText());
            assertEquals(3, writes.get());
            gateway.retire("stop-1", "deployment-1", "1");
            assertEquals(result, fake.rollout().execute(plan));
            assertEquals(3, writes.get());
            assertEquals("2", gateway.epoch("deployment-1"));
        }
    }

    @Test void resumedIncrementUsesRotatedAuthorityWithoutReinstallingIt() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            JsonNode plan = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            JsonNode result = fake.rollout().execute(plan);
            JsonNode switched = fake.rollout().replace(replacement(plan, result, "resume", null));
            assertEquals("2", switched.path("publicationEpoch").asText());
            assertEquals(switched, fake.rollout().replace(replacement(plan, result, "resume", null)));
            for (long[] ends : fake.ends) java.util.Arrays.fill(ends, 9);
            JsonNode next = fake.rollout().next(plan, result).path("plan");
            assertEquals("2", fake.rollout().execute(next).path("publicationEpoch").asText());
            assertEquals(6, fake.installations.get());
            assertEquals(6, writes.get());
        }
    }

    @Test void occupiedRestartOutputDoesNotRevokeOldAuthority() throws Exception {
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> {})) {
            Fenced fake = new Fenced(gateway);
            JsonNode plan = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            JsonNode result = fake.rollout().execute(plan);
            String changed = RustContinuousRolloutTest.SOURCE.replace("0.001", "0.002").replace("tsx.continuous.clean.v2", "tsx.restarted.clean.v2");
            JsonNode next = plan(fake, "deployment-2", changed);
            fake.empty = false;
            assertThrows(IllegalArgumentException.class, () -> fake.rollout().replace(replacement(plan, result, "restart", next)));
            assertEquals("1", gateway.epoch("deployment-1"));
            assertEquals("0", gateway.epoch("deployment-2"));
            fake.empty = true;
            assertEquals("2", fake.rollout().replace(replacement(plan, result, "restart", next)).path("publicationEpoch").asText());
            assertEquals("2", fake.rollout().execute(next).path("publicationEpoch").asText());
        }
    }

    @Test void partialInstallationRetryDoesNotPublishBeforeAllWorkersAreReady() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            JsonNode plan = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            fake.failInstallation = true;
            RuntimeException failure = assertThrows(org.apache.wayang.core.api.exception.WayangException.class, () -> fake.rollout().execute(plan));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, writes.get());
            fake.failInstallation = false;
            assertEquals("completed", fake.rollout().execute(plan).path("state").asText());
            assertEquals(3, fake.installations.get());
            assertEquals(3, writes.get());
        }
    }

    @Test void workerReceiptMustMatchAcknowledgedGatewayBytes() throws Exception {
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> {})) {
            Fenced fake = new Fenced(gateway);
            JsonNode plan = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            fake.forgeCompletion = true;
            RuntimeException failure = assertThrows(org.apache.wayang.core.api.exception.WayangException.class, () -> fake.rollout().execute(plan));
            assertInstanceOf(RustPublicationGateway.Refused.class, failure.getCause());
        }
    }

    @Test void boundedCompletedRetryAfterRetirementRejectsAChangedWorkerOutputDigest() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            JsonNode plan = fake.rollout().plan("deployment-1", RustContinuousRolloutTest.SOURCE, "1790812800000000");
            JsonNode completed = fake.rollout().execute(plan);
            gateway.retire("stop-1", "deployment-1", "1");
            assertEquals(completed, fake.rollout().execute(plan));
            for (JsonNode status : fake.statuses.values()) ((ObjectNode) status.path("receipt")).put("outputDigest", "f".repeat(64));
            RuntimeException failure = assertThrows(org.apache.wayang.core.api.exception.WayangException.class, () -> fake.rollout().execute(plan));
            assertInstanceOf(RustPublicationGateway.Refused.class, failure.getCause());
            assertEquals(3, writes.get());
            assertEquals("2", gateway.epoch("deployment-1"));
        }
    }

    @Test void resumeAfterTheSecondCompletedCycleContinuesAtSequenceThree() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            RustRollout rollout = fake.rollout();
            JsonNode first = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            JsonNode firstResult = rollout.execute(first);
            JsonNode second = rollout.next(first, firstResult).path("plan");
            assertEquals("2", second.path("sequence").asText());
            JsonNode secondResult = rollout.execute(second);
            assertEquals("2", rollout.replace(replacement(second, secondResult, "resume", null)).path("publicationEpoch").asText());
            JsonNode third = rollout.next(second, secondResult).path("plan");
            assertEquals("3", third.path("sequence").asText());
            assertEquals("2", rollout.execute(third).path("publicationEpoch").asText());
            assertEquals(9, writes.get());
            assertEquals(6, fake.installations.get());
        }
    }

    @Test void identicalSwitchRetryDoesNotNeedAnEvictedCompletedRequestOrRewindNewerWork() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            RustRollout rollout = fake.rollout();
            JsonNode first = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            JsonNode firstResult = rollout.execute(first);
            ObjectNode operation = replacement(first, firstResult, "resume", null);
            JsonNode switched = rollout.replace(operation);
            JsonNode current = first;
            JsonNode result = firstResult;
            for (int sequence = 2; sequence <= 3; sequence++) {
                current = rollout.next(current, result).path("plan");
                assertEquals(Integer.toString(sequence), current.path("sequence").asText());
                result = rollout.execute(current);
            }
            assertEquals(9, writes.get());
            assertEquals(switched, rollout.replace(operation));
            assertEquals(9, writes.get());
            assertEquals(6, fake.installations.get());
            JsonNode next = rollout.next(current, result).path("plan");
            assertEquals("4", next.path("sequence").asText());
            assertEquals("2", rollout.execute(next).path("publicationEpoch").asText());
            assertEquals(12, writes.get());
            assertEquals(6, fake.installations.get());
        }
    }

    @Test void aRetiredWorkerPoolRejectsAnotherDeploymentBeforeAdmittingItsAuthority() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            RustRollout rollout = fake.rollout();
            JsonNode first = plan(fake, "deployment-1", RustContinuousRolloutTest.SOURCE);
            assertEquals("completed", rollout.execute(first).path("state").asText());
            gateway.retire("stop-first", "deployment-1", "1");
            String changed = RustContinuousRolloutTest.SOURCE.replace("tsx.continuous.clean.v2", "tsx.restarted.clean.v2");
            JsonNode next = plan(fake, "deployment-2", changed);
            RuntimeException failure = assertThrows(RuntimeException.class, () -> rollout.execute(next));
            assertEquals("0", gateway.epoch("deployment-2"));
            assertInstanceOf(IllegalArgumentException.class, failure);
            assertEquals("2", gateway.epoch("deployment-1"));
            assertEquals(3, writes.get());
            assertEquals(3, fake.installations.get());
        }
    }

    @Test void concurrentFreshDeploymentsCannotBothAdmitTheSameWorkerPool() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        try (RustPublicationGateway gateway = new RustPublicationGateway(directory, (topic, partitions, records) -> writes.incrementAndGet())) {
            Fenced fake = new Fenced(gateway);
            CountDownLatch winnerPrepared = new CountDownLatch(1);
            CountDownLatch loserPrepared = new CountDownLatch(1);
            CountDownLatch releaseWinner = new CountDownLatch(1);
            CountDownLatch releaseLoser = new CountDownLatch(1);
            AtomicInteger winnerReads = new AtomicInteger();
            AtomicInteger loserReads = new AtomicInteger();
            AtomicReference<Thread> winnerThread = new AtomicReference<>();
            AtomicReference<Thread> loserThread = new AtomicReference<>();
            RustWorkerClient client = new RustWorkerClient() {
                @Override public JsonNode get(RustRollout.Worker worker, String route) {
                    JsonNode value;
                    synchronized (fake) { value = fake.get(worker, route); }
                    if (route.equals("/v1/publication")) {
                        try {
                            if (Thread.currentThread() == winnerThread.get() && winnerReads.incrementAndGet() == 3) {
                                winnerPrepared.countDown();
                                releaseWinner.await();
                            } else if (Thread.currentThread() == loserThread.get() && loserReads.incrementAndGet() == 3) {
                                loserPrepared.countDown();
                                releaseLoser.await();
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("worker preflight was interrupted", error);
                        }
                    }
                    return value;
                }
                @Override public JsonNode post(RustRollout.Worker worker, String route, String body) {
                    synchronized (fake) { return fake.post(worker, route, body); }
                }
            };
            RustRollout rollout = new RustRollout("kafscale-read:39093", Path.of("../systems/processors"),
                    RustContinuousRolloutTest.WORKERS, client, fake, gateway);
            JsonNode first = rollout.planContinuous("deployment-1", RustContinuousRolloutTest.SOURCE, "1790812800000000");
            JsonNode second = rollout.planContinuous("deployment-2", RustContinuousRolloutTest.SOURCE.replace("tsx.continuous.", "tsx.concurrent."), "1790812800000000");
            FutureTask<JsonNode> winner = new FutureTask<>(() -> rollout.execute(first));
            FutureTask<JsonNode> loser = new FutureTask<>(() -> rollout.execute(second));
            Thread firstThread = new Thread(winner, "fencing-winner");
            Thread secondThread = new Thread(loser, "fencing-loser");
            winnerThread.set(firstThread);
            loserThread.set(secondThread);
            firstThread.start();
            try {
                winnerPrepared.await();
                secondThread.start();
                var threads = ManagementFactory.getThreadMXBean();
                while (loserPrepared.getCount() != 0) {
                    var blocked = threads.getThreadInfo(secondThread.getId());
                    if (blocked != null && blocked.getThreadState() == Thread.State.BLOCKED && blocked.getLockInfo() != null
                            && blocked.getLockInfo().getClassName().equals(RustRollout.class.getName())
                            && blocked.getLockInfo().getIdentityHashCode() == System.identityHashCode(rollout)) break;
                    if (loser.isDone()) throw new AssertionError("competing execution finished before pool preflight synchronization");
                    Thread.onSpinWait();
                }
                releaseWinner.countDown();
                assertEquals("completed", winner.get().path("state").asText());
                releaseLoser.countDown();
                ExecutionException rejected = assertThrows(ExecutionException.class, loser::get);
                assertEquals("0", gateway.epoch("deployment-2"));
                assertInstanceOf(IllegalArgumentException.class, rejected.getCause());
                assertEquals("1", gateway.epoch("deployment-1"));
                assertEquals(3, writes.get());
                assertEquals(3, fake.installations.get());
                for (JsonNode grant : fake.grants.values()) assertEquals("deployment-1", grant.path("deployment").asText());
                assertThrows(IllegalArgumentException.class, () -> rollout.execute(second));
                assertEquals("0", gateway.epoch("deployment-2"));
            } finally {
                releaseWinner.countDown();
                releaseLoser.countDown();
                firstThread.join();
                secondThread.join();
            }
        }
    }
}
