package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RustRolloutRequestLimitTest {
    @Test void twoAdmittedPlansAndTheirCompletedResultFitTheReplacementEnvelope() {
        var fake = new RustContinuousRolloutTest.Fake();
        String source = " ".repeat(31_000) + RustContinuousRolloutTest.SOURCE;
        var previous = fake.rollout().planContinuous("deployment-first", source, "1790812800000000");
        var result = fake.rollout().execute(previous);
        var replacement = fake.rollout().planContinuous("deployment-next", source.replace("0.001", "0.002")
                .replace("tsx.continuous.clean.v2", "tsx.restarted.clean.v2"), "1790812800000001");
        var body = JsonNodeFactory.instance.objectNode().put("operation", "switch-1").put("mode", "restart").put("expectedEpoch", "1");
        body.set("plan", previous); body.set("result", result); body.set("replacement", replacement);
        int bytes = RustRollout.stringify(body).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes > 262_144);
        assertTrue(bytes < RustRolloutServer.MAX_BYTES);
    }
}
