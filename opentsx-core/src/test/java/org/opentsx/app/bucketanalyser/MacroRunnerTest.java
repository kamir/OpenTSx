package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MacroRunnerTest {

    @Test
    void workflowStopsAtItsUnsupportedLoadInsteadOfCompletingWithEmptyBuckets() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> MacroRunner.main(new String[0]));

        assertEquals("Context-based bucket loading is not implemented", error.getMessage());
    }
}
