package org.opentsx.wayang.federation;

import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.opentsx.wayang.federation.FederationFixtures.*;

class SiteStatsTest {

    @Test void rawSeriesAreFoldedInCanonicalOrderWithoutChangingInputs() {
        var first = statistics("PT10M", 1, 2, Double.NaN);
        first.setSeriesId("1111111111111111");
        var second = statistics("PT10M", 3, 4);
        second.setSeriesId("2222222222222222");
        var mapping = Map.of(first.getSeriesId(), GROUP, second.getSeriesId(), GROUP);
        var avro = new OpenTsxAvro();
        byte[] original = avro.encode(first);
        var forward = SiteStats.group(List.of(first, second), mapping, 0.01);
        var reverse = SiteStats.group(List.of(second, first), mapping, 0.01);
        assertEquals(1, forward.size());
        assertArrayEquals(avro.encode(forward.get(0)), avro.encode(reverse.get(0)));
        assertArrayEquals(original, avro.encode(first));
        assertEquals(4, forward.get(0).getValidCount());
        assertEquals(1, forward.get(0).getNanCount());
        assertEquals(2.5, forward.get(0).getMean());
        assertThrows(IllegalArgumentException.class, () -> SiteStats.group(List.of(first, first), mapping, 0.01));
        assertThrows(IllegalArgumentException.class, () -> SiteStats.group(List.of(first), Map.of(), 0.01));
    }

    @Test void globalRecordsAreExcludedAndKnownEmptyRawWindowsRemainNan() {
        var global = statistics("PT10M", 1);
        global.setWindowType("GLOBAL");
        assertTrue(SiteStats.group(List.of(global), Map.of(), 0.01).isEmpty());
        var empty = statistics("PT10M");
        empty.setSeriesId("1111111111111111");
        var output = SiteStats.group(List.of(empty), Map.of(empty.getSeriesId(), GROUP), 0.01).get(0);
        assertEquals(0, output.getValidCount());
        assertTrue(Double.isNaN(output.getMean()));
        assertTrue(Double.isNaN(output.getM2()));
        assertTrue(Double.isNaN(output.getMin()));
        assertNull(output.getLastSeen());
    }
}
