package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentsx.model.contracts.ExecutionAssignments;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.DagToWayang;
import org.opentsx.wayang.federation.AggregateInventory;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

public final class RunnerPlan {
    private final JsonNode document;
    private final JsonNode assignment;
    private final String commandHash;

    private RunnerPlan(JsonNode document, JsonNode assignment, String commandHash) {
        this.document = document.deepCopy();
        this.assignment = assignment.deepCopy();
        this.commandHash = commandHash;
    }

    public static RunnerPlan verify(String compact, PublicKey key) {
        return parse(SignedJws.verify(compact, key), SiteJson.hash(compact.getBytes(StandardCharsets.US_ASCII)));
    }

    public static RunnerPlan parse(byte[] exactJson) { return parse(exactJson, SiteJson.hash(exactJson)); }

    private static RunnerPlan parse(byte[] exactJson, String hash) {
        JsonNode value = SiteJson.parse(exactJson, SiteJson.MAX_BYTES);
        SiteJson.fields(value, "schema", "job", "planVersion", "issued", "expires", "site", "catalog", "specHash",
                "assignmentText", "inputContracts", "outputs", "federation");
        if (!value.path("schema").asText().equals("opentsx.runner-plan.v1")) throw SiteJson.invalid("unknown runner plan schema");
        SiteJson.id(value, "job");
        SiteJson.id(value, "site");
        JsonNode version = value.get("planVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) throw SiteJson.invalid("invalid planVersion");
        Instant issued = instant(value, "issued"), expires = instant(value, "expires");
        if (!expires.isAfter(issued)) throw SiteJson.invalid("expires must follow issued");
        String text = SiteJson.text(value, "assignmentText");
        try { ExecutionAssignments.validate(text); }
        catch (IllegalArgumentException error) { throw SiteJson.invalid(error.getMessage()); }
        JsonNode assignment = SiteJson.parse(text.getBytes(StandardCharsets.UTF_8), 65_536);
        if (!assignment.path("runtime").asText().equals("java") || !assignment.path("mode").asText().equals("bounded")) {
            throw SiteJson.invalid("the JVM site profile requires a bounded Java assignment");
        }
        if (!assignment.path("deployment").equals(value.get("job")) || !assignment.path("site").equals(value.get("site"))
                || !assignment.path("catalog").equals(value.get("catalog")) || !assignment.path("specHash").equals(value.get("specHash"))
                || !assignment.path("generation").asText().equals(Integer.toString(version.intValue()))) {
            throw SiteJson.invalid("runner and assignment identities differ");
        }
        Set<String> inputs = new HashSet<>();
        Set<String> inputTopics = new HashSet<>();
        for (JsonNode input : assignment.path("inputs")) {
            if (!inputTopics.add(input.path("topic").asText())) throw SiteJson.invalid("bounded JVM inputs must have distinct topic names");
            inputs.add(input.path("binding").asText() + "|" + input.path("topic").asText());
        }
        JsonNode contracts = value.get("inputContracts");
        if (!contracts.isArray() || contracts.size() != inputs.size()) throw SiteJson.invalid("input contracts do not cover the assignment");
        Set<String> seen = new HashSet<>();
        for (JsonNode input : contracts) {
            SiteJson.fields(input, "binding", "topic", "fingerprints");
            String identity = SiteJson.id(input, "binding") + "|" + SiteJson.text(input, "topic");
            if (!inputs.contains(identity) || !seen.add(identity)) throw SiteJson.invalid("input contract identity differs");
            fingerprints(input.get("fingerprints"));
        }
        JsonNode outputs = value.get("outputs");
        if (!outputs.isArray() || outputs.isEmpty() || outputs.size() > 128) throw SiteJson.invalid("invalid output inventory");
        Set<String> outputIds = new HashSet<>();
        for (JsonNode output : outputs) {
            SiteJson.fields(output, "sink", "cluster", "topic", "partitionCount", "fingerprints");
            if (SiteJson.text(output, "sink").length() > 249) throw SiteJson.invalid("invalid output sink identity");
            String cluster = SiteJson.text(output, "cluster"), topic = SiteJson.text(output, "topic");
            long partitions = SiteJson.decimal(SiteJson.text(output, "partitionCount"), true);
            if (!Set.of("site", "signal").contains(cluster) || !ExecutionAssignments.validTopic(topic)
                    || partitions > Integer.MAX_VALUE || !outputIds.add(cluster + "|" + topic)) {
                throw SiteJson.invalid("invalid output binding");
            }
            fingerprints(output.get("fingerprints"));
        }
        JsonNode fragment = SiteJson.parse(assignment.path("fragmentText").asText().getBytes(StandardCharsets.UTF_8), 65_536);
        if (!inputTopics.equals(strings(fragment.path("source").path("topics"), true))) {
            throw SiteJson.invalid("assignment input topics must match the fragment source topics");
        }
        if (fragment.path("sinks").size() != 1 || outputs.size() != 1) throw SiteJson.invalid("bounded JVM execution requires one statistics sink");
        JsonNode sink = fragment.path("sinks").get(0), output = outputs.get(0);
        if (!sink.path("schema").asText().equals("org.opentsx.model.v2.SeriesStats") || !sink.path("topic").equals(output.path("topic"))) {
            throw SiteJson.invalid("bounded JVM execution requires a matching SeriesStats output");
        }
        String identity = DagToWayang.sinkIdentity(sink, new TopicLayout(output.path("topic").asText().split("\\.")[1]));
        if (!identity.equals(output.path("sink").asText()) || !strings(output.path("fingerprints"), true)
                .equals(Set.of(String.format("%016x", OpenTsxAvro.fingerprint(SeriesStats.getClassSchema()))))) {
            throw SiteJson.invalid("output sink identity or fingerprint differs from the fragment");
        }
        boolean terminalStatistics = false;
        for (JsonNode node : fragment.path("nodes")) {
            if (node.path("id").equals(sink.path("from")) && node.path("op").asText().equals("stats.welford-ddsketch")) terminalStatistics = true;
        }
        if (!terminalStatistics) throw SiteJson.invalid("bounded JVM statistics sink must read stats.welford-ddsketch");
        JsonNode federation = value.get("federation");
        SiteJson.fields(federation, "route", "expectedAssignments", "scopes", "groupBy", "maxDelay");
        if (!Set.of("signal-cluster", "job-state").contains(SiteJson.text(federation, "route"))) throw SiteJson.invalid("invalid federation route");
        try { if (Duration.parse(SiteJson.text(federation, "maxDelay")).isNegative()) throw SiteJson.invalid("invalid maxDelay"); }
        catch (DateTimeParseException error) { throw SiteJson.invalid("invalid maxDelay"); }
        strings(federation.get("groupBy"), false);
        JsonNode expected = federation.get("expectedAssignments");
        if (!expected.isArray() || expected.isEmpty() || expected.size() > 128) throw SiteJson.invalid("invalid expected assignment inventory");
        Set<String> assignmentIds = new HashSet<>(), sites = new HashSet<>();
        boolean own = false;
        for (JsonNode member : expected) {
            SiteJson.fields(member, "assignment", "site", "node", "generation", "fence", "fragmentHash");
            for (String name : Set.of("assignment", "site", "node")) SiteJson.id(member, name);
            SiteJson.decimal(SiteJson.text(member, "generation"), true);
            SiteJson.decimal(SiteJson.text(member, "fence"), true);
            if (!SiteJson.text(member, "fragmentHash").matches("[0-9a-f]{64}")
                    || !member.get("generation").equals(assignment.get("generation"))
                    || !assignmentIds.add(member.path("assignment").asText()) || !sites.add(member.path("site").asText())) {
                throw SiteJson.invalid("invalid expected assignment identity");
            }
            if (member.get("assignment").equals(assignment.get("assignment"))) {
                for (String name : Set.of("site", "node", "generation", "fence", "fragmentHash")) {
                    if (!member.get(name).equals(assignment.get(name))) throw SiteJson.invalid("own assignment inventory differs");
                }
                own = true;
            }
        }
        if (!own) throw SiteJson.invalid("own assignment is missing from expected membership");
        try {
            AggregateInventory inventory = new AggregateInventory(value.path("job").asText(), version.intValue(),
                    value.path("specHash").asText(), expected, federation.path("scopes"));
            inventory.admit(text);
        } catch (IllegalArgumentException error) { throw SiteJson.invalid(error.getMessage()); }
        return new RunnerPlan(value, assignment, hash);
    }

    static Instant instant(JsonNode document, String name) {
        try {
            String text = SiteJson.text(document, name);
            if (!text.endsWith("Z")) throw SiteJson.invalid(name + " must be UTC");
            return Instant.parse(text);
        } catch (DateTimeParseException error) { throw SiteJson.invalid("invalid " + name); }
    }

    static Set<String> strings(JsonNode value, boolean nonempty) {
        if (!value.isArray() || nonempty && value.isEmpty()) throw SiteJson.invalid("expected a string inventory");
        Set<String> out = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isEmpty() || !out.add(item.textValue())) throw SiteJson.invalid("invalid string inventory");
        }
        return out;
    }

    static void fingerprints(JsonNode value) {
        for (String fingerprint : strings(value, true)) {
            if (!fingerprint.matches("[0-9a-f]{16}")) throw SiteJson.invalid("invalid schema fingerprint");
        }
    }

    public JsonNode document() { return document.deepCopy(); }
    public JsonNode assignment() { return assignment.deepCopy(); }
    public String assignmentText() { return document.path("assignmentText").asText(); }
    public String exactAssignmentText() { return assignmentText(); }
    public JsonNode checkpointIdentity(String stateAbi) {
        if (stateAbi == null || stateAbi.isBlank()) throw SiteJson.invalid("state ABI is required");
        var identity = SiteJson.JSON.createObjectNode().put("schema", "opentsx.site-continuation.v1")
                .put("job", job()).put("planVersion", planVersion()).put("stateAbi", stateAbi).put("commandHash", commandHash);
        for (String name : Set.of("catalog", "specHash", "fragmentHash", "assignment", "generation", "fence", "site", "node", "artifactDigest", "inputs")) {
            identity.set(name, assignment.get(name));
        }
        return identity;
    }
    public String job() { return document.path("job").asText(); }
    public int planVersion() { return document.path("planVersion").asInt(); }
    public String site() { return document.path("site").asText(); }
    public String commandHash() { return commandHash; }
    public Instant issued() { return instant(document, "issued"); }
    public Instant expires() { return instant(document, "expires"); }
}
