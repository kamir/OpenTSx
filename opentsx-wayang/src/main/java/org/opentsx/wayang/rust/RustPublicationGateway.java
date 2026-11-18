package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public final class RustPublicationGateway implements AutoCloseable {
    public static final int MAX_BYTES = 85_000_000;
    private static final int LEDGER_BYTES = 4_194_304;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final Writer writer;
    private ObjectNode ledger;
    private boolean storageFailed;

    @FunctionalInterface
    interface Writer {
        void publish(String topic, int partitions, List<Record> records) throws Exception;
    }

    record Record(String key, long timestamp, byte[] payload) { }

    public static final class Refused extends RuntimeException {
        public Refused(String message) { super(message); }
    }

    public RustPublicationGateway(Path directory, String bootstrap) throws IOException {
        this(directory, kafka(bootstrap));
    }

    RustPublicationGateway(Path directory, Writer writer) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(this.directory)) throw new IOException("publication directory must not be a symbolic link");
        Files.createDirectories(this.directory);
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        Path lockPath = this.directory.resolve("lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("publication lock must not be a symbolic link");
        lockChannel = FileChannel.open(lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException error) { lockChannel.close(); throw error; }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("another publication gateway owns this directory");
        }
        processLock = acquired;
        this.writer = writer;
        try {
            Path file = this.directory.resolve("ledger.json");
            Path initialized = this.directory.resolve("initialized");
            if (Files.isSymbolicLink(file)) throw new IOException("publication ledger must not be a symbolic link");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(initialized, LinkOption.NOFOLLOW_LINKS) || Files.size(initialized) > 64
                        || !Files.readString(initialized, StandardCharsets.US_ASCII).equals("opentsx.publication-ledger.v1\n")) throw new IOException("publication initialization marker is missing or invalid");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LEDGER_BYTES) throw new IOException("publication ledger exceeds its storage contract");
                JsonNode envelope = RustRollout.json(Files.readString(file, StandardCharsets.UTF_8));
                exact(envelope, Set.of("schema", "checksum", "payload"));
                if (!envelope.path("schema").asText().equals("opentsx.publication-ledger.v1")
                        || !envelope.path("payload").isObject()
                        || !envelope.path("checksum").asText().equals(ExecutionAssignments.sha256(RustRollout.stringify(envelope.path("payload"))))) {
                    throw new IOException("publication ledger checksum or schema mismatch");
                }
                ledger = (ObjectNode) envelope.path("payload");
                exact(ledger, Set.of("topics", "groups", "requests", "operations"));
                for (String name : List.of("topics", "groups", "requests", "operations")) if (!ledger.path(name).isObject()) throw new IOException("invalid publication ledger inventory");
                validateLedger();
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } else {
                if (Files.exists(this.directory.resolve("initialized"))) throw new IOException("publication ledger is missing from an initialized directory");
                ledger = JSON.objectNode();
                for (String name : List.of("topics", "groups", "requests", "operations")) ledger.putObject(name);
                Files.createFile(initialized, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try (FileChannel marker = FileChannel.open(initialized, StandardOpenOption.WRITE)) {
                    marker.write(ByteBuffer.wrap("opentsx.publication-ledger.v1\n".getBytes(StandardCharsets.US_ASCII)));
                    marker.force(true);
                }
                syncDirectory();
                save();
            }
        } catch (RuntimeException | IOException error) {
            processLock.release();
            lockChannel.close();
            throw error;
        }
    }

    public synchronized String admit(JsonNode plan) {
        healthy();
        String deployment = identifier(plan.path("deployment"));
        JsonNode existing = object("groups").get(deployment);
        if (existing != null) {
            if (existing.path("retired").asBoolean() || !sameBinding(existing, plan)) throw new Refused("publication deployment is retired or its whole-topic binding changed");
            return existing.path("epoch").asText();
        }
        if (object("groups").size() >= 64) throw new Refused("publication deployment capacity reached");
        if (object("operations").size() + activeGroups() >= 64) throw new Refused("publication retirement capacity must remain available");
        long epoch = 1;
        for (String topic : topicSet(plan)) {
            for (JsonNode group : object("groups")) if (!group.path("retired").asBoolean() && contains(group.path("topics"), topic)) throw new Refused("a whole topic already has an active publication owner");
            epoch = Math.max(epoch, increment(object("topics").path(topic).asText("0")));
        }
        createGroup(plan, epoch, "admit-" + ExecutionAssignments.sha256(deployment).substring(0, 48));
        saveUnchecked();
        return Long.toString(epoch);
    }

    public synchronized JsonNode grant(String deployment, String node) {
        healthy();
        JsonNode group = active(deployment);
        JsonNode grant = group.path("grants").get(node);
        if (grant == null) throw new Refused("worker has no publication grant");
        return grant.deepCopy();
    }

    public synchronized String epoch(String deployment) {
        healthy();
        JsonNode group = object("groups").get(deployment);
        if (group == null) return "0";
        return group.path("epoch").asText();
    }

    public synchronized boolean hasOperation(String operation) {
        healthy();
        return object("operations").has(operation);
    }

    public synchronized void register(JsonNode plan, JsonNode unit, JsonNode status) {
        healthy();
        JsonNode group = active(identifier(plan.path("deployment")));
        if (!sameBinding(group, plan)) throw new Refused("publication plan changed its whole-topic binding");
        String hash = RustRollout.requestHash(unit);
        String state = status.path("state").asText();
        if (state.equals("completed")) return;
        if (!status.path("publicationEpoch").asText().equals(group.path("epoch").asText())) throw new Refused("prepared publication belongs to another epoch");
        String digest = hash(status.path("preparedOutputDigest"));
        long count = decimal(status.path("preparedOutputRecords").asText());
        JsonNode request = RustRollout.json(unit.path("requestText").asText());
        if (count > request.path("limits").path("samples").asLong()) throw new Refused("prepared publication record capacity exceeded");
        ObjectNode registered = JSON.objectNode().put("deployment", plan.path("deployment").asText()).put("epoch", group.path("epoch").asText())
                .put("node", unit.path("node").asText()).put("inputTopic", unit.path("inputTopic").asText())
                .put("outputTopic", request.path("sink").path("topic").asText()).put("partitionCount", request.path("sink").path("partitionCount").asText())
                .put("outputDigest", digest).put("outputRecords", Long.toString(count)).put("requestText", unit.path("requestText").asText()).put("state", "ready");
        JsonNode old = object("requests").get(hash);
        if (old != null) {
            ObjectNode comparable = old.deepCopy();
            comparable.put("state", "ready");
            if (!comparable.equals(registered)) throw new Refused("registered publication identity conflicts");
            if (old.path("state").asText().equals("publishing") || old.path("state").asText().equals("uncertain")) throw new Refused("publication has an unresolved broker outcome");
            return;
        }
        JsonNode head = group.path("heads").get(unit.path("node").asText());
        boolean continuous = request.path("schema").asText().equals(RustRollout.INCREMENT_SCHEMA);
        if (head == null) {
            if (continuous && (!request.path("sequence").asText().equals("1") || !request.path("previousRequestHash").isNull())) throw new Refused("publication increment must begin at sequence one");
        } else if (!continuous || !head.path("acknowledged").asBoolean()
                || !request.path("sequence").asText().equals(Long.toString(increment(head.path("sequence").asText())))
                || !request.path("previousRequestHash").equals(head.path("requestHash"))) throw new Refused("publication increment is stale or its predecessor is unconfirmed");
        Iterator<Map.Entry<String, JsonNode>> entries = object("requests").fields();
        List<String> completed = new ArrayList<>();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getValue().path("node").equals(registered.path("node")) && entry.getValue().path("deployment").equals(registered.path("deployment"))
                    && entry.getValue().path("state").asText().equals("acknowledged")) completed.add(entry.getKey());
        }
        for (int index = 0; index < completed.size() - 1; index++) object("requests").remove(completed.get(index));
        if (object("requests").size() >= 256) throw new Refused("publication request capacity reached");
        object("requests").set(hash, registered);
        ObjectNode nextHead = ((ObjectNode) group.path("heads")).putObject(unit.path("node").asText()).put("requestHash", hash).put("acknowledged", false);
        if (continuous) nextHead.set("sequence", request.path("sequence")); else nextHead.putNull("sequence");
        saveUnchecked();
    }

    public synchronized void verifyCompletion(JsonNode unit, JsonNode status) {
        healthy();
        JsonNode saved = object("requests").get(RustRollout.requestHash(unit));
        JsonNode receipt = status.path("receipt");
        if (saved == null || !saved.path("state").asText().equals("acknowledged") || !saved.path("epoch").equals(status.path("publicationEpoch"))
                || !saved.path("outputDigest").equals(receipt.path("outputDigest"))
                || !saved.path("outputRecords").asText().equals(receipt.path("outputRecords").asText())) throw new Refused("worker completion lacks its acknowledged publication");
    }

    public synchronized ObjectNode publish(String token, JsonNode input) {
        healthy();
        exact(input, Set.of("epoch", "requestText", "records"));
        JsonNode granted = authorize(token);
        if (!input.path("epoch").isTextual() || !input.path("epoch").asText().equals(granted.path("epoch").asText()) || !input.path("requestText").isTextual()) throw new Refused("publication authority was revoked");
        String requestText = input.path("requestText").asText();
        if (requestText.getBytes(StandardCharsets.UTF_8).length > 65_536) throw new Refused("publication request exceeds its byte limit");
        String requestHash = ExecutionAssignments.sha256(requestText);
        JsonNode saved = object("requests").get(requestHash);
        if (saved == null || !saved.path("deployment").equals(granted.path("deployment")) || !saved.path("epoch").equals(granted.path("epoch"))
                || !saved.path("node").equals(granted.path("node")) || !saved.path("inputTopic").equals(granted.path("inputTopic"))
                || !saved.path("outputTopic").equals(granted.path("outputTopic")) || !saved.path("requestText").asText().equals(requestText)) throw new Refused("publication request is not registered for this authority");
        List<Record> records = records(input.path("records"), RustRollout.json(requestText));
        if (!saved.path("outputRecords").asText().equals(Integer.toString(records.size())) || !saved.path("outputDigest").asText().equals(digest(records))) throw new Refused("publication bytes differ from the private preparation");
        String state = saved.path("state").asText();
        if (state.equals("acknowledged")) return receipt(requestHash, saved);
        if (!state.equals("ready")) throw new Refused("publication has an unresolved broker outcome");
        ((ObjectNode) saved).put("state", "publishing");
        saveUnchecked();
        try {
            writer.publish(saved.path("outputTopic").asText(), Math.toIntExact(decimal(saved.path("partitionCount").asText())), records);
        } catch (Exception error) {
            ((ObjectNode) saved).put("state", "uncertain");
            saveUnchecked();
            throw new Refused("publication has an unresolved broker outcome");
        }
        ((ObjectNode) saved).put("state", "acknowledged");
        ((ObjectNode) active(saved.path("deployment").asText()).path("heads").path(saved.path("node").asText())).put("acknowledged", true);
        saveUnchecked();
        return receipt(requestHash, saved);
    }

    public synchronized boolean accepts(String token) {
        try { authorize(token); return true; }
        catch (Refused error) { return false; }
    }

    public synchronized ObjectNode rotate(String operation, String mode, String expectedEpoch, JsonNode oldPlan, JsonNode replacement) {
        healthy();
        identifier(JSON.textNode(operation));
        if (!Set.of("resume", "restart").contains(mode)) throw new Refused("unsupported publication replacement mode");
        String oldDeployment = identifier(oldPlan.path("deployment"));
        JsonNode target = mode.equals("resume") ? oldPlan : replacement;
        if (target == null || !target.isObject()) throw new Refused("replacement plan is required");
        String targetDeployment = identifier(target.path("deployment"));
        ObjectNode identity = JSON.objectNode().put("mode", mode).put("oldDeployment", oldDeployment).put("deployment", targetDeployment)
                .put("expectedEpoch", expectedEpoch).put("oldPlanHash", oldPlan.path("planHash").asText()).put("planHash", target.path("planHash").asText());
        JsonNode repeated = object("operations").get(operation);
        if (repeated != null) {
            if (!repeated.path("identity").equals(identity)) throw new Refused("publication operation identity conflicts");
            return (ObjectNode) repeated.path("result").deepCopy();
        }
        JsonNode old = active(oldDeployment);
        if (!old.path("epoch").asText().equals(expectedEpoch) || !sameBinding(old, oldPlan)) throw new Refused("publication epoch or whole-topic binding changed");
        for (JsonNode unit : oldPlan.path("assignments")) {
            JsonNode head = old.path("heads").path(unit.path("node").asText());
            if (!head.path("acknowledged").asBoolean() || !head.path("requestHash").asText().equals(RustRollout.requestHash(unit))) throw new Refused("replacement requires the current acknowledged whole-topic heads");
        }
        if (mode.equals("restart") && (oldDeployment.equals(targetDeployment) || oldPlan.path("specHash").equals(target.path("specHash"))
                || oldPlan.path("output").path("topic").equals(target.path("output").path("topic")) || !inputs(oldPlan).equals(inputs(target)))) throw new Refused("restart requires a changed DAG, the same whole topics and a new output");
        barrier(oldDeployment);
        if (object("operations").size() + activeGroups() >= 64 || (!oldDeployment.equals(targetDeployment) && (object("groups").has(targetDeployment)
                || object("groups").size() >= 64))) throw new Refused("publication replacement identity or capacity conflicts");
        long next = increment(expectedEpoch);
        for (String topic : topicSet(target)) {
            next = Math.max(next, increment(object("topics").path(topic).asText("0")));
            for (JsonNode group : object("groups")) if (!group.path("deployment").asText().equals(oldDeployment)
                    && !group.path("retired").asBoolean() && contains(group.path("topics"), topic)) throw new Refused("replacement whole topic has another publication owner");
        }
        JsonNode previousHeads = old.path("heads").deepCopy();
        ((ObjectNode) old).put("retired", true);
        discardUnpublished(oldDeployment);
        for (String topic : topicSet(oldPlan)) object("topics").put(topic, Long.toString(next));
        createGroup(target, next, operation);
        if (mode.equals("resume")) ((ObjectNode) active(targetDeployment)).set("heads", previousHeads);
        ObjectNode result = JSON.objectNode().put("operation", operation).put("publicationEpoch", Long.toString(next)).put("state", "active").put("deployment", targetDeployment);
        ObjectNode recorded = object("operations").putObject(operation);
        recorded.set("identity", identity);
        recorded.set("result", result);
        saveUnchecked();
        return result.deepCopy();
    }

    public synchronized ObjectNode retire(String operation, String deployment, String expectedEpoch) {
        healthy();
        identifier(JSON.textNode(operation)); identifier(JSON.textNode(deployment));
        ObjectNode identity = JSON.objectNode().put("mode", "retire").put("deployment", deployment).put("expectedEpoch", expectedEpoch);
        JsonNode repeated = object("operations").get(operation);
        if (repeated != null) {
            if (!repeated.path("identity").equals(identity)) throw new Refused("publication operation identity conflicts");
            return (ObjectNode) repeated.path("result").deepCopy();
        }
        if (object("operations").size() >= 64) throw new Refused("publication operation capacity reached");
        JsonNode existing = object("groups").get(deployment);
        if (existing != null && (existing.path("retired").asBoolean() || !existing.path("epoch").asText().equals(expectedEpoch))) throw new Refused("publication epoch changed before retirement");
        if (existing == null && !expectedEpoch.equals("0")) throw new Refused("publication deployment is unknown");
        if (existing == null && object("operations").size() + activeGroups() >= 64) throw new Refused("publication retirement capacity must remain available");
        barrier(deployment);
        discardUnpublished(deployment);
        long next = increment(expectedEpoch);
        ObjectNode group;
        if (existing == null) {
            if (object("groups").size() >= 64) throw new Refused("publication deployment capacity reached");
            group = object("groups").putObject(deployment);
            group.put("deployment", deployment); group.putArray("topics"); group.putObject("grants");
        } else group = (ObjectNode) existing;
        group.put("retired", true).put("epoch", Long.toString(next));
        group.putObject("grants");
        for (JsonNode topic : group.path("topics")) object("topics").put(topic.asText(), Long.toString(next));
        ObjectNode result = JSON.objectNode().put("operation", operation).put("publicationEpoch", Long.toString(next)).put("state", "retired").put("deployment", deployment);
        ObjectNode recorded = object("operations").putObject(operation);
        recorded.set("identity", identity); recorded.set("result", result);
        saveUnchecked();
        return result.deepCopy();
    }

    private void barrier(String deployment) {
        for (JsonNode request : object("requests")) if (request.path("deployment").asText().equals(deployment)
                && Set.of("publishing", "uncertain").contains(request.path("state").asText())) throw new Refused("publication uncertainty blocks deployment replacement");
    }

    private int activeGroups() {
        int count = 0;
        for (JsonNode group : object("groups")) if (!group.path("retired").asBoolean()) count++;
        return count;
    }

    private void discardUnpublished(String deployment) {
        List<String> discard = new ArrayList<>();
        object("requests").fields().forEachRemaining(entry -> {
            if (entry.getValue().path("deployment").asText().equals(deployment)
                    && !entry.getValue().path("state").asText().equals("acknowledged")) discard.add(entry.getKey());
        });
        discard.forEach(object("requests")::remove);
    }

    private JsonNode authorize(String token) {
        healthy();
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new Refused("publication authority was revoked");
        byte[] supplied = token.getBytes(StandardCharsets.US_ASCII);
        for (JsonNode group : object("groups")) if (!group.path("retired").asBoolean()) {
            for (JsonNode grant : group.path("grants")) if (MessageDigest.isEqual(supplied, grant.path("token").asText().getBytes(StandardCharsets.US_ASCII))) return grant;
        }
        throw new Refused("publication authority was revoked");
    }

    private void createGroup(JsonNode plan, long epoch, String operation) {
        String deployment = identifier(plan.path("deployment"));
        ObjectNode group = JSON.objectNode().put("deployment", deployment).put("epoch", Long.toString(epoch)).put("retired", false)
                .put("bindingHash", bindingHash(plan));
        group.set("topics", JSON.arrayNode().addAll(topicSet(plan).stream().map(JSON::textNode).toList()));
        group.putObject("heads");
        ObjectNode grants = group.putObject("grants");
        for (JsonNode unit : plan.path("assignments")) {
            byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
            grants.putObject(unit.path("node").asText()).put("schema", "opentsx.publication-grant.v1").put("operation", operation)
                    .put("deployment", deployment).put("epoch", Long.toString(epoch)).put("node", unit.path("node").asText())
                    .put("inputTopic", unit.path("inputTopic").asText()).put("outputTopic", plan.path("output").path("topic").asText())
                    .put("token", HexFormat.of().formatHex(bytes));
        }
        object("groups").set(deployment, group);
        for (String topic : topicSet(plan)) object("topics").put(topic, Long.toString(epoch));
    }

    private JsonNode active(String deployment) {
        JsonNode group = object("groups").get(deployment);
        if (group == null || group.path("retired").asBoolean()) throw new Refused("publication deployment is unknown or retired");
        return group;
    }

    private static boolean sameBinding(JsonNode group, JsonNode plan) {
        if (!group.path("bindingHash").asText().equals(bindingHash(plan))) return false;
        Set<String> topics = new TreeSet<>(); group.path("topics").forEach(topic -> topics.add(topic.asText()));
        if (!topics.equals(topicSet(plan)) || group.path("grants").size() != plan.path("assignments").size()) return false;
        for (JsonNode unit : plan.path("assignments")) {
            JsonNode grant = group.path("grants").path(unit.path("node").asText());
            if (!grant.path("inputTopic").equals(unit.path("inputTopic")) || !grant.path("outputTopic").equals(plan.path("output").path("topic"))) return false;
        }
        return true;
    }

    private static String bindingHash(JsonNode plan) {
        ObjectNode binding = JSON.objectNode();
        for (String name : List.of("schema", "deployment", "specHash", "createdAtMicros")) binding.set(name, plan.path(name));
        binding.putObject("output").setAll(Map.of("topic", plan.path("output").path("topic"), "partitionCount", plan.path("output").path("partitionCount")));
        ArrayNode units = binding.putArray("assignments");
        for (JsonNode unit : plan.path("assignments")) {
            ObjectNode entry = units.addObject();
            for (String name : List.of("node", "endpoint", "inputTopic")) entry.set(name, unit.path(name));
            JsonNode request = RustRollout.json(unit.path("requestText").asText());
            for (String name : List.of("bindings", "sink", "limits")) entry.set(name, request.path(name));
            ObjectNode assignment = (ObjectNode) RustRollout.json(request.path("assignmentText").asText());
            for (JsonNode input : assignment.path("inputs")) ((ObjectNode) input).remove("positions");
            entry.set("assignment", assignment);
        }
        return RustRollout.hash(binding);
    }

    private static Set<String> inputs(JsonNode plan) {
        Set<String> result = new TreeSet<>();
        for (JsonNode unit : plan.path("assignments")) if (!result.add(unit.path("inputTopic").asText())) throw new Refused("duplicate whole topic in publication plan");
        if (result.isEmpty() || result.size() > 3) throw new Refused("invalid publication topic inventory");
        return result;
    }

    private static Set<String> topicSet(JsonNode plan) {
        Set<String> result = inputs(plan);
        String output = plan.path("output").path("topic").asText();
        if (!ExecutionAssignments.validTopic(output) || !result.add(output)) throw new Refused("invalid publication output topic");
        return result;
    }

    private static boolean contains(JsonNode values, String text) {
        for (JsonNode value : values) if (value.asText().equals(text)) return true;
        return false;
    }

    private ObjectNode object(String name) { return (ObjectNode) ledger.path(name); }

    private static ObjectNode receipt(String requestHash, JsonNode saved) {
        return JSON.objectNode().put("schema", "opentsx.publication-receipt.v1").put("publicationEpoch", saved.path("epoch").asText())
                .put("requestHash", requestHash).put("outputDigest", saved.path("outputDigest").asText()).put("outputRecords", saved.path("outputRecords").asText());
    }

    private static List<Record> records(JsonNode values, JsonNode request) {
        if (!values.isArray() || values.size() > request.path("limits").path("samples").asLong()) throw new Refused("publication record count exceeds its admitted limit");
        List<Record> result = new ArrayList<>();
        long bytes = 0;
        for (JsonNode value : values) {
            exact(value, Set.of("key", "timestamp", "payload"));
            String key = value.path("key").asText();
            if (!value.path("key").isTextual() || !key.matches("[0-9a-f]{16}") || !value.path("timestamp").isIntegralNumber() || !value.path("timestamp").canConvertToLong()
                    || value.path("timestamp").asLong() <= 0 || !value.path("payload").isArray()
                    || value.path("payload").size() > request.path("limits").path("payloadBytes").asLong()) throw new Refused("invalid publication record");
            byte[] payload = new byte[value.path("payload").size()];
            for (int index = 0; index < payload.length; index++) {
                JsonNode octet = value.path("payload").get(index);
                if (!octet.isIntegralNumber() || !octet.canConvertToInt() || octet.asInt() < 0 || octet.asInt() > 255) throw new Refused("invalid publication payload byte");
                payload[index] = (byte) octet.asInt();
            }
            bytes = Math.addExact(bytes, payload.length);
            if (bytes > request.path("limits").path("outputBytes").asLong()) throw new Refused("publication bytes exceed their admitted limit");
            result.add(new Record(key, value.path("timestamp").asLong(), payload));
        }
        return result;
    }

    static String digest(List<Record> records) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (Record record : records) {
                    byte[] key = record.key().getBytes(StandardCharsets.UTF_8);
                    output.writeLong(key.length); output.write(key); output.writeLong(record.timestamp());
                    output.writeLong(record.payload().length); output.write(record.payload());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception error) { throw new IllegalStateException("cannot digest publication bytes", error); }
    }

    private void saveUnchecked() {
        try { save(); }
        catch (IOException error) {
            storageFailed = true;
            throw new IllegalStateException("publication authority could not be committed", error);
        }
    }

    private void healthy() {
        if (storageFailed) throw new Refused("publication storage is unavailable; restart and reconcile the durable ledger");
    }

    private void validateLedger() throws IOException {
        if (object("groups").size() > 64 || object("operations").size() + activeGroups() > 64 || object("requests").size() > 256) throw new IOException("publication ledger inventory exceeds capacity");
        try {
            object("topics").fields().forEachRemaining(entry -> {
                if (!ExecutionAssignments.validTopic(entry.getKey()) || decimal(entry.getValue().asText()) < 1) throw new Refused("invalid publication topic epoch");
            });
            object("groups").fields().forEachRemaining(entry -> {
                JsonNode group = entry.getValue();
                if (!identifier(group.path("deployment")).equals(entry.getKey()) || decimal(group.path("epoch").asText()) < 1 || !group.path("retired").isBoolean()
                        || !group.path("topics").isArray() || !group.path("grants").isObject()) throw new Refused("invalid publication authority inventory");
                Set<String> retainedTopics = new HashSet<>();
                for (JsonNode topic : group.path("topics")) if (!topic.isTextual() || !ExecutionAssignments.validTopic(topic.asText())
                        || !retainedTopics.add(topic.asText()) || decimal(object("topics").path(topic.asText()).asText()) < decimal(group.path("epoch").asText())) throw new Refused("persisted whole-topic fence was lost or regressed");
                if (!group.path("retired").asBoolean()) {
                    hash(group.path("bindingHash"));
                    if (group.path("grants").isEmpty() || group.path("grants").size() > 3 || !group.path("heads").isObject()) throw new Refused("invalid publication grant inventory");
                    Set<String> topics = new HashSet<>();
                    for (JsonNode topic : group.path("topics")) if (!topic.isTextual() || !topics.add(topic.asText())
                            || !object("topics").path(topic.asText()).equals(group.path("epoch"))) throw new Refused("active publication topic epoch differs from its authority");
                    Set<String> inputs = new HashSet<>();
                    String output = null;
                    for (JsonNode grant : group.path("grants")) {
                        if (!inputs.add(grant.path("inputTopic").asText()) || (output != null && !output.equals(grant.path("outputTopic").asText()))) throw new Refused("persisted whole-topic publication binding conflicts");
                        output = grant.path("outputTopic").asText();
                    }
                    if (output == null || !inputs.add(output) || !inputs.equals(topics)) throw new Refused("persisted publication inventory is incomplete");
                    group.path("heads").fields().forEachRemaining(head -> {
                        if (!group.path("grants").has(head.getKey()) || !head.getValue().path("acknowledged").isBoolean()) throw new Refused("invalid publication predecessor head");
                        hash(head.getValue().path("requestHash"));
                        if (!head.getValue().path("sequence").isNull() && decimal(head.getValue().path("sequence").asText()) < 1) throw new Refused("invalid publication predecessor sequence");
                    });
                }
                group.path("grants").fields().forEachRemaining(granted -> {
                    JsonNode grant = granted.getValue();
                    exact(grant, Set.of("schema", "operation", "deployment", "epoch", "node", "inputTopic", "outputTopic", "token"));
                    if (!grant.path("schema").asText().equals("opentsx.publication-grant.v1") || !identifier(grant.path("node")).equals(granted.getKey())
                            || !grant.path("deployment").equals(group.path("deployment")) || !grant.path("epoch").equals(group.path("epoch"))
                            || !grant.path("token").asText().matches("[0-9a-f]{64}") || !contains(group.path("topics"), grant.path("inputTopic").asText())
                            || !contains(group.path("topics"), grant.path("outputTopic").asText())) throw new Refused("invalid persisted publication grant");
                    identifier(grant.path("operation"));
                });
            });
            object("requests").fields().forEachRemaining(entry -> {
                JsonNode request = entry.getValue();
                if (!entry.getKey().matches("[0-9a-f]{64}") || !entry.getKey().equals(ExecutionAssignments.sha256(request.path("requestText").asText()))
                        || !Set.of("ready", "publishing", "uncertain", "acknowledged").contains(request.path("state").asText())
                        || !object("groups").has(request.path("deployment").asText())) throw new Refused("invalid persisted publication request");
                hash(request.path("outputDigest")); decimal(request.path("outputRecords").asText());
                JsonNode group = object("groups").path(request.path("deployment").asText());
                JsonNode grant = group.path("grants").path(request.path("node").asText());
                if (request.path("state").asText().equals("acknowledged")) {
                    if (decimal(request.path("epoch").asText()) < 1 || decimal(request.path("epoch").asText()) > decimal(group.path("epoch").asText())
                            || !contains(group.path("topics"), request.path("inputTopic").asText()) || !contains(group.path("topics"), request.path("outputTopic").asText())
                            || !group.path("heads").has(request.path("node").asText())) throw new Refused("persisted publication receipt authority conflicts");
                } else if (group.path("retired").asBoolean() || !group.path("epoch").equals(request.path("epoch")) || !grant.path("inputTopic").equals(request.path("inputTopic"))
                        || !grant.path("outputTopic").equals(request.path("outputTopic"))) throw new Refused("persisted publication request authority conflicts");
            });
        } catch (RuntimeException error) { throw new IOException("invalid publication ledger authority", error); }
    }

    private void save() throws IOException {
        ObjectNode envelope = JSON.objectNode().put("schema", "opentsx.publication-ledger.v1")
                .put("checksum", ExecutionAssignments.sha256(RustRollout.stringify(ledger)));
        envelope.set("payload", ledger);
        byte[] bytes = RustRollout.stringify(envelope).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LEDGER_BYTES) throw new IOException("publication ledger exceeds byte limit");
        Path pending = directory.resolve("ledger.pending");
        if (Files.isSymbolicLink(pending)) throw new IOException("publication pending file must not be a symbolic link");
        try (FileChannel file = FileChannel.open(pending, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
        Files.move(pending, directory.resolve("ledger.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory();
    }

    private void syncDirectory() throws IOException {
        try (FileChannel file = FileChannel.open(directory, StandardOpenOption.READ)) { file.force(true); }
    }

    private static long decimal(String text) {
        try {
            long number = Long.parseLong(text);
            if (number < 0 || !Long.toString(number).equals(text)) throw new Refused("invalid publication epoch or count");
            return number;
        } catch (NumberFormatException error) { throw new Refused("invalid publication epoch or count"); }
    }

    private static long increment(String epoch) {
        try { return Math.addExact(decimal(epoch), 1); }
        catch (ArithmeticException error) { throw new Refused("publication epoch capacity reached"); }
    }

    private static String identifier(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("[a-z0-9][a-z0-9-]{0,62}")) throw new Refused("invalid publication identity");
        return value.asText();
    }

    private static String hash(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) throw new Refused("invalid prepared publication digest");
        return value.asText();
    }

    private static void exact(JsonNode value, Set<String> fields) {
        if (!value.isObject() || value.size() != fields.size()) throw new Refused("invalid publication fields");
        value.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw new Refused("invalid publication fields"); });
    }

    private static Writer kafka(String bootstrap) {
        return (topic, partitions, records) -> {
            try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
                var inventory = consumer.partitionsFor(topic, Duration.ofSeconds(30));
                if (inventory == null || inventory.size() != partitions) throw new IllegalStateException("publication output partition inventory changed");
            }
            var configuration = KafScaleClients.producerProps(bootstrap);
            configuration.put(ProducerConfig.RETRIES_CONFIG, 0);
            configuration.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            configuration.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
            configuration.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
            try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(configuration, new StringSerializer(), new ByteArraySerializer())) {
                for (Record record : records) {
                    ProducerRecord<String, byte[]> output = new ProducerRecord<>(topic, null, record.timestamp(), record.key(), record.payload());
                    output.headers().add("tsx-schema", "org.opentsx.model.v2.Episode".getBytes(StandardCharsets.UTF_8));
                    producer.send(output).get(40, TimeUnit.SECONDS);
                }
                producer.flush();
            }
        };
    }

    @Override public synchronized void close() throws IOException {
        processLock.release();
        lockChannel.close();
    }
}
