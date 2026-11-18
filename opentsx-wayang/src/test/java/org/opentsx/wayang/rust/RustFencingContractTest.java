package org.opentsx.wayang.rust;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustFencingContractTest {
    @Test void rolloutExposesExplicitSwitchAndRetirementOperations() throws Exception {
        assertEquals("replace", RustRollout.class.getMethod("replace", JsonNode.class).getName());
        assertEquals("retire", RustRollout.class.getMethod("retire", JsonNode.class).getName());
    }

    @Test void metadataRequiresAnExplicitEmptyOutputProof() throws Exception {
        assertTrue(RustRollout.Metadata.class.getMethod("requireEmpty", String.class, int.class).isDefault());
    }
}
