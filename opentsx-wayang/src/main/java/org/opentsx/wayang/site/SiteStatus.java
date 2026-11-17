package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

final class SiteStatus {
    private final RunnerPlan plan;
    private final ConformanceHandshake.QualifiedRuntime runtime;
    private final Clock clock;
    private final ScheduledExecutorService timer;
    private final Consumer<JsonNode> sink;
    private ScheduledFuture<?> task;
    private RuntimeException failure;
    private boolean finished;
    private long committedRecords;

    SiteStatus(RunnerPlan plan, ConformanceHandshake.QualifiedRuntime runtime, Clock clock,
               ScheduledExecutorService timer, Consumer<JsonNode> sink) {
        this.plan = plan;
        this.runtime = runtime;
        this.clock = clock;
        this.timer = timer;
        this.sink = sink;
    }

    JsonNode execute(Function<RunnerPlan, JsonNode> execution) {
        try {
            synchronized (this) {
                emit("running", null);
                task = timer.scheduleAtFixedRate(this::heartbeat, 30, 30, TimeUnit.SECONDS);
            }
            JsonNode receipt = execution.apply(plan);
            synchronized (this) {
                stop();
                check();
                committedRecords = count(receipt, "inputRecords");
                emit("stopped", null);
            }
            return receipt;
        } catch (RuntimeException error) {
            synchronized (this) {
                stop();
                if (failure != null && failure != error) error.addSuppressed(failure);
                try { emit("failed", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
                catch (RuntimeException reporting) { if (reporting != error && reporting != failure) error.addSuppressed(reporting); }
            }
            throw error;
        } finally {
            synchronized (this) { stop(); }
        }
    }

    private synchronized void heartbeat() {
        if (finished || failure != null) return;
        try { emit("running", null); }
        catch (RuntimeException error) { failure = error; }
    }

    synchronized void check() { if (failure != null) throw failure; }

    private void stop() {
        finished = true;
        if (task != null) task.cancel(false);
    }

    private void emit(String state, String error) {
        ObjectNode status = SiteJson.JSON.createObjectNode().put("schema", "opentsx.dag-status.v1")
                .put("dag", plan.job()).put("job", plan.job()).put("site", plan.site()).put("planVersion", plan.planVersion())
                .put("track", "A").put("specHash", plan.document().path("specHash").asText()).put("state", state)
                .put("records", committedRecords).putNull("watermark").put("skippedBlocks", 0).put("at", clock.instant().toString());
        status.putArray("waitingFor");
        status.putObject("artifact").put("version", runtime.version()).put("digest", runtime.digest());
        if (error != null) status.put("error", error);
        sink.accept(status);
    }

    static long count(JsonNode value, String field) {
        if (value == null || !value.path(field).isIntegralNumber() || !value.path(field).canConvertToLong()
                || value.path(field).longValue() < 0) throw new IllegalArgumentException("invalid nonnegative counter " + field);
        return value.path(field).longValue();
    }

    static void validate(JsonNode status, RunnerPlan plan, ConformanceHandshake.QualifiedRuntime runtime) {
        if (status == null || !status.isObject()) throw SiteJson.invalid("status must be an object");
        for (String name : List.of("schema", "dag", "job", "site", "planVersion", "track", "artifact", "specHash", "state",
                "records", "watermark", "waitingFor", "skippedBlocks", "at")) if (!status.has(name)) throw SiteJson.invalid("missing status field " + name);
        if (!status.path("schema").asText().equals("opentsx.dag-status.v1") || !status.path("dag").asText().equals(plan.job())
                || !status.path("job").asText().equals(plan.job()) || !status.path("site").asText().equals(plan.site())
                || !status.path("track").asText().equals("A") || !status.path("specHash").equals(plan.document().path("specHash"))) {
            throw SiteJson.invalid("status identity differs");
        }
        SiteJson.fields(status.path("artifact"), "version", "digest");
        if (!status.path("artifact").path("version").asText().equals(runtime.version())
                || !status.path("artifact").path("digest").asText().equals(runtime.digest())) throw SiteJson.invalid("status artifact is not qualified");
        String state = SiteJson.text(status, "state");
        if (!Set.of("running", "stopped", "failed").contains(state)) throw SiteJson.invalid("invalid bounded site state");
        count(status, "records");
        count(status, "skippedBlocks");
        JsonNode watermark = status.path("watermark");
        if (!watermark.isNull()) RunnerPlan.instant(status, "watermark");
        RunnerPlan.instant(status, "at");
        Set<String> waiting = RunnerPlan.strings(status.path("waitingFor"), false);
        if (waiting.size() > 10 || waiting.stream().anyMatch(id -> !id.matches("[0-9a-f]{16}"))) throw SiteJson.invalid("invalid waiting series inventory");
        if (state.equals("failed") && (!status.path("error").isTextual() || status.path("error").asText().isBlank())) throw SiteJson.invalid("failed status requires an error");
        if (status.has("error") && !status.path("error").isTextual()) throw SiteJson.invalid("invalid status error");
    }
}
