package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class TsxSiteAgent implements AutoCloseable {
    private final String site;
    private final String node;
    private final String artifactDigest;
    private final String stateAbi;
    private final PublicKey driverKey;
    private final JsonNode policy;
    private final Clock clock;
    private final SiteState state;
    private final ConformanceHandshake.QualifiedRuntime runtime;
    private final ScheduledExecutorService timer;
    private SiteStatus activeStatus;
    private boolean unhealthy;

    public TsxSiteAgent(String site, String node, String artifactDigest, String stateAbi, PublicKey driverKey,
                        JsonNode policy, Path durableDirectory, Clock clock) {
        this(site, node, artifactDigest, stateAbi, driverKey, policy, durableDirectory, clock, null, timer());
    }

    public TsxSiteAgent(ConformanceHandshake.QualifiedRuntime runtime, PublicKey driverKey, JsonNode policy,
                        Path durableDirectory, Clock clock) {
        this(runtime, driverKey, policy, durableDirectory, clock, timer());
    }

    TsxSiteAgent(ConformanceHandshake.QualifiedRuntime runtime, PublicKey driverKey, JsonNode policy,
                 Path durableDirectory, Clock clock, ScheduledExecutorService timer) {
        this(runtime.site(), runtime.node(), runtime.digest(), runtime.stateAbi(), driverKey, policy, durableDirectory, clock, runtime, timer);
    }

    private TsxSiteAgent(String site, String node, String artifactDigest, String stateAbi, PublicKey driverKey,
                         JsonNode policy, Path durableDirectory, Clock clock, ConformanceHandshake.QualifiedRuntime runtime,
                         ScheduledExecutorService timer) {
        if (site == null || !site.matches("[a-z0-9][a-z0-9-]{0,62}") || node == null
                || !node.matches("[a-z0-9][a-z0-9-]{0,62}") || artifactDigest == null
                || !artifactDigest.matches("sha256:[0-9a-f]{64}") || stateAbi == null || stateAbi.isBlank()) {
            throw new IllegalArgumentException("site runtime identity is invalid");
        }
        SiteJson.fields(policy, "domains", "inputs", "outputs", "catalogs");
        RunnerPlan.strings(policy.get("domains"), true);
        if (!policy.get("inputs").isObject() || policy.get("inputs").isEmpty()) throw new IllegalArgumentException("site input policy is invalid");
        policy.get("inputs").fields().forEachRemaining(entry -> {
            if (!entry.getKey().matches("[a-z0-9][a-z0-9-]{0,62}")) throw SiteJson.invalid("invalid input binding policy");
            RunnerPlan.strings(entry.getValue(), true);
        });
        RunnerPlan.strings(policy.get("catalogs"), true);
        if (!policy.get("outputs").isObject()) throw new IllegalArgumentException("site output policy is invalid");
        policy.get("outputs").fields().forEachRemaining(entry -> {
            if (!Set.of("site", "signal").contains(entry.getKey())) throw SiteJson.invalid("unknown output cluster policy");
            RunnerPlan.strings(entry.getValue(), false);
        });
        this.site = site;
        this.node = node;
        this.artifactDigest = artifactDigest;
        this.stateAbi = stateAbi;
        this.driverKey = java.util.Objects.requireNonNull(driverKey);
        this.policy = policy.deepCopy();
        this.clock = java.util.Objects.requireNonNull(clock);
        this.runtime = runtime;
        this.timer = java.util.Objects.requireNonNull(timer);
        state = new SiteState(durableDirectory);
    }

    private static ScheduledExecutorService timer() {
        return Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "opentsx-site-status");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized JsonNode accept(String compact, Function<RunnerPlan, JsonNode> execution) {
        state.check();
        if (unhealthy) throw new IllegalStateException("site journal requires recovery");
        RunnerPlan plan = RunnerPlan.verify(compact, driverKey);
        if (!plan.site().equals(site) || !plan.assignment().path("node").asText().equals(node)) {
            throw new PlanRefusal("plan.site", "plan is assigned to another site or node");
        }
        ObjectNode jobs = state.value.with("jobs");
        JsonNode previous = jobs.path(plan.job());
        if (!previous.isMissingNode()) {
            int version = previous.path("planVersion").asInt();
            if (version > plan.planVersion() || version == plan.planVersion()
                    && !previous.path("commandHash").asText().equals(plan.commandHash())) {
                throw new PlanRefusal("plan.version", "plan version conflicts with accepted state");
            }
            if (version == plan.planVersion()) return previous.path("outcome").deepCopy();
            if (!previous.path("outcome").path("state").asText().equals("completed")) {
                throw new PlanRefusal("plan.busy", "the previous accepted assignment has not retired");
            }
        }
        if (!clock.instant().isBefore(plan.expires())) throw new PlanRefusal("plan.expired", "new activation has expired");
        if (clock.instant().isBefore(plan.issued())) throw SiteJson.invalid("plan issued time is in the future");
        qualify(plan);
        for (JsonNode job : jobs) {
            if (!job.path("outcome").path("state").asText().equals("completed")) {
                for (JsonNode own : job.path("inputs")) for (JsonNode requested : plan.assignment().path("inputs")) {
                    if (own.path("binding").equals(requested.path("binding")) && own.path("topic").equals(requested.path("topic"))) {
                        throw new PlanRefusal("plan.busy", "a live assignment already owns the whole topic");
                    }
                }
            }
        }
        Path checkpoint = checkpointPath(plan);
        requireContinuation(plan, checkpoint);
        ObjectNode entry = SiteJson.JSON.createObjectNode().put("planVersion", plan.planVersion()).put("commandHash", plan.commandHash());
        entry.set("inputs", plan.assignment().path("inputs"));
        entry.set("continuation", checkpointIdentity(plan));
        entry.set("outcome", outcome(plan, "accepted"));
        jobs.set(plan.job(), entry);
        try { state.save(); }
        catch (RuntimeException error) { unhealthy = true; throw error; }
        try {
            JsonNode result = execution.apply(plan);
            if (result == null) throw new IllegalStateException("site execution returned no outcome");
            ObjectNode completed = outcome(plan, "completed");
            completed.set("result", result.deepCopy());
            entry.set("outcome", completed);
            try { state.save(); }
            catch (RuntimeException error) { unhealthy = true; throw error; }
            return completed.deepCopy();
        } catch (RuntimeException error) {
            ObjectNode failed = outcome(plan, "failed");
            failed.put("error", "site execution failed");
            entry.set("outcome", failed);
            try { state.save(); }
            catch (RuntimeException persistence) { unhealthy = true; error.addSuppressed(persistence); }
            throw error;
        }
    }

    private void qualify(RunnerPlan plan) {
        if (!plan.assignment().path("artifactDigest").asText().equals(artifactDigest)
                || !RunnerPlan.strings(policy.path("catalogs"), true).contains(plan.assignment().path("catalog").asText())) {
            throw new PlanRefusal("plan.catalog", "runtime artifact or catalog is not qualified");
        }
        Set<String> domains = RunnerPlan.strings(policy.path("domains"), true);
        for (JsonNode input : plan.assignment().path("inputs")) {
            String topic = input.path("topic").asText();
            JsonNode inputs = policy.path("inputs").path(input.path("binding").asText());
            if (!domains.contains(topic.split("\\.")[1]) || !inputs.isArray()
                    || !RunnerPlan.strings(inputs, true).contains(topic)) throw policy();
        }
        for (JsonNode output : plan.document().path("outputs")) {
            String topic = output.path("topic").asText(), cluster = output.path("cluster").asText();
            JsonNode allowed = policy.path("outputs").path(cluster);
            if (!domains.contains(topic.split("\\.")[1]) || !allowed.isArray()
                    || !RunnerPlan.strings(allowed, false).contains(topic)
                    || cluster.equals("signal") && !(topic.endsWith(".aggregates.v2") || topic.endsWith(".signals.v1"))) throw policy();
        }
    }

    private static PlanRefusal policy() { return new PlanRefusal("plan.policy", "plan output or input violates local site policy"); }

    private static ObjectNode outcome(RunnerPlan plan, String status) {
        return SiteJson.JSON.createObjectNode().put("job", plan.job()).put("planVersion", plan.planVersion())
                .put("commandHash", plan.commandHash()).put("state", status);
    }

    public Path checkpointPath(RunnerPlan plan) {
        if (!plan.site().equals(site)) throw new PlanRefusal("plan.site", "checkpoint belongs to another site");
        return state.directory.resolve("checkpoints").resolve(plan.job()).resolve(Integer.toString(plan.planVersion())).resolve("checkpoint.json");
    }

    public synchronized void requireContinuation(RunnerPlan plan, Path checkpoint) {
        readCheckpoint(plan, checkpoint);
    }

    private Optional<JsonNode> readCheckpoint(RunnerPlan plan, Path checkpoint) {
        state.check();
        if (!checkpoint.toAbsolutePath().normalize().equals(checkpointPath(plan))) throw SiteJson.invalid("checkpoint is outside the admitted continuation");
        Path location = state.directory;
        for (Path part : state.directory.relativize(checkpoint)) {
            location = location.resolve(part);
            if (Files.isSymbolicLink(location)) throw SiteJson.invalid("checkpoint continuation is a symbolic link");
        }
        JsonNode expected = checkpointIdentity(plan);
        try {
            if (Files.isSymbolicLink(checkpoint) || Files.isSymbolicLink(checkpoint.getParent())) throw SiteJson.invalid("checkpoint continuation is a symbolic link");
            if (Files.exists(checkpoint)) {
                JsonNode saved = SiteJson.parse(SiteJson.read(checkpoint, 16 * 1024 * 1024), 16 * 1024 * 1024);
                SiteJson.fields(saved, "schema", "identity", "positions", "state", "receipt");
                if (!saved.path("schema").asText().equals("opentsx.site-checkpoint.v1") || !saved.path("identity").equals(expected)) {
                    throw SiteJson.invalid("checkpoint identity is incompatible");
                }
                return Optional.of(saved.deepCopy());
            }
        } catch (IOException error) { throw new IllegalStateException("cannot read admitted continuation", error); }
        return Optional.empty();
    }

    public JsonNode checkpointIdentity(RunnerPlan plan) { return plan.checkpointIdentity(stateAbi); }

    public synchronized Optional<JsonNode> checkpoint(RunnerPlan plan) {
        requireAdmitted(plan);
        return readCheckpoint(plan, checkpointPath(plan));
    }

    public synchronized void checkpoint(RunnerPlan plan, JsonNode positions, JsonNode operatorState, JsonNode receipt) {
        requireAdmitted(plan);
        if (activeStatus != null) activeStatus.check();
        if (positions == null || !positions.isObject() || operatorState == null || !operatorState.isObject()
                || receipt == null || !receipt.isObject()) throw SiteJson.invalid("checkpoint content is invalid");
        Path path = checkpointPath(plan);
        readCheckpoint(plan, path);
        ObjectNode saved = SiteJson.JSON.createObjectNode().put("schema", "opentsx.site-checkpoint.v1");
        saved.set("identity", checkpointIdentity(plan));
        saved.set("positions", positions.deepCopy());
        saved.set("state", operatorState.deepCopy());
        saved.set("receipt", receipt.deepCopy());
        SiteState.write(path, saved);
    }

    private void requireAdmitted(RunnerPlan plan) {
        state.check();
        if (!state.value.path("jobs").path(plan.job()).path("continuation").equals(checkpointIdentity(plan))) {
            throw SiteJson.invalid("checkpoint requires the identical admitted grant");
        }
    }

    public synchronized JsonNode resume(String compact, Function<RunnerPlan, JsonNode> execution) {
        state.check();
        if (unhealthy) throw new IllegalStateException("site journal requires recovery");
        RunnerPlan plan = RunnerPlan.verify(compact, driverKey);
        JsonNode entry = state.value.path("jobs").path(plan.job());
        if (!entry.path("commandHash").asText().equals(plan.commandHash())
                || !entry.path("continuation").equals(checkpointIdentity(plan))) throw SiteJson.invalid("recovery requires the identical admitted grant");
        if (entry.path("outcome").path("state").asText().equals("completed")) return entry.path("outcome").deepCopy();
        if (!Files.isRegularFile(checkpointPath(plan))) throw SiteJson.invalid("recovery requires a retained compatible checkpoint");
        qualify(plan);
        requireContinuation(plan, checkpointPath(plan));
        JsonNode result = execution.apply(plan);
        if (result == null) throw new IllegalStateException("site recovery returned no outcome");
        ObjectNode completed = outcome(plan, "completed");
        completed.set("result", result.deepCopy());
        ((ObjectNode) entry).set("outcome", completed);
        try { state.save(); }
        catch (RuntimeException error) { unhealthy = true; throw error; }
        return completed.deepCopy();
    }

    public synchronized Optional<JsonNode> pollOnce(TsxPlanClient client, Function<RunnerPlan, JsonNode> execution) {
        state.check();
        if (runtime == null) throw new IllegalStateException("polling requires an explicit qualified runtime version");
        String after = state.value.path("deliveryCursor").asText("0");
        JsonNode response = client.next(after);
        SiteJson.fields(response, "deliveries", "next");
        JsonNode deliveries = response.path("deliveries");
        if (!deliveries.isArray() || deliveries.size() > 1) throw SiteJson.invalid("invalid delivery page");
        if (deliveries.isEmpty()) {
            if (!response.path("next").asText().equals(after)) throw SiteJson.invalid("empty delivery changed the cursor");
            return Optional.empty();
        }
        JsonNode delivery = deliveries.get(0);
        SiteJson.fields(delivery, "sequence", "job", "planVersion", "plan");
        String sequence = SiteJson.text(delivery, "sequence");
        if (SiteJson.decimal(sequence, true) <= SiteJson.decimal(after, false) || !response.path("next").asText().equals(sequence)) {
            throw SiteJson.invalid("delivery cursor did not advance");
        }
        String compact = SiteJson.text(delivery, "plan");
        RunnerPlan plan = RunnerPlan.verify(compact, driverKey);
        if (!plan.job().equals(delivery.path("job").asText()) || !delivery.path("planVersion").isIntegralNumber()
                || !delivery.path("planVersion").canConvertToInt() || plan.planVersion() != delivery.path("planVersion").intValue()) {
            throw SiteJson.invalid("delivery identity differs from the signed command");
        }
        JsonNode outcome;
        ObjectNode acknowledgement = SiteJson.JSON.createObjectNode().put("job", plan.job()).put("planVersion", plan.planVersion())
                .put("commandHash", plan.commandHash());
        JsonNode previous = state.value.path("deliveryOutcomes").path(sequence);
        if (previous.isObject()) {
            JsonNode recorded = previous.path("acknowledgement");
            for (String name : Set.of("job", "planVersion", "commandHash")) {
                if (!recorded.path(name).equals(acknowledgement.path(name))) throw SiteJson.invalid("retained delivery identity conflicts");
            }
            acknowledgement = (ObjectNode) recorded.deepCopy();
            outcome = previous.path("outcome").deepCopy();
        } else {
            try {
                outcome = accept(compact, admitted -> runWithStatus(admitted, execution, value -> client.status(admitted.job(), value)));
                acknowledgement.put("result", "accepted");
            } catch (PlanRefusal error) {
                if (state.value.path("jobs").path(plan.job()).path("commandHash").asText().equals(plan.commandHash())) throw error;
                acknowledgement.put("result", "refused").put("code", error.code()).put("message", error.getMessage());
                outcome = acknowledgement.deepCopy();
            }
            ObjectNode receipt = SiteJson.JSON.createObjectNode();
            receipt.set("acknowledgement", acknowledgement);
            receipt.set("outcome", outcome);
            state.value.with("deliveryOutcomes").set(sequence, receipt);
            state.save();
        }
        client.ack(sequence, acknowledgement);
        state.value.put("deliveryCursor", sequence);
        state.save();
        return Optional.of(outcome);
    }

    JsonNode runWithStatus(RunnerPlan plan, Function<RunnerPlan, JsonNode> execution, Consumer<JsonNode> sink) {
        requireAdmitted(plan);
        if (runtime == null) throw new IllegalStateException("status reporting requires an explicit qualified runtime version");
        SiteStatus status = new SiteStatus(plan, runtime, clock, timer, sink);
        activeStatus = status;
        try { return status.execute(execution); }
        finally { activeStatus = null; }
    }

    @Override public void close() { timer.shutdownNow(); state.close(); }
}
