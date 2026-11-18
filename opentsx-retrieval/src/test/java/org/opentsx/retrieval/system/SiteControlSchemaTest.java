package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteControlSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CHECKS = List.of("vectors.wire", "vectors.series-id", "vectors.operators", "kafscale.required",
            "topics.names", "topics.partitions", "topics.fingerprints", "partitioner.murmur2", "series.disjoint", "runtime.identity");

    private static JsonSchema schema(String name) throws Exception {
        JsonNode document = JSON.readTree(Path.of("..", "docs", "datamodel-2026", "schemas", name).toFile());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document);
    }

    private static ObjectNode report() {
        ObjectNode report = JSON.createObjectNode().put("schema", "opentsx.site-conformance.v1").put("site", "s1")
                .put("node", "node-s1").put("job", "wind").put("planVersion", 1).put("generation", "1")
                .put("admissionHash", "a".repeat(64)).put("issued", "2026-11-17T00:00:00Z").put("at", "2026-11-17T12:00:00Z")
                .put("expires", "2026-11-18T00:00:00Z").put("track", "A").put("catalog", "tsx-catalog/1");
        report.putObject("artifact").put("version", "3.0.0").put("digest", "sha256:" + "a".repeat(64)).put("stateAbi", "jvm-site-test/1");
        var checks = report.putArray("checks");
        CHECKS.forEach(id -> checks.addObject().put("id", id).put("result", "pass").put("detail", "executed synthetic check"));
        report.putArray("topics").addObject().put("role", "input").put("binding", "site").put("topic", "tsx.wind.episodes.v2")
                .put("partitions", 1).putArray("fingerprints").add("8985f02b48c77195");
        report.putObject("series").put("count", 1).putArray("ids").add("0123456789abcdef");
        return report;
    }

    @Test void duplicateLegacyChecksCannotSatisfyConformance() throws Exception {
        ObjectNode report = report();
        report.remove(List.of("node", "job", "planVersion", "generation", "admissionHash", "issued", "expires"));
        ((ObjectNode) report.path("artifact")).remove("stateAbi");
        report.putArray("topics");
        var checks = report.putArray("checks");
        for (int index = 0; index < 10; index++) checks.addObject().put("id", "vectors.wire").put("result", "pass");
        assertFalse(schema("opentsx.site-conformance.v1.schema.json").validate(report).isEmpty());
    }

    @Test void exactDetailedJobBoundReportsMatchTheSchema() throws Exception {
        JsonSchema schema = schema("opentsx.site-conformance.v1.schema.json");
        ObjectNode valid = report();
        assertTrue(schema.validate(valid).isEmpty(), schema.validate(valid).toString());
        ((ObjectNode) valid.path("checks").get(1)).put("id", "vectors.wire");
        assertFalse(schema.validate(valid).isEmpty());
        valid = report();
        ((ObjectNode) valid.path("checks").get(0)).put("detail", " ");
        assertFalse(schema.validate(valid).isEmpty());
        valid = report();
        ((ObjectNode) valid.path("topics").get(0)).remove("binding");
        assertFalse(schema.validate(valid).isEmpty());
    }

    @Test void runnerWrapperRequiresBoundedVersionsAndSignedScopeInventory() throws Exception {
        ObjectNode plan = JSON.createObjectNode().put("schema", "opentsx.runner-plan.v1").put("job", "wind")
                .put("planVersion", 1).put("issued", "2026-11-17T00:00:00Z").put("expires", "2026-11-18T00:00:00Z")
                .put("site", "s1").put("catalog", "tsx-catalog/1").put("specHash", "a".repeat(64)).put("assignmentText", "{}");
        plan.putArray("inputContracts").addObject().put("binding", "site").put("topic", "tsx.wind.episodes.v2")
                .putArray("fingerprints").add("8985f02b48c77195");
        plan.putArray("outputs").addObject().put("sink", "tsx.wind.aggregates.v2").put("cluster", "signal")
                .put("topic", "tsx.wind.aggregates.v2").put("partitionCount", "1").putArray("fingerprints").add("e51b48188baa6606");
        ObjectNode federation = plan.putObject("federation").put("route", "signal-cluster").put("maxDelay", "PT1M");
        federation.putArray("groupBy").add("park");
        federation.putArray("expectedAssignments").addObject().put("assignment", "a1").put("site", "s1").put("node", "node-s1")
                .put("generation", "1").put("fence", "1").put("fragmentHash", "a".repeat(64));
        federation.putArray("scopes").addObject().put("groupId", "0123456789abcdef").put("windowType", "PT10M")
                .put("startMicros", "0").put("endMicros", "600000000");
        JsonSchema schema = schema("opentsx.runner-plan.v1.schema.json");
        assertTrue(schema.validate(plan).isEmpty(), schema.validate(plan).toString());
        plan.put("planVersion", 2147483648L);
        assertFalse(schema.validate(plan).isEmpty());
        plan.put("planVersion", 1);
        federation.remove("scopes");
        assertFalse(schema.validate(plan).isEmpty());
    }
}
