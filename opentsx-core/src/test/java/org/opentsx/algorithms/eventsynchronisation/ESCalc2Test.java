package org.opentsx.algorithms.eventsynchronisation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ESCalc2Test {

    @Test
    void tooFewEventsFailNamingTheCounts() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ESCalc2.calcES(new int[]{1, 5}, new int[]{2, 6, 9, 12}));
        assertEquals("calcES: tx has 2 events and ty has 4; expected at least 3 events in each", e.getMessage());
    }
}
