package org.opentsx.wayang.rust;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.wayang.basic.operators.UnionAllOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.wayang.DagToWayang;
import org.opentsx.retrieval.vector.DagPlan;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class RustRollout {
    public static final String SCHEMA = "opentsx.local-rollout.v1";
    public static final String CONTINUOUS_SCHEMA = "opentsx.local-continuous-rollout.v1";
    public static final String INCREMENT_SCHEMA = "opentsx.local-increment.v1";
    public static final List<String> OPERATORS = List.of("decode.avro-soe", "dq.checks", "convert.unit", "resample");
    private static final List<String> LIMITS = List.of("records", "payloadBytes", "inputBytes", "samples", "outputBytes", "series", "stateBytes");
    private static final Map<String, Integer> CEILINGS = Map.of("records", 10_000, "payloadBytes", 1_048_576,
            "inputBytes", 16_777_216, "samples", 100_000, "outputBytes", 16_777_216, "series", 64, "stateBytes", 1_048_576);
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(262_144).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(32_768).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Worker(String node, String endpoint) {
        public Worker {
            if (node == null || !node.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid worker node");
            URI uri = URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()) {
                throw new IllegalArgumentException("worker endpoint must be an internal HTTP origin");
            }
        }
    }

    public record Position(int partition, long from, long to) { }
    public record Inventory(Map<String, List<Position>> inputs, int outputPartitions, boolean createOutput) { }

    public interface Metadata {
        Inventory inspect(List<String> topics, String output);
        void provision(String output, int partitions, boolean mayCreate);
        default void requireEmpty(String output, int partitions) { throw new IllegalStateException("output emptiness cannot be verified"); }
    }

    private final String bootstrap;
    private final Path catalog;
    private final List<Worker> workers;
    private final RustWorkerClient client;
    private final Metadata metadata;
    private final RustPublicationGateway publication;

    public RustRollout(String bootstrap, Path catalog, List<Worker> workers, RustWorkerClient client, Metadata metadata) {
        this(bootstrap, catalog, workers, client, metadata, null);
    }

    public RustRollout(String bootstrap, Path catalog, List<Worker> workers, RustWorkerClient client, Metadata metadata, RustPublicationGateway publication) {
        if (workers.isEmpty() || workers.size() > 3 || workers.stream().map(Worker::node).distinct().count() != workers.size()
                || workers.stream().map(Worker::endpoint).distinct().count() != workers.size()) {
            throw new IllegalArgumentException("configure one to three distinct worker nodes and endpoints");
        }
        this.bootstrap = bootstrap;
        this.catalog = catalog;
        this.workers = workers.stream().sorted(Comparator.comparing(Worker::node)).toList();
        this.client = client;
        this.metadata = metadata;
        this.publication = publication;
    }

    public ObjectNode capabilities() {
        ObjectNode result = JSON.createObjectNode().put("schema", "opentsx.local-rollout-capabilities.v1").put("profile", "bounded");
        result.set("operators", JSON.valueToTree(OPERATORS));
        ArrayNode nodes = result.putArray("workers");
        String artifact = null;
        boolean incremental = true;
        for (Worker worker : workers) {
            JsonNode capability = client.get(worker, "/v1/capabilities");
            validateCapability(worker, capability);
            String digest = capability.path("artifactDigest").asText();
            if (artifact != null && !artifact.equals(digest)) throw new IllegalArgumentException("workers must run the same qualified artifact");
            artifact = digest;
            JsonNode chains = capability.path("limits").path("incrementalChains");
            incremental &= capability.path("incrementalRequestSchema").asText().equals(INCREMENT_SCHEMA)
                    && chains.isIntegralNumber() && chains.canConvertToInt() && chains.asInt() == 1;
            nodes.add(capability);
        }
        ArrayNode profiles = result.putArray("profiles").add("bounded");
        if (incremental) profiles.add("continuous");
        if (publication != null) {
            result.put("publicationFencing", true);
            result.putArray("switchModes").add("resume").add("restart");
            for (JsonNode capability : nodes) if (!capability.path("publicationFencing").asBoolean()) throw new IllegalStateException("worker publication fencing is not configured");
        }
        return result;
    }

    public ObjectNode plan(String deployment, String sourceText, String createdAt) {
        return plan(deployment, sourceText, createdAt, null, false);
    }

    public ObjectNode planContinuous(String deployment, String sourceText, String createdAt) {
        return plan(deployment, sourceText, createdAt, null, true);
    }

    public ObjectNode next(JsonNode previous, JsonNode result) {
        validatePlan(previous);
        if (!previous.path("schema").asText().equals(CONTINUOUS_SCHEMA)) throw new IllegalArgumentException("a successor requires a continuous plan");
        verifyCompleted(previous, result);
        ObjectNode plan = plan(previous.path("deployment").asText(), previous.path("sourceDagText").asText(),
                previous.path("createdAtMicros").asText(), previous, true);
        boolean changed = false;
        for (JsonNode unit : plan.path("assignments")) {
            JsonNode assignment = json(json(unit.path("requestText").asText()).path("assignmentText").asText());
            for (JsonNode cursor : assignment.path("inputs").get(0).path("positions")) changed |= !cursor.path("from").equals(cursor.path("to"));
        }
        ObjectNode answer = JSON.createObjectNode().put("state", changed ? "ready" : "idle");
        if (changed) answer.set("plan", plan); else answer.putNull("plan");
        return answer;
    }

    private void verifyCompleted(JsonNode previous, JsonNode result) {
        allowed(result, Set.of("deployment", "planHash", "state", "assignments", "error", "publicationEpoch"));
        if (!result.path("deployment").equals(previous.path("deployment")) || !result.path("planHash").equals(previous.path("planHash"))
                || !result.path("state").asText().equals("completed") || !result.path("assignments").isArray()
                || result.path("assignments").size() != previous.path("assignments").size()) {
            throw new IllegalArgumentException("a successor requires the exact completed previous outcome");
        }
        Set<String> nodes = new HashSet<>();
        for (JsonNode status : result.path("assignments")) {
            JsonNode unit = null;
            for (JsonNode candidate : previous.path("assignments")) if (candidate.path("node").equals(status.path("node"))) unit = candidate;
            if (unit == null || !nodes.add(status.path("node").asText()) || !status.path("state").asText().equals("completed")
                    || status.path("activationRetryable").asBoolean(true)) throw new IllegalArgumentException("the previous worker inventory is not complete");
            verifyStatus(unit, status);
        }
    }

    private ObjectNode plan(String deployment, String sourceText, String createdAt, JsonNode previous, boolean continuous) {
        if (deployment == null || !deployment.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid deployment identity");
        decimal(createdAt);
        JsonNode dag = source(sourceText);
        List<String> topics = texts(dag.path("source").path("topics"), "source topics", workers.size());
        if (topics.isEmpty() || topics.stream().anyMatch(t -> !ExecutionAssignments.validTopic(t))) {
            throw new IllegalArgumentException("select one to three distinct complete OpenTSx topics");
        }
        JsonNode sink = dag.path("sinks").get(0);
        String output = sink.path("topic").asText();
        if (!ExecutionAssignments.validTopic(output) || topics.contains(output)) throw new IllegalArgumentException("output must be a distinct valid OpenTSx topic");
        ObjectNode capability = capabilities();
        if (continuous && !texts(capability.path("profiles"), "profiles", 2).contains("continuous")) throw new IllegalArgumentException("workers do not support continuous increments");
        Inventory inventory = metadata.inspect(topics, output);
        if (inventory.outputPartitions() < 1 || inventory.outputPartitions() > 128) throw new IllegalArgumentException("output partition capacity exceeded");
        if (previous != null && (inventory.createOutput()
                || !Integer.toString(inventory.outputPartitions()).equals(previous.path("output").path("partitionCount").asText()))) {
            throw new IllegalArgumentException("continuous output topic inventory changed");
        }
        long sequence = previous == null ? 1 : Math.addExact(decimal(previous.path("sequence").asText()), 1);
        ObjectNode plan = JSON.createObjectNode().put("schema", continuous ? CONTINUOUS_SCHEMA : SCHEMA).put("deployment", deployment)
                .put("sourceDagText", sourceText).put("specHash", ExecutionAssignments.sha256(sourceText))
                .put("createdAtMicros", createdAt);
        if (continuous) {
            plan.put("sequence", Long.toString(sequence));
            if (previous == null) plan.putNull("previousPlanHash"); else plan.set("previousPlanHash", previous.path("planHash"));
        }
        plan.putObject("output").put("topic", output).put("partitionCount", Integer.toString(inventory.outputPartitions()))
                .put("create", inventory.createOutput());
        ArrayNode assignments = plan.putArray("assignments");
        List<String> envelopes = new ArrayList<>();
        for (int index = 0; index < topics.size(); index++) {
            String topic = topics.get(index);
            Worker worker = workers.get(index);
            JsonNode cap = capability.path("workers").get(index);
            List<Position> positions = inventory.inputs().get(topic);
            if (positions == null || positions.isEmpty() || positions.size() > 128
                    || positions.size() > cap.path("limits").path("partitions").asInt()) {
                throw new IllegalArgumentException("whole topic exceeds assigned worker capacity: " + topic);
            }
            JsonNode previousUnit = previous == null ? null : previous.path("assignments").get(index);
            if (continuous) positions = incrementPositions(positions, previousUnit, cap, sequence);
            ObjectNode fragment = dag.deepCopy();
            ((ObjectNode) fragment.path("source")).putArray("topics").add(topic);
            String fragmentText = stringify(fragment);
            String fragmentId = "fragment-" + (index + 1);
            ObjectNode assignment = JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                    .put("deployment", deployment).put("generation", "1").put("assignment", "assignment-" + (index + 1))
                    .put("attempt", "attempt-1").put("fence", "1").put("fragment", fragmentId)
                    .put("site", cap.path("site").asText()).put("node", worker.node()).put("runtime", "rust")
                    .put("catalog", "tsx-catalog/1").put("artifactDigest", cap.path("artifactDigest").asText())
                    .put("specHash", plan.path("specHash").asText()).put("sourceDagText", sourceText)
                    .put("fragmentHash", ExecutionAssignments.sha256(fragmentText)).put("fragmentText", fragmentText).put("mode", "bounded");
            ObjectNode input = assignment.putArray("inputs").addObject().put("binding", "local").put("topic", topic)
                    .put("partitionCount", Integer.toString(positions.size()));
            ArrayNode cursor = input.putArray("positions");
            long records = 0;
            for (int partition = 0; partition < positions.size(); partition++) {
                Position position = positions.get(partition);
                if (position.partition() != partition || position.from() < 0 || position.to() < position.from()
                        || (!continuous && position.from() != 0)) {
                    throw new IllegalArgumentException("bounded rollout requires every retained partition from offset zero");
                }
                records = Math.addExact(records, position.to() - position.from());
                cursor.addObject().put("partition", Integer.toString(partition)).put("from", Long.toString(position.from())).put("to", Long.toString(position.to()));
            }
            if (records > cap.path("limits").path("records").asLong()) throw new IllegalArgumentException("whole topic exceeds assigned record capacity");
            String envelope = stringify(assignment);
            ExecutionAssignments.validate(envelope);
            envelopes.add(envelope);
            ObjectNode request = JSON.createObjectNode().put("schema", continuous ? INCREMENT_SCHEMA : "opentsx.local-bounded-run.v1")
                    .put("assignmentText", envelope).put("createdAtMicros", createdAt);
            if (continuous) {
                request.put("sequence", Long.toString(sequence));
                if (previousUnit == null) request.putNull("previousRequestHash"); else request.put("previousRequestHash", requestHash(previousUnit));
            }
            ObjectNode binding = request.putObject("bindings").putObject("local").put("bootstrap", bootstrap);
            binding.putArray("inputs").add(topic);
            binding.putArray("outputs").add(output);
            request.putObject("sink").put("name", sinkName(dag)).put("binding", "local").put("topic", output)
                    .put("partitionCount", Integer.toString(inventory.outputPartitions()));
            ObjectNode limits = request.putObject("limits");
            for (String name : LIMITS) limits.put(name, cap.path("limits").path(name).asInt());
            String requestText = stringify(request);
            if (requestText.getBytes(StandardCharsets.UTF_8).length > 65_536) throw new IllegalArgumentException("compiled request exceeds its byte limit");
            assignments.addObject().put("node", worker.node()).put("endpoint", worker.endpoint()).put("inputTopic", topic)
                    .put("requestText", requestText);
        }
        ExecutionAssignments.validateSet(envelopes);
        plan.put("planHash", hash(plan));
        if (stringify(plan).getBytes(StandardCharsets.UTF_8).length > 262_144) throw new IllegalArgumentException("physical plan exceeds its byte limit");
        return plan;
    }

    private static List<Position> incrementPositions(List<Position> inventory, JsonNode previousUnit, JsonNode capability, long sequence) {
        JsonNode cursors = previousUnit == null ? null
                : json(json(previousUnit.path("requestText").asText()).path("assignmentText").asText()).path("inputs").get(0).path("positions");
        if (cursors != null && cursors.size() != inventory.size()) throw new IllegalArgumentException("continuous whole-topic partition count changed");
        long[] from = new long[inventory.size()];
        long[] to = new long[inventory.size()];
        long[] end = new long[inventory.size()];
        List<Integer> active = new ArrayList<>();
        int first = (int) ((sequence - 1) % inventory.size());
        for (int partition = 0; partition < inventory.size(); partition++) {
            Position position = inventory.get(partition);
            long start = cursors == null ? 0 : decimal(cursors.get(partition).path("to").asText());
            if (position.partition() != partition || position.from() < 0 || position.from() > start || position.to() < start) {
                throw new IllegalArgumentException("continuous cursor is outside the retained whole-topic inventory");
            }
            from[partition] = to[partition] = start;
            end[partition] = position.to();
        }
        for (int offset = 0; offset < inventory.size(); offset++) {
            int partition = (first + offset) % inventory.size();
            if (to[partition] < end[partition]) active.add(partition);
        }
        long remaining = incrementBudget(capability);
        while (remaining > 0 && !active.isEmpty()) {
            long share = Math.max(1, remaining / active.size());
            List<Integer> pending = new ArrayList<>();
            for (int partition : active) {
                long take = Math.min(remaining, Math.min(share, end[partition] - to[partition]));
                to[partition] = Math.addExact(to[partition], take);
                remaining -= take;
                if (to[partition] < end[partition]) pending.add(partition);
            }
            active = pending;
        }
        List<Position> result = new ArrayList<>(inventory.size());
        for (int partition = 0; partition < inventory.size(); partition++) result.add(new Position(partition, from[partition], to[partition]));
        return result;
    }

    private static long incrementBudget(JsonNode capability) {
        JsonNode limits = capability.path("limits");
        if (!limits.path("incrementalChains").isIntegralNumber() || !limits.path("incrementalChains").canConvertToInt()
                || limits.path("incrementalChains").asInt() != 1) throw new IllegalArgumentException("worker must own one incremental chain");
        JsonNode episode = limits.path("episodeSamples");
        if (!episode.isIntegralNumber() || !episode.canConvertToInt() || episode.asInt() < 1 || episode.asInt() > 25_000) {
            throw new IllegalArgumentException("invalid worker episode capacity");
        }
        long budget = Math.min(limits.path("records").asLong(), Math.min(
                limits.path("inputBytes").asLong() / limits.path("payloadBytes").asLong(),
                limits.path("samples").asLong() / episode.asInt()));
        if (budget < 1) throw new IllegalArgumentException("worker capacities cannot admit one maximum-sized episode");
        return budget;
    }

    public synchronized ObjectNode execute(JsonNode plan) {
        validatePlan(plan);
        if (publication != null && publication.epoch(plan.path("deployment").asText()).equals("0")) {
            for (JsonNode unit : plan.path("assignments")) {
                JsonNode current = client.get(worker(unit), "/v1/publication");
                fields(current, Set.of("publicationEpoch", "deployment", "operation"));
                if (!current.path("publicationEpoch").isNull() || !current.path("deployment").isNull() || !current.path("operation").isNull()) throw new IllegalArgumentException("the worker pool already retains an execution chain; use its active replacement operation or a fresh isolated pool");
            }
        }
        JsonNode dag = source(plan.path("sourceDagText").asText());
        AtomicReference<JsonNode> result = new AtomicReference<>();
        RustStageAction action = () -> {
            JsonNode answer = stage(plan);
            if (!result.compareAndSet(null, answer)) throw new IllegalStateException("a deployment stage executed twice");
            if (!answer.path("state").asText().equals("completed")) throw new IllegalStateException("Rust deployment failed: " + answer.path("error").asText());
            return answer;
        };
        List<String> ids = new ArrayList<>();
        List<JsonNode> chainNodes = new ArrayList<>();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        dag.path("nodes").forEach(node -> byId.put(node.path("id").asText(), node));
        JsonNode current = byId.get(dag.path("sinks").get(0).path("from").asText());
        while (!current.path("op").asText().equals("decode.avro-soe")) {
            chainNodes.add(current);
            current = byId.get(current.path("in").get(0).asText());
        }
        ids.add(current.path("id").asText());
        Collections.reverse(chainNodes);
        Operator upstream = null;
        for (JsonNode assignment : plan.path("assignments")) {
            RustTopicSource topic = new RustTopicSource(assignment.path("inputTopic").asText(), ids, action);
            if (upstream == null) upstream = topic;
            else {
                UnionAllOperator<SpecificRecord> union = new UnionAllOperator<>(DataSetType.createDefault(SpecificRecord.class));
                upstream.connectTo(0, union, 0);
                topic.connectTo(0, union, 1);
                upstream = union;
            }
        }
        if (!chainNodes.isEmpty()) {
            RustSeriesChain chain = new RustSeriesChain(chainNodes, action);
            upstream.connectTo(0, chain, 0);
            upstream = chain;
        }
        JsonNode selectedSink = dag.path("sinks").get(0);
        RustTopicSink sink = new RustTopicSink(selectedSink.path("topic").asText(), selectedSink.path("from").asText(), action);
        upstream.connectTo(0, sink, 0);
        Configuration configuration = new Configuration();
        configuration.setProperty("wayang.core.log.enabled", "false");
        try { new WayangContext(configuration).with(new RustPlugin()).execute(new WayangPlan(sink)); }
        catch (RuntimeException error) {
            if (result.get() == null || !result.get().path("state").asText().equals("failed")) throw error;
        }
        JsonNode answer = result.get();
        if (answer == null) throw new IllegalStateException("Wayang did not execute the Rust deployment stage");
        return (ObjectNode) answer;
    }

    private ObjectNode stage(JsonNode plan) {
        JsonNode output = plan.path("output");
        metadata.provision(output.path("topic").asText(), Integer.parseInt(output.path("partitionCount").asText()), output.path("create").asBoolean());
        List<JsonNode> units = new ArrayList<>();
        plan.path("assignments").forEach(units::add);
        if (publication != null) {
            List<Call> saved = parallel(units, unit -> client.get(worker(unit), "/v1/jobs/" + requestHash(unit)));
            if (saved.stream().allMatch(call -> call.error() == null && call.status().path("state").asText().equals("completed"))) {
                ArrayNode statuses = JSON.createArrayNode();
                for (Call call : saved) {
                    verifyStatus(call.unit(), call.status());
                    requirePublicationEpoch(call.status());
                    publication.verifyCompletion(call.unit(), call.status());
                    statuses.add(call.status());
                }
                return outcome(plan, "completed", statuses, null);
            }
            for (Call call : saved) if (call.error() != null && !(call.error() instanceof RustWorkerClient.Rejected rejected && rejected.status() == 404)) throw call.error();
            if (publication.epoch(plan.path("deployment").asText()).equals("0")) metadata.requireEmpty(output.path("topic").asText(), Integer.parseInt(output.path("partitionCount").asText()));
            publication.admit(plan);
            for (JsonNode unit : units) installPublication(plan, unit, false, null);
        }
        List<Call> prepared = parallel(units, unit -> client.post(worker(unit), "/v1/prepare", unit.path("requestText").asText()));
        if (CONTINUOUS_SCHEMA.equals(plan.path("schema").asText()) && prepared.stream().anyMatch(call -> call.error() != null
                && !(call.error() instanceof RustWorkerClient.Rejected rejected && Set.of(400, 409, 413, 422).contains(rejected.status())))) {
            throw new IllegalStateException("worker preparation requires identical retry; retain private candidates");
        }
        if (prepared.stream().anyMatch(call -> call.error() != null)) return abort(plan, prepared, "worker preparation failed");
        try {
            Set<String> inputs = new HashSet<>();
            Set<String> outputs = new HashSet<>();
            for (Call call : prepared) {
                verifyStatus(call.unit(), call.status());
                String state = call.status().path("state").asText();
                if (!state.equals("prepared") && !state.equals("completed")
                        && !(state.equals("failed") && call.status().path("activationRetryable").asBoolean())) {
                    throw new IllegalArgumentException("worker is not prepared");
                }
                for (String id : series(call.status(), "inputSeries")) if (!inputs.add(id)) throw new IllegalArgumentException("one source series occurs in multiple whole topics");
                for (String id : series(call.status(), "outputSeries")) if (!outputs.add(id)) throw new IllegalArgumentException("derived series ownership collides across whole topics");
            }
        } catch (RuntimeException error) {
            return abort(plan, prepared, error.getMessage());
        }
        if (publication != null) for (Call call : prepared) publication.register(plan, call.unit(), call.status());
        List<Call> activated = parallel(units, unit -> client.post(worker(unit), "/v1/activate/" + requestHash(unit), ""));
        ArrayNode statuses = JSON.createArrayNode();
        boolean completed = true;
        for (Call call : activated) {
            JsonNode status = call.status();
            if (call.error() != null) status = client.get(worker(call.unit()), "/v1/jobs/" + requestHash(call.unit()));
            verifyStatus(call.unit(), status);
            String state = status.path("state").asText();
            if (status.path("activationRetryable").asBoolean()) throw new IllegalStateException("prepared publication requires identical retry");
            if (!Set.of("completed", "failed", "cancelled").contains(state)) throw new IllegalStateException("worker outcome requires reconciliation");
            completed &= state.equals("completed");
            if (publication != null && state.equals("completed")) publication.verifyCompletion(call.unit(), status);
            statuses.add(status);
        }
        return outcome(plan, completed ? "completed" : "failed", statuses, completed ? null : "one or more workers failed");
    }

    private void installPublication(JsonNode plan, JsonNode unit, boolean reset, String expectedHead) {
        JsonNode grant = publication.grant(plan.path("deployment").asText(), unit.path("node").asText());
        JsonNode current = client.get(worker(unit), "/v1/publication");
        if (current.path("publicationEpoch").equals(grant.path("epoch")) && current.path("deployment").equals(grant.path("deployment"))
                && current.path("operation").equals(grant.path("operation"))) return;
        ObjectNode install = JSON.createObjectNode().put("reset", reset);
        install.set("grant", grant);
        if (expectedHead == null) install.putNull("expectedHead"); else install.put("expectedHead", expectedHead);
        JsonNode installed = client.post(worker(unit), "/v1/publication", stringify(install));
        if (!installed.path("publicationEpoch").equals(grant.path("epoch")) || !installed.path("operation").equals(grant.path("operation"))
                || !installed.path("node").equals(unit.path("node")) || !installed.path("state").asText().equals("installed")) throw new IllegalStateException("worker publication installation requires reconciliation");
    }

    public synchronized ObjectNode replace(JsonNode input) {
        if (publication == null) throw new IllegalArgumentException("publication fencing is not configured");
        fields(input, Set.of("operation", "mode", "expectedEpoch", "plan", "result", "replacement"));
        JsonNode previous = input.path("plan");
        validatePlan(previous);
        if (!previous.path("schema").asText().equals(CONTINUOUS_SCHEMA)) throw new IllegalArgumentException("replacement requires continuous execution");
        verifyCompleted(previous, input.path("result"));
        if (!publication.hasOperation(input.path("operation").asText())) for (JsonNode status : input.path("result").path("assignments")) {
            for (JsonNode unit : previous.path("assignments")) if (unit.path("node").equals(status.path("node"))) publication.verifyCompletion(unit, status);
        }
        String mode = input.path("mode").asText();
        JsonNode target = mode.equals("resume") ? previous : input.path("replacement");
        if (mode.equals("resume") && !input.path("replacement").isNull()) throw new IllegalArgumentException("resume does not accept a replacement plan");
        if (mode.equals("restart")) {
            validatePlan(target);
            if (!target.path("schema").asText().equals(CONTINUOUS_SCHEMA) || !target.path("sequence").asText().equals("1")) throw new IllegalArgumentException("restart requires a new continuous plan from the beginning");
            for (int index = 0; index < target.path("assignments").size(); index++) {
                JsonNode unit = target.path("assignments").get(index);
                JsonNode old = previous.path("assignments").get(index);
                JsonNode request = json(unit.path("requestText").asText());
                JsonNode assignment = json(request.path("assignmentText").asText());
                JsonNode oldAssignment = json(json(old.path("requestText").asText()).path("assignmentText").asText());
                if (!unit.path("node").equals(old.path("node")) || !unit.path("endpoint").equals(old.path("endpoint"))
                        || !unit.path("inputTopic").equals(old.path("inputTopic")) || !assignment.path("inputs").get(0).path("partitionCount").equals(oldAssignment.path("inputs").get(0).path("partitionCount"))) throw new IllegalArgumentException("restart changed the complete topic placement");
                for (JsonNode position : assignment.path("inputs").get(0).path("positions")) if (!position.path("from").asText().equals("0")) throw new IllegalArgumentException("restart must start from offset zero");
            }
            if (!publication.hasOperation(input.path("operation").asText())) {
                metadata.provision(target.path("output").path("topic").asText(), Integer.parseInt(target.path("output").path("partitionCount").asText()), target.path("output").path("create").asBoolean());
                metadata.requireEmpty(target.path("output").path("topic").asText(), Integer.parseInt(target.path("output").path("partitionCount").asText()));
            }
        }
        ObjectNode result = publication.rotate(input.path("operation").asText(), mode, input.path("expectedEpoch").asText(), previous, target);
        for (int index = 0; index < target.path("assignments").size(); index++) installPublication(target, target.path("assignments").get(index), mode.equals("restart"), requestHash(previous.path("assignments").get(index)));
        return result;
    }

    public synchronized ObjectNode retire(JsonNode input) {
        if (publication == null) throw new IllegalArgumentException("publication fencing is not configured");
        fields(input, Set.of("operation", "deployment", "expectedEpoch"));
        return publication.retire(input.path("operation").asText(), input.path("deployment").asText(), input.path("expectedEpoch").asText());
    }

    private static void requirePublicationEpoch(JsonNode status) {
        if (!status.path("publicationEpoch").isTextual() || decimal(status.path("publicationEpoch").asText()) <= 0) throw new IllegalStateException("worker did not retain its publication epoch");
    }

    private ObjectNode abort(JsonNode plan, List<Call> calls, String reason) {
        List<Call> resolved = new ArrayList<>();
        for (Call call : calls) {
            JsonNode status = call.status();
            if (status == null) {
                try { status = client.get(worker(call.unit()), "/v1/jobs/" + requestHash(call.unit())); }
                catch (RustWorkerClient.Rejected error) {
                    if (error.status() == 404 && call.error() instanceof RustWorkerClient.Rejected rejected
                            && Set.of(400, 409, 413, 422).contains(rejected.status())) continue;
                    throw error;
                }
            }
            verifyStatus(call.unit(), status);
            resolved.add(new Call(call.unit(), status, null));
        }
        if (resolved.stream().anyMatch(call -> call.status().path("activationRetryable").asBoolean()
                || call.status().path("state").asText().equals("completed"))) {
            throw new IllegalStateException("publication may have begun; retain the admitted plan for reconciliation");
        }
        ArrayNode statuses = JSON.createArrayNode();
        for (Call call : resolved) {
            JsonNode status = call.status();
            if (status.path("state").asText().equals("prepared")) {
                status = client.post(worker(call.unit()), "/v1/abort/" + requestHash(call.unit()), "");
                verifyStatus(call.unit(), status);
            }
            if (!Set.of("completed", "cancelled", "failed").contains(status.path("state").asText())) {
                throw new IllegalStateException("preparation cleanup requires reconciliation");
            }
            statuses.add(status);
        }
        return outcome(plan, "failed", statuses, reason);
    }

    private record Call(JsonNode unit, JsonNode status, RuntimeException error) { }

    private List<Call> parallel(List<JsonNode> units, Function<JsonNode, JsonNode> operation) {
        ExecutorService pool = Executors.newFixedThreadPool(units.size());
        try {
            List<CompletableFuture<Call>> futures = units.stream().map(unit -> CompletableFuture.supplyAsync(() -> {
                try { return new Call(unit, operation.apply(unit), null); }
                catch (RuntimeException error) { return new Call(unit, null, error); }
            }, pool)).toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally { pool.shutdown(); }
    }

    private ObjectNode outcome(JsonNode plan, String state, ArrayNode statuses, String error) {
        ObjectNode value = JSON.createObjectNode().put("deployment", plan.path("deployment").asText())
                .put("planHash", plan.path("planHash").asText()).put("state", state);
        value.set("assignments", statuses);
        if (publication != null) {
            String epoch = publication.epoch(plan.path("deployment").asText());
            if (state.equals("completed")) {
                epoch = null;
                for (JsonNode status : statuses) {
                    requirePublicationEpoch(status);
                    if (epoch != null && !epoch.equals(status.path("publicationEpoch").asText())) throw new IllegalStateException("worker completion epochs disagree");
                    epoch = status.path("publicationEpoch").asText();
                }
            }
            value.put("publicationEpoch", epoch);
        }
        if (error != null) value.put("error", error);
        return value;
    }

    private void validatePlan(JsonNode plan) {
        boolean continuous = CONTINUOUS_SCHEMA.equals(plan.path("schema").asText());
        fields(plan, continuous
                ? Set.of("schema", "deployment", "sourceDagText", "specHash", "createdAtMicros", "output", "assignments", "planHash", "sequence", "previousPlanHash")
                : Set.of("schema", "deployment", "sourceDagText", "specHash", "createdAtMicros", "output", "assignments", "planHash"));
        if ((!continuous && !SCHEMA.equals(plan.path("schema").asText())) || !hash(plan).equals(plan.path("planHash").asText())
                || !ExecutionAssignments.sha256(plan.path("sourceDagText").asText()).equals(plan.path("specHash").asText())) {
            throw new IllegalArgumentException("physical plan identity mismatch");
        }
        decimal(plan.path("createdAtMicros").asText());
        if (continuous) {
            if (!plan.path("sequence").isTextual() || decimal(plan.path("sequence").asText()) < 1
                    || (plan.path("sequence").asText().equals("1") ? !plan.path("previousPlanHash").isNull()
                    : !plan.path("previousPlanHash").isTextual() || !plan.path("previousPlanHash").asText().matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("invalid continuous sequence or predecessor plan");
            }
        }
        JsonNode dag = source(plan.path("sourceDagText").asText());
        List<String> topics = texts(dag.path("source").path("topics"), "source topics", workers.size());
        JsonNode units = plan.path("assignments");
        if (!units.isArray() || units.size() != topics.size() || units.isEmpty()) throw new IllegalArgumentException("physical topic coverage mismatch");
        JsonNode cap = capabilities();
        List<String> envelopes = new ArrayList<>();
        JsonNode output = plan.path("output");
        fields(output, Set.of("topic", "partitionCount", "create"));
        if (!output.path("create").isBoolean() || !output.path("topic").equals(dag.path("sinks").get(0).path("topic"))) throw new IllegalArgumentException("physical sink mismatch");
        int outputCount = Math.toIntExact(decimal(output.path("partitionCount").asText()));
        if (outputCount < 1 || outputCount > 128) throw new IllegalArgumentException("physical output inventory invalid");
        for (int index = 0; index < units.size(); index++) {
            JsonNode unit = units.get(index);
            fields(unit, Set.of("node", "endpoint", "inputTopic", "requestText"));
            Worker worker = worker(unit);
            if (!worker.equals(workers.get(index)) || !unit.path("inputTopic").asText().equals(topics.get(index))) throw new IllegalArgumentException("physical placement changed");
            JsonNode request = json(unit.path("requestText").asText());
            fields(request, continuous ? Set.of("schema", "assignmentText", "bindings", "sink", "createdAtMicros", "limits", "sequence", "previousRequestHash")
                    : Set.of("schema", "assignmentText", "bindings", "sink", "createdAtMicros", "limits"));
            if (!request.path("schema").asText().equals(continuous ? INCREMENT_SCHEMA : "opentsx.local-bounded-run.v1")
                    || !request.path("createdAtMicros").equals(plan.path("createdAtMicros"))) throw new IllegalArgumentException("local request context mismatch");
            if (continuous && (!cap.path("workers").get(index).path("incrementalRequestSchema").asText().equals(INCREMENT_SCHEMA)
                    || !request.path("sequence").equals(plan.path("sequence"))
                    || (plan.path("sequence").asText().equals("1") ? !request.path("previousRequestHash").isNull()
                    : !request.path("previousRequestHash").isTextual() || !request.path("previousRequestHash").asText().matches("[0-9a-f]{64}")))) {
                throw new IllegalArgumentException("continuous request predecessor mismatch");
            }
            String envelope = request.path("assignmentText").asText();
            ExecutionAssignments.validate(envelope);
            envelopes.add(envelope);
            JsonNode assignment = json(envelope);
            if (!assignment.path("node").asText().equals(worker.node()) || !assignment.path("deployment").equals(plan.path("deployment"))
                    || !assignment.path("sourceDagText").equals(plan.path("sourceDagText")) || !assignment.path("specHash").equals(plan.path("specHash"))
                    || !assignment.path("artifactDigest").equals(cap.path("workers").get(index).path("artifactDigest"))
                    || !assignment.path("site").equals(cap.path("workers").get(index).path("site"))
                    || !assignment.path("runtime").asText().equals("rust") || !assignment.path("mode").asText().equals("bounded")) throw new IllegalArgumentException("assignment context changed");
            ObjectNode expected = dag.deepCopy();
            ((ObjectNode) expected.path("source")).putArray("topics").add(topics.get(index));
            if (!stringify(expected).equals(assignment.path("fragmentText").asText())) throw new IllegalArgumentException("compiled fragment changed from the UI DAG");
            JsonNode input = assignment.path("inputs");
            if (input.size() != 1 || !input.get(0).path("topic").equals(unit.path("inputTopic")) || !input.get(0).path("binding").asText().equals("local")
                    || decimal(input.get(0).path("partitionCount").asText()) > 128) throw new IllegalArgumentException("assignment does not own one complete topic");
            long records = 0;
            for (JsonNode position : input.get(0).path("positions")) {
                if (!continuous && !position.path("from").asText().equals("0")) throw new IllegalArgumentException("bounded rollout begins at offset zero");
                records = Math.addExact(records, decimal(position.path("to").asText()) - decimal(position.path("from").asText()));
            }
            ObjectNode bindings = JSON.createObjectNode();
            ObjectNode binding = bindings.putObject("local").put("bootstrap", bootstrap);
            binding.putArray("inputs").add(topics.get(index));
            binding.putArray("outputs").add(output.path("topic").asText());
            ObjectNode sink = JSON.createObjectNode().put("name", sinkName(dag)).put("binding", "local").put("topic", output.path("topic").asText())
                    .put("partitionCount", output.path("partitionCount").asText());
            ObjectNode limits = JSON.createObjectNode();
            for (String name : LIMITS) limits.put(name, cap.path("workers").get(index).path("limits").path(name).asInt());
            if (!request.path("bindings").equals(bindings) || !request.path("sink").equals(sink) || !request.path("limits").equals(limits)
                    || records > limits.path("records").asLong()
                    || (continuous && records > incrementBudget(cap.path("workers").get(index)))) throw new IllegalArgumentException("physical binding or capacity mismatch");
        }
        ExecutionAssignments.validateSet(envelopes);
    }

    private JsonNode source(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 32_768 || text.isBlank()) throw new IllegalArgumentException("UI DAG must contain at most 32768 UTF-8 bytes");
        JsonNode raw;
        try { raw = YAML.readTree(text); }
        catch (IOException error) { throw new IllegalArgumentException("invalid UI DAG document", error); }
        allowed(raw, Set.of("schema", "dag", "version", "source", "nodes", "sinks", "checkpoint"));
        if (!raw.path("schema").asText().equals("opentsx.dag.v2") || !raw.path("dag").isTextual() || raw.path("dag").asText().isBlank()) throw new IllegalArgumentException("bounded rollout requires a named opentsx.dag.v2");
        allowed(raw.path("source"), Set.of("kind", "topics", "series", "start"));
        if (!raw.path("source").path("kind").asText().equals("kafscale-broker")
                || !raw.path("source").path("start").asText("earliest").equals("earliest")) throw new IllegalArgumentException("bounded rollout requires Kafka source.start earliest");
        List<String> topics = texts(raw.path("source").path("topics"), "source topics", workers.size());
        if (topics.isEmpty() || topics.stream().anyMatch(t -> !ExecutionAssignments.validTopic(t))) throw new IllegalArgumentException("invalid complete source topics");
        if (raw.has("checkpoint")) {
            fields(raw.path("checkpoint"), Set.of("backend"));
            if (!raw.path("checkpoint").path("backend").asText().equals("file")) throw new IllegalArgumentException("unsupported checkpoint backend");
        }
        List<String> selected = raw.path("source").has("series") ? texts(raw.path("source").path("series"), "source series", 64) : List.of();
        if (selected.stream().anyMatch(id -> !id.matches("[0-9a-f]{16}"))) throw new IllegalArgumentException("source series IDs must be canonical hexadecimal");
        if (!raw.path("sinks").isArray() || raw.path("sinks").size() != 1) throw new IllegalArgumentException("select exactly one episode sink");
        JsonNode sink = raw.path("sinks").get(0);
        allowed(sink, Set.of("from", "topic", "schema", "signal", "origin", "madeOf", "name"));
        if (!sink.path("schema").asText().equals("opentsx.episode.v2") || !ExecutionAssignments.validTopic(sink.path("topic").asText())
                || topics.contains(sink.path("topic").asText())) throw new IllegalArgumentException("output must be a distinct Episode topic");
        if (sink.has("signal")) {
            allowed(sink.path("signal"), Set.of("system", "id", "grade", "metric"));
            if (!sink.path("signal").path("grade").asText("clean").equals("clean")
                    || sink.path("signal").path("system").asText().isBlank() || sink.path("signal").path("id").asText().isBlank()) throw new IllegalArgumentException("clean sink requires system, id and clean grade");
        }
        if (!raw.path("nodes").isArray() || raw.path("nodes").isEmpty()) throw new IllegalArgumentException("DAG nodes are required");
        for (JsonNode node : raw.path("nodes")) {
            allowed(node, Set.of("id", "op", "in", "params"));
            if (!OPERATORS.contains(node.path("op").asText())) throw new IllegalArgumentException("operator unavailable on bounded Rust: " + node.path("id").asText() + " (" + node.path("op").asText() + ")");
        }
        JsonNode dag;
        try { dag = DagToWayang.parameters(raw, catalog); }
        catch (IOException error) { throw new IllegalStateException("operator catalog cannot be read", error); }
        Map<String, JsonNode> nodes = DagPlan.validateStructure(dag, new TopicLayout(topics.get(0).split("\\.")[1]));
        Set<String> visited = new HashSet<>();
        String at = sink.path("from").asText();
        boolean last = true;
        while (true) {
            if (!visited.add(at)) throw new IllegalArgumentException("DAG contains a cycle");
            JsonNode node = nodes.get(at);
            if (node == null) throw new IllegalArgumentException("sink references a missing node");
            String op = node.path("op").asText();
            if (op.equals("decode.avro-soe")) {
                if (node.has("params")) fields(node.path("params"), Set.of());
                break;
            }
            if (selected.isEmpty() || !sink.has("signal")) throw new IllegalArgumentException("cleaning requires selected source series and a named signal sink");
            if (node.path("in").size() != 1) throw new IllegalArgumentException("bounded Rust requires one linear cleaning chain");
            if (op.equals("resample") && !last) throw new IllegalArgumentException("resample must be the trailing operator");
            Set<String> params = switch (op) {
                case "dq.checks" -> Set.of("checks", "min", "max", "mode");
                case "convert.unit" -> Set.of("factor", "offset", "unit");
                case "resample" -> Set.of("every", "agg");
                default -> throw new IllegalArgumentException("unsupported operator");
            };
            allowed(node.path("params"), params);
            at = node.path("in").get(0).asText();
            last = false;
        }
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("bounded DAG contains unused nodes");
        return dag;
    }

    private void validateCapability(Worker worker, JsonNode value) {
        if (!value.path("schema").asText().equals("opentsx.local-worker.v1") || !value.path("node").asText().equals(worker.node())
                || !value.path("site").asText().equals("compose") || !value.path("artifactDigest").asText().matches("sha256:[0-9a-f]{64}")
                || !value.path("version").asText().equals("0.1.0") || !value.path("catalog").asText().equals("tsx-catalog/1")
                || !value.path("requestSchema").asText().equals("opentsx.local-bounded-run.v1")
                || !value.path("stateAbi").asText().equals("opentsx.rust.clean-state.v1") || !value.path("fingerprint").asText().equals("8985f02b48c77195")
                || !new HashSet<>(texts(value.path("operators"), "worker operators", 4)).equals(new HashSet<>(OPERATORS))) throw new IllegalArgumentException("worker capability identity mismatch: " + worker.node());
        for (String name : LIMITS) {
            JsonNode limit = value.path("limits").path(name);
            if (!limit.isIntegralNumber() || !limit.canConvertToLong() || limit.asLong() <= 0 || limit.asLong() > CEILINGS.get(name)) throw new IllegalArgumentException("invalid worker capacity " + name);
        }
        if (!value.path("limits").path("partitions").isIntegralNumber() || !value.path("limits").path("partitions").canConvertToInt()
                || value.path("limits").path("partitions").asInt() < 1
                || value.path("limits").path("partitions").asInt() > 128) throw new IllegalArgumentException("invalid worker partition capacity");
    }

    private void verifyStatus(JsonNode unit, JsonNode status) {
        JsonNode request = json(unit.path("requestText").asText());
        JsonNode assignment = json(request.path("assignmentText").asText());
        if (!status.path("schema").asText().equals("opentsx.local-worker-status.v1") || !status.path("requestHash").asText().equals(requestHash(unit))
                || !status.path("node").equals(unit.path("node")) || !status.path("site").equals(assignment.path("site"))
                || !status.path("artifactDigest").equals(assignment.path("artifactDigest")) || !status.path("inputTopic").equals(unit.path("inputTopic"))
                || !status.path("activationRetryable").isBoolean()) throw new IllegalStateException("worker status identity mismatch");
        if (request.path("schema").asText().equals(INCREMENT_SCHEMA)
                && (!status.path("sequence").equals(request.path("sequence"))
                || !status.path("previousRequestHash").equals(request.path("previousRequestHash")))) throw new IllegalStateException("worker increment identity mismatch");
        series(status, "inputSeries");
        series(status, "outputSeries");
        if (status.path("state").asText().equals("completed")) {
            JsonNode receipt = status.path("receipt");
            for (String field : List.of("artifactDigest", "deployment", "generation", "assignment", "node")) {
                if (!receipt.path(field).equals(assignment.path(field))) throw new IllegalStateException("worker receipt context mismatch: " + field);
            }
            ArrayNode positions = JSON.createArrayNode();
            long count = 0;
            for (JsonNode cursor : assignment.path("inputs").get(0).path("positions")) {
                positions.addObject().put("partition", cursor.path("partition").asText()).put("next", cursor.path("to").asText());
                count = Math.addExact(count, decimal(cursor.path("to").asText()) - decimal(cursor.path("from").asText()));
            }
            long outputCeiling = json(assignment.path("fragmentText").asText()).path("nodes").size() == 1
                    ? count : request.path("limits").path("samples").asLong();
            if (!receipt.path("schema").asText().equals("opentsx.local-receipt.v1") || !receipt.path("requestHash").asText().equals(requestHash(unit))
                    || !receipt.path("inputTopic").equals(unit.path("inputTopic")) || !receipt.path("outputTopic").equals(request.path("sink").path("topic"))
                    || !receipt.path("positions").equals(positions) || !receipt.path("inputRecords").isIntegralNumber()
                    || !receipt.path("inputRecords").canConvertToLong() || receipt.path("inputRecords").asLong() != count
                    || !receipt.path("outputRecords").isIntegralNumber() || !receipt.path("outputRecords").canConvertToLong()
                    || receipt.path("outputRecords").asLong() < 0 || receipt.path("outputRecords").asLong() > outputCeiling
                    || !receipt.path("stateDigest").asText().matches("[0-9a-f]{64}") || !receipt.path("outputDigest").asText().matches("[0-9a-f]{64}")) throw new IllegalStateException("worker completion receipt mismatch");
        }
    }

    private static List<String> series(JsonNode status, String field) {
        List<String> ids = texts(status.path(field), field, 64);
        if (ids.stream().anyMatch(id -> !id.matches("[0-9a-f]{16}"))) throw new IllegalStateException("invalid worker series manifest");
        return ids;
    }

    private Worker worker(JsonNode unit) {
        return workers.stream().filter(worker -> worker.node().equals(unit.path("node").asText())
                        && worker.endpoint().equals(unit.path("endpoint").asText())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("assignment targets an unconfigured worker"));
    }

    public static String requestHash(JsonNode unit) { return ExecutionAssignments.sha256(unit.path("requestText").asText()); }

    public static JsonNode json(String text) {
        try {
            JsonNode value = JSON.readTree(text);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("expected a JSON object");
            return value;
        }
        catch (IOException error) { throw new IllegalArgumentException("invalid JSON document", error); }
    }

    public static String stringify(JsonNode value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException error) { throw new IllegalStateException("cannot encode rollout document", error); }
    }

    public static String hash(JsonNode plan) {
        ObjectNode payload = plan.deepCopy();
        payload.remove("planHash");
        return ExecutionAssignments.sha256(stringify(canonical(payload)));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue()));
            ObjectNode result = JSON.createObjectNode();
            fields.forEach((name, entry) -> result.set(name, canonical(entry)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(entry -> result.add(canonical(entry)));
            return result;
        }
        return value;
    }

    private static String sinkName(JsonNode dag) {
        JsonNode sink = dag.path("sinks").get(0);
        if (sink.has("name")) return sink.path("name").asText();
        if (dag.path("nodes").size() == 1) return sink.path("from").asText();
        return sink.path("signal").path("id").asText().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static long decimal(String text) {
        try {
            long value = Long.parseLong(text);
            if (!Long.toString(value).equals(text)) throw new IllegalArgumentException("noncanonical decimal string");
            return value;
        } catch (NumberFormatException error) { throw new IllegalArgumentException("invalid decimal string", error); }
    }

    private static List<String> texts(JsonNode value, String name, int limit) {
        if (!value.isArray() || value.size() > limit) throw new IllegalArgumentException(name + " must be a bounded array");
        List<String> result = new ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.asText().isBlank() || result.contains(entry.asText())) throw new IllegalArgumentException(name + " must contain unique nonempty strings");
            result.add(entry.asText());
        }
        return result;
    }

    private static void allowed(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("expected an object");
        node.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw new IllegalArgumentException("unsupported field " + name); });
    }

    private static void fields(JsonNode node, Set<String> fields) {
        allowed(node, fields);
        if (node.size() != fields.size()) throw new IllegalArgumentException("missing required rollout fields");
    }

    public static Metadata kafka(String bootstrap) {
        return kafka(bootstrap, bootstrap);
    }

    public static Metadata kafka(String readBootstrap, String writeBootstrap) {
        return new Metadata() {
            @Override
            public Inventory inspect(List<String> topics, String output) {
                try (Admin admin = KafScaleClients.admin(writeBootstrap); KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(readBootstrap)) {
                    Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
                    if (!existing.containsAll(topics)) throw new IllegalArgumentException("one or more source topics do not exist");
                    Map<String, List<Position>> inputs = new LinkedHashMap<>();
                    for (String topic : topics) {
                        List<TopicPartition> partitions = partitions(consumer, topic);
                        Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions, Duration.ofSeconds(30));
                        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, Duration.ofSeconds(30));
                        inputs.put(topic, partitions.stream().map(partition -> new Position(partition.partition(), starts.get(partition), ends.get(partition))).toList());
                    }
                    return new Inventory(inputs, existing.contains(output) ? partitions(consumer, output).size() : 1, !existing.contains(output));
                } catch (IllegalArgumentException error) { throw error; }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("broker inventory interrupted", error); }
                catch (Exception error) { throw new IllegalStateException("broker inventory cannot be read", error); }
            }

            @Override
            public void provision(String output, int count, boolean mayCreate) {
                try (Admin admin = KafScaleClients.admin(writeBootstrap); KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(readBootstrap)) {
                    if (!admin.listTopics().names().get(30, TimeUnit.SECONDS).contains(output)) {
                        if (!mayCreate) throw new IllegalStateException("admitted output topic disappeared");
                        try { admin.createTopics(List.of(new NewTopic(output, count, (short) 1))).all().get(30, TimeUnit.SECONDS); }
                        catch (java.util.concurrent.ExecutionException error) { if (!(error.getCause() instanceof TopicExistsException)) throw error; }
                    }
                    if (partitions(consumer, output).size() != count) throw new IllegalStateException("output topic inventory changed");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("output provisioning interrupted", error); }
                catch (Exception error) { throw new IllegalStateException("output topic cannot be provisioned", error); }
            }

            @Override
            public void requireEmpty(String output, int count) {
                try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(readBootstrap)) {
                    List<TopicPartition> partitions = partitions(consumer, output);
                    if (partitions.size() != count || consumer.endOffsets(partitions, Duration.ofSeconds(30)).values().stream().anyMatch(offset -> offset != 0)) throw new IllegalArgumentException("fenced deployment requires an empty output topic");
                }
            }
        };
    }

    private static List<TopicPartition> partitions(KafkaConsumer<byte[], byte[]> consumer, String topic) {
        var info = consumer.partitionsFor(topic, Duration.ofSeconds(30));
        if (info == null || info.isEmpty() || info.size() > 128) throw new IllegalArgumentException("whole topic partition capacity exceeded");
        List<TopicPartition> result = info.stream().map(partition -> new TopicPartition(topic, partition.partition()))
                .sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
        for (int index = 0; index < result.size(); index++) if (result.get(index).partition() != index) throw new IllegalArgumentException("incomplete broker partition inventory");
        return result;
    }
}
