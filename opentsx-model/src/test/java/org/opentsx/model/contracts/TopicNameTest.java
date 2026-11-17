package org.opentsx.model.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicNameTest {
    @Test
    void deploymentOutputsUseTheAssignmentTopicGrammar() {
        assertTrue(ExecutionAssignments.validTopic("tsx.wind.worker-1.clean.v2"));
        for (String value : new String[]{"other", "tsx.wind.Clean.v2", "tsx.wind.clean.v02", "tsx.wind.clean.v2\n"}) {
            assertFalse(ExecutionAssignments.validTopic(value), value);
        }
        assertFalse(ExecutionAssignments.validTopic(null));
        assertFalse(ExecutionAssignments.validTopic("tsx.wind." + "a".repeat(240) + ".v2"));
    }
}
