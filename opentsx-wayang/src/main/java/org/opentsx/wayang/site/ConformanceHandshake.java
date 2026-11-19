package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConformanceHandshake {
    public static final List<String> CHECKS = List.of("vectors.wire", "vectors.series-id", "vectors.operators",
            "kafscale.required", "topics.names", "topics.partitions", "topics.fingerprints", "partitioner.murmur2",
            "series.disjoint", "runtime.identity");

    public record QualifiedRuntime(String site, String node, String version, String digest, String stateAbi, String runtime) {
        public QualifiedRuntime(String site, String node, String version, String digest, String stateAbi) {
            this(site, node, version, digest, stateAbi, "java");
        }

        public QualifiedRuntime {
            if (site == null || !site.matches("[a-z0-9][a-z0-9-]{0,62}") || node == null
                    || !node.matches("[a-z0-9][a-z0-9-]{0,62}") || version == null || version.isBlank()
                    || digest == null || !digest.matches("sha256:[0-9a-f]{64}") || stateAbi == null || stateAbi.isBlank()
                    || runtime == null || !Set.of("java", "rust").contains(runtime)) {
                throw new IllegalArgumentException("qualified runtime pin is invalid");
            }
        }

        public String track() { return runtime.equals("rust") ? "B" : "A"; }
    }

    private ConformanceHandshake() { }

    public static JsonNode challenge(RunnerPlan plan, QualifiedRuntime runtime) {
        ObjectNode contract = SiteJson.JSON.createObjectNode().put("schema", "opentsx.site-admission.v1")
                .put("job", plan.job()).put("planVersion", plan.planVersion()).put("site", plan.site())
                .put("node", runtime.node()).put("catalog", plan.document().path("catalog").asText()).put("specHash", plan.document().path("specHash").asText())
                .put("commandHash", plan.commandHash())
                .put("generation", plan.assignment().path("generation").asText()).put("issued", plan.document().path("issued").asText())
                .put("expires", plan.document().path("expires").asText());
        contract.putObject("artifact").put("version", runtime.version()).put("digest", runtime.digest()).put("stateAbi", runtime.stateAbi());
        contract.set("inputs", plan.assignment().path("inputs"));
        contract.set("inputContracts", plan.document().path("inputContracts"));
        contract.set("expectedAssignments", plan.document().path("federation").path("expectedAssignments"));
        contract.set("scopes", plan.document().path("federation").path("scopes"));
        contract.set("outputs", plan.document().path("outputs"));
        return SiteJson.JSON.createObjectNode().put("contractHash", SiteJson.hash(SiteJson.bytes(contract))).set("contract", contract);
    }

    public static JsonNode validate(byte[] payload, RunnerPlan plan, QualifiedRuntime runtime, Set<String> otherSeries, Clock clock) {
        JsonNode report = SiteJson.parse(payload, SiteJson.MAX_BYTES);
        SiteJson.fields(report, "schema", "site", "node", "job", "planVersion", "generation", "admissionHash",
                "at", "issued", "expires", "track", "artifact", "catalog", "checks", "topics", "series");
        if (!report.path("schema").asText().equals("opentsx.site-conformance.v1")) throw SiteJson.invalid("unknown conformance schema");
        JsonNode challenge = challenge(plan, runtime);
        JsonNode contract = challenge.path("contract");
        for (String name : List.of("site", "node", "job", "planVersion", "generation", "catalog", "artifact")) {
            if (!report.path(name).equals(contract.path(name))) return refused("runtime.identity", "report runtime or job identity differs");
        }
        if (!report.path("track").asText().equals(runtime.track())
                || !plan.assignment().path("runtime").asText().equals(runtime.runtime())
                || !report.path("admissionHash").asText().equals(challenge.path("contractHash").asText())
                || !plan.assignment().path("artifactDigest").asText().equals(runtime.digest())) {
            return refused("runtime.identity", "runtime is not qualified for this admission");
        }
        var issued = RunnerPlan.instant(report, "issued");
        var expires = RunnerPlan.instant(report, "expires");
        var at = RunnerPlan.instant(report, "at");
        if (issued.isAfter(clock.instant()) || at.isBefore(issued) || at.isAfter(clock.instant())
                || !expires.isAfter(issued) || !clock.instant().isBefore(expires) || expires.isAfter(plan.expires())) {
            return refused("runtime.identity", "conformance report is outside its validity interval");
        }
        JsonNode checks = report.path("checks");
        if (!checks.isArray()) throw SiteJson.invalid("invalid conformance checks");
        Map<String, JsonNode> byCheck = new HashMap<>();
        for (JsonNode check : checks) {
            SiteJson.fields(check, "id", "result", "detail");
            String id = SiteJson.text(check, "id");
            if (!CHECKS.contains(id)) throw SiteJson.invalid("unknown conformance check");
            if (byCheck.putIfAbsent(id, check) != null) return refused(id, "duplicate conformance check");
        }
        for (String id : CHECKS) {
            JsonNode check = byCheck.get(id);
            if (check == null || !check.path("result").asText().equals("pass") || !check.path("detail").isTextual()
                    || check.path("detail").asText().isBlank()) return refused(id, "conformance check did not pass");
        }
        if (checks.size() != CHECKS.size()) throw SiteJson.invalid("conformance check inventory differs");
        JsonNode topics = report.path("topics");
        if (!topics.isArray()) throw SiteJson.invalid("invalid conformance topic inventory");
        Map<String, JsonNode> byTopic = new HashMap<>();
        for (JsonNode topic : topics) {
            SiteJson.fields(topic, "role", "binding", "topic", "partitions", "fingerprints");
            String name = SiteJson.text(topic, "role") + "|" + SiteJson.text(topic, "binding") + "|" + SiteJson.text(topic, "topic");
            if (byTopic.putIfAbsent(name, topic) != null) return refused("topics.names", "duplicate topic inventory");
        }
        Map<String, JsonNode> expectedTopics = new HashMap<>();
        for (JsonNode input : plan.assignment().path("inputs")) {
            ObjectNode expected = ((ObjectNode) input).deepCopy();
            for (JsonNode contractEntry : plan.document().path("inputContracts")) {
                if (contractEntry.path("binding").equals(input.path("binding")) && contractEntry.path("topic").equals(input.path("topic"))) {
                    expected.set("fingerprints", contractEntry.path("fingerprints"));
                }
            }
            expectedTopics.put("input|" + input.path("binding").asText() + "|" + input.path("topic").asText(), expected);
        }
        for (JsonNode output : plan.document().path("outputs")) {
            expectedTopics.put("output|" + output.path("cluster").asText() + "|" + output.path("topic").asText(), output);
        }
        if (!expectedTopics.keySet().equals(byTopic.keySet())) return refused("topics.names", "topic inventory differs");
        for (var entry : expectedTopics.entrySet()) {
            JsonNode expected = entry.getValue();
            JsonNode topic = byTopic.get(entry.getKey());
            if (topic == null) return refused("topics.names", "required topic is absent");
            if (!topic.path("partitions").isIntegralNumber() || !topic.path("partitions").canConvertToInt()
                    || !Integer.toString(topic.path("partitions").asInt()).equals(expected.path("partitionCount").asText())) {
                return refused("topics.partitions", "topic partition count differs");
            }
        }
        for (var entry : expectedTopics.entrySet()) {
            JsonNode expected = entry.getValue();
            JsonNode topic = byTopic.get(entry.getKey());
            try {
                RunnerPlan.fingerprints(topic.path("fingerprints"));
                if (!RunnerPlan.strings(topic.path("fingerprints"), true)
                        .equals(RunnerPlan.strings(expected.path("fingerprints"), true))) return refused("topics.fingerprints", "topic fingerprint differs");
            } catch (PlanRefusal error) { return refused("topics.fingerprints", "topic fingerprint inventory is invalid"); }
        }
        JsonNode series = report.path("series");
        SiteJson.fields(series, "count", "ids");
        if (!series.path("count").isIntegralNumber() || !series.path("count").canConvertToInt()
                || series.path("count").asInt() < 0 || !series.path("ids").isArray()
                || series.path("count").asInt() != series.path("ids").size()) return refused("series.disjoint", "series count differs");
        String previous = null;
        for (JsonNode id : series.path("ids")) {
            if (!id.isTextual() || !id.asText().matches("[0-9a-f]{16}") || previous != null && previous.compareTo(id.asText()) >= 0
                    || otherSeries.contains(id.asText())) return refused("series.disjoint", "series inventory overlaps or is not a sorted unique set");
            previous = id.asText();
        }
        return SiteJson.JSON.createObjectNode().put("result", "accepted");
    }

    public static ObjectNode report(JsonNode challenge, JsonNode topics, Set<String> series, Map<String, String> passedChecks, Clock clock) {
        JsonNode contract = challenge.path("contract");
        ObjectNode report = SiteJson.JSON.createObjectNode().put("schema", "opentsx.site-conformance.v1")
                .put("admissionHash", challenge.path("contractHash").asText()).put("at", clock.instant().toString()).put("track", "A");
        for (String name : List.of("site", "node", "job", "planVersion", "generation", "catalog", "artifact", "issued", "expires")) {
            report.set(name, contract.path(name));
        }
        var checks = report.putArray("checks");
        for (String id : CHECKS) checks.addObject().put("id", id).put("result", passedChecks.containsKey(id) ? "pass" : "fail")
                .put("detail", passedChecks.getOrDefault(id, "check was not executed"));
        report.set("topics", topics.deepCopy());
        ObjectNode inventory = report.putObject("series").put("count", series.size());
        var ids = inventory.putArray("ids");
        series.stream().sorted().forEach(ids::add);
        return report;
    }

    private static ObjectNode refused(String check, String message) {
        return SiteJson.JSON.createObjectNode().put("result", "refused").put("check", check).put("message", message);
    }
}
