package org.opentsx.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EpisodeQueryTest {

    @Test
    void aLimitBelowOneIsRefusedWithTheValue() {
        EpisodeQuery q = EpisodeQuery.of(SeriesSelector.all(), 0, 1);
        assertEquals("limit: 0 is out of range; expected a whole number above 0",
                assertThrows(IllegalArgumentException.class, () -> q.limit(0)).getMessage());
        assertEquals("limit: -3 is out of range; expected a whole number above 0",
                assertThrows(IllegalArgumentException.class, () -> q.limit(-3)).getMessage());
        assertEquals(1, q.limit(1).limit());
    }
}
