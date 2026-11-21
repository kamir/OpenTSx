package org.opentsx.wayang.site;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.contracts.ExecutionAssignments;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** D-T21: the contract admits whole-topic outputs, but a JVM site refuses them until it writes them. */
class AssignmentOutputsTest {

    @Test void aSitePlanWhoseAssignmentDeclaresOutputsIsRefused() {
        ObjectNode document = SiteTestData.document("wind", 1, "s1");
        ObjectNode assignment = (ObjectNode) SiteJson.parse(document.path("assignmentText").asText().getBytes(StandardCharsets.UTF_8), 65_536);
        assignment.putArray("outputs").addObject().put("binding", "site").put("topic", "tsx.wind.repartition.flow.vector.v2")
                .put("partitionCount", "1").put("port", "out").put("kind", "repartition");
        String text = new String(SiteJson.bytes(assignment), StandardCharsets.UTF_8);
        ExecutionAssignments.validate(text);
        document.put("assignmentText", text);
        PlanRefusal refusal = assertThrows(PlanRefusal.class, () -> RunnerPlan.parse(SiteJson.bytes(document)));
        assertEquals("plan.invalid", refusal.code());
        assertEquals("assignment outputs are not executed by this runtime yet (D-T21)", refusal.getMessage());
    }
}
