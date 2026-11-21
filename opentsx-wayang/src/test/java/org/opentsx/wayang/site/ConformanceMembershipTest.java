package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TASK-020 U01 "Membership and capabilities" on the signed site path (S3.3): the report is bound to the pinned node,
 * fresh within the plan, carries every check exactly once from the known list, and its topic and series inventories
 * hold unique entries; expected membership names each node for one site only.
 */
class ConformanceMembershipTest {

    private static final ConformanceHandshake.QualifiedRuntime PIN =
            new ConformanceHandshake.QualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
    private static final RunnerPlan PLAN = RunnerPlan.parse(SiteJson.bytes(SiteTestData.document("wind", 1, "s1")));

    private static ObjectNode report() {
        return SiteTestData.report(ConformanceHandshake.challenge(PLAN, PIN), "0123456789abcdef");
    }

    private static JsonNode validate(ObjectNode report) {
        return ConformanceHandshake.validate(SiteJson.bytes(report), PLAN, PIN, Set.of(), SiteTestData.CLOCK);
    }

    private static String refusedCheck(ObjectNode report) {
        JsonNode result = validate(report);
        assertEquals("refused", result.path("result").asText(), result.toString());
        return result.path("check").asText();
    }

    @Test void aReportFromAnotherNodeOfTheSiteIsRefused() {
        assertEquals("accepted", validate(report()).path("result").asText());
        assertEquals("runtime.identity", refusedCheck(report().put("node", "node-s2")));
    }

    @Test void aReportOutsideThePlanOrFromTheFutureIsRefused() {
        // the plan is valid from 2026-11-17T00:00Z to 2026-11-18T00:00Z; the clock reads 2026-11-17T12:00Z
        assertEquals("runtime.identity", refusedCheck(report().put("at", "2026-11-17T12:00:01Z")));
        assertEquals("runtime.identity", refusedCheck(report().put("at", "2026-11-16T23:59:59Z")));
        assertEquals("runtime.identity", refusedCheck(report().put("issued", "2026-11-17T12:00:01Z")));
        assertEquals("runtime.identity", refusedCheck(report().put("expires", "2026-11-18T00:00:01Z")));
        assertEquals("accepted", validate(report().put("expires", "2026-11-18T00:00:00Z")).path("result").asText());
    }

    @Test void aCheckOutsideTheKnownListIsAnInvalidReport() {
        ObjectNode unknown = report();
        ((ObjectNode) unknown.path("checks").get(0)).put("id", "vectors.extra");
        assertEquals("plan.invalid", assertThrows(PlanRefusal.class, () -> validate(unknown)).code());
        ObjectNode extra = report();
        extra.withArray("checks").addObject().put("id", "vectors.extra").put("result", "pass").put("detail", "executed");
        assertEquals("plan.invalid", assertThrows(PlanRefusal.class, () -> validate(extra)).code());
    }

    @Test void repeatedTopicRowsAndRepeatedOrUnsortedSeriesAreRefused() {
        ObjectNode topics = report();
        topics.withArray("topics").add(topics.path("topics").get(0).deepCopy());
        assertEquals("topics.names", refusedCheck(topics));

        ObjectNode repeated = report();
        ObjectNode series = (ObjectNode) repeated.path("series");
        series.put("count", 2).putArray("ids").add("0123456789abcdef").add("0123456789abcdef");
        assertEquals("series.disjoint", refusedCheck(repeated));

        ObjectNode unsorted = report();
        ((ObjectNode) unsorted.path("series")).put("count", 2).putArray("ids").add("fedcba9876543210").add("0123456789abcdef");
        assertEquals("series.disjoint", refusedCheck(unsorted));
    }

    /** D-T17: a site has exactly one node; its qualified pin cannot name a second node. */
    @Test void aSiteKeepsTheOneNodeOfItsQualifiedPin(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var driver = SiteTestData.key();
        try (var service = new TsxPlanService(driver.getPublic(), java.util.Map.of("s1", SiteTestData.key().getPublic()),
                java.util.Map.of("c".repeat(64), "s1"), directory, SiteTestData.CLOCK, (plan, value) -> { }, (plan, value) -> { })) {
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            service.registerQualifiedRuntime("s1", "node-s1", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI);
            assertEquals("a runtime pin cannot change while the service is running", assertThrows(IllegalArgumentException.class,
                    () -> service.registerQualifiedRuntime("s1", "node-s2", "3.0.0", SiteTestData.DIGEST, SiteTestData.ABI)).getMessage());
        }
    }

    @Test void expectedMembershipNamesEachNodeForOneSiteOnly() {
        ObjectNode document = SiteTestData.document("wind", 1, "s1");
        ObjectNode other = ((ObjectNode) document.path("federation").path("expectedAssignments").get(0)).deepCopy();
        other.put("assignment", "assignment-s2").put("site", "s2").put("node", "node-s2");
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("federation").path("expectedAssignments")).add(other);
        RunnerPlan.parse(SiteJson.bytes(document));
        other.put("node", "node-s1");
        PlanRefusal refusal = assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(document)));
        assertEquals("plan.invalid", refusal.code());
    }
}
