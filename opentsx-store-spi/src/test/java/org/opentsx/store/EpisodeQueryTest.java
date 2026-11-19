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

    @Test
    void aWindowThatDoesNotOpenBeforeItClosesIsRefusedWithBothValues() {
        assertEquals("fromMicros: 5 is not before toMicros 5; expected fromMicros < toMicros",
                assertThrows(IllegalArgumentException.class, () -> EpisodeQuery.of(SeriesSelector.all(), 5, 5)).getMessage());
        assertEquals("fromMicros: 7 is not before toMicros -2; expected fromMicros < toMicros",
                assertThrows(IllegalArgumentException.class, () -> EpisodeQuery.of(SeriesSelector.all(), 7, -2)).getMessage());
        assertEquals(4, EpisodeQuery.of(SeriesSelector.all(), 3, 4).toMicros());
    }
}
