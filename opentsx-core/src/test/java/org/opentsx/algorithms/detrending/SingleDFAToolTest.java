package org.opentsx.algorithms.detrending;

import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class SingleDFAToolTest {

    @Test
    void noSeriesFailInsteadOfPrinting() {
        assertEquals("calcMultiDFA: vmr has no series; expected at least 1 series",
                assertThrows(IllegalArgumentException.class,
                        () -> SingleDFATool.calcMultiDFA(new Vector<>(), 1, "v")).getMessage());
        assertEquals("calcMultiDFA: vmr has no series; expected at least 1 series",
                assertThrows(IllegalArgumentException.class,
                        () -> MultiDFATool2.calcMultiDFA(new Vector<>(), 1, "v")).getMessage());
    }

    @Test
    void aMissingSeriesFailsNamingItsIndex() {
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(walk(500));
        rows.add(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SingleDFATool().runDFA(rows, 2));
        assertEquals("runDFA: series 1 is null; expected a series at every index", e.getMessage());
    }

    private static TimeSeriesObject walk(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        double y = 0.0;
        for (int i = 0; i < n; i++) {
            y += Math.sin(i * 0.7) + (i % 3) - 1.0;
            row.addValuePair(i, y);
        }
        return row;
    }
}
