package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class SiteTestData {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC);
    static final String DIGEST = "sha256:" + "a".repeat(64);
    static final String ABI = "jvm-site-test/1";
    static final String INPUT = "tsx.wind.episodes.v2";
    static final String OUTPUT = "tsx.wind.aggregates.v2";

    private SiteTestData() { }

    static KeyPair key() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }

    static ObjectNode document(String job, int version, String site) {
        String fragment = "{\"source\":{\"kind\":\"kafscale-broker\",\"topics\":[\"" + INPUT
                + "\"]},\"nodes\":[{\"id\":\"decode\",\"op\":\"decode.avro-soe\"},{\"id\":\"stats\",\"op\":\"stats.welford-ddsketch\",\"in\":[\"decode\"]}],\"sinks\":[{\"from\":\"stats\",\"schema\":\"org.opentsx.model.v2.SeriesStats\",\"topic\":\"" + OUTPUT + "\"}]}";
        ObjectNode assignment = SiteJson.JSON.createObjectNode().put("schema", "opentsx.execution-assignment.v1")
                .put("deployment", job).put("generation", Integer.toString(version)).put("assignment", "assignment-" + site)
                .put("attempt", "attempt-1").put("fence", "1").put("fragment", "fragment-" + site)
                .put("site", site).put("node", "node-" + site).put("runtime", "java").put("catalog", "tsx-catalog/1")
                .put("artifactDigest", DIGEST).put("specHash", ExecutionAssignments.sha256(fragment)).put("sourceDagText", fragment)
                .put("fragmentHash", ExecutionAssignments.sha256(fragment)).put("fragmentText", fragment).put("mode", "bounded");
        assignment.putArray("inputs").addObject().put("binding", "site").put("topic", INPUT).put("partitionCount", "1")
                .putArray("positions").addObject().put("partition", "0").put("from", "0").put("to", "5");
        ObjectNode plan = SiteJson.JSON.createObjectNode().put("schema", "opentsx.runner-plan.v1").put("job", job)
                .put("planVersion", version).put("issued", "2026-11-17T00:00:00Z").put("expires", "2026-11-18T00:00:00Z")
                .put("site", site).put("catalog", "tsx-catalog/1").put("specHash", assignment.path("specHash").asText())
                .put("assignmentText", new String(SiteJson.bytes(assignment), StandardCharsets.UTF_8));
        plan.putArray("inputContracts").addObject().put("binding", "site").put("topic", INPUT)
                .putArray("fingerprints").add("8985f02b48c77195");
        plan.putArray("outputs").addObject().put("sink", OUTPUT).put("cluster", "signal").put("topic", OUTPUT)
                .put("partitionCount", "1").putArray("fingerprints").add("e51b48188baa6606");
        ObjectNode federation = plan.putObject("federation").put("route", "signal-cluster").put("maxDelay", "PT1M");
        federation.putArray("groupBy").add("park");
        federation.putArray("scopes").addObject().put("groupId", "0123456789abcdef").put("windowType", "PT10M")
                .put("startMicros", "0").put("endMicros", "600000000");
        var member = federation.putArray("expectedAssignments").addObject();
        for (String field : Set.of("assignment", "site", "node", "generation", "fence", "fragmentHash")) member.set(field, assignment.get(field));
        return plan;
    }

    static void membership(ObjectNode... plans) {
        var members = SiteJson.JSON.createArrayNode();
        for (ObjectNode plan : plans) {
            JsonNode assignment = RunnerPlan.parse(SiteJson.bytes(plan)).assignment();
            var member = members.addObject();
            for (String field : Set.of("assignment", "site", "node", "generation", "fence", "fragmentHash")) member.set(field, assignment.get(field));
        }
        for (ObjectNode plan : plans) ((ObjectNode) plan.path("federation")).set("expectedAssignments", members.deepCopy());
    }

    static JsonNode policy() {
        ObjectNode policy = SiteJson.JSON.createObjectNode();
        policy.putArray("domains").add("wind");
        policy.putObject("inputs").putArray("site").add(INPUT);
        policy.putObject("outputs").putArray("signal").add(OUTPUT);
        policy.putArray("catalogs").add("tsx-catalog/1");
        return policy;
    }

    static ObjectNode report(JsonNode challenge, String series) {
        var topics = SiteJson.JSON.createArrayNode();
        topics.addObject().put("role", "input").put("binding", "site").put("topic", INPUT).put("partitions", 1).putArray("fingerprints").add("8985f02b48c77195");
        topics.addObject().put("role", "output").put("binding", "signal").put("topic", OUTPUT).put("partitions", 1).putArray("fingerprints").add("e51b48188baa6606");
        Map<String, String> passed = ConformanceHandshake.CHECKS.stream().collect(Collectors.toMap(id -> id, id -> "executed synthetic check"));
        return ConformanceHandshake.report(challenge, topics, Set.of(series), passed, CLOCK);
    }
}
