package org.opentsx.store;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class InMemorySeriesStoreTest extends SeriesStoreContract {

    @Override
    protected SeriesStore newStore() {
        return new InMemorySeriesStore();
    }

    @Test
    void clipKeepsLineageAndBounds() {
        Episode e = Episodes.regular(WIND_T01, T0, SEC, new double[]{1, 2, 3, 4}).label("x", "y").build();
        assertSame(e, EpisodeOps.clip(e, T0, T0 + 10 * SEC));
        Episode c = EpisodeOps.clip(e, T0 + SEC, T0 + 3 * SEC);
        assertArrayEquals(new double[]{2, 3}, Episodes.values(c));
        assertEquals(T0 + SEC, Micros.of(c.getTStart()));
        assertEquals(T0 + 3 * SEC, Micros.of(c.getTEnd()));
        assertEquals(e.getEpisodeId(), c.getProvenance().getParentEpisodeId());
        assertEquals("y", c.getLabels().get("x"));
        assertNull(EpisodeOps.clip(e, T0 + 10 * SEC, T0 + 20 * SEC));
    }
}
