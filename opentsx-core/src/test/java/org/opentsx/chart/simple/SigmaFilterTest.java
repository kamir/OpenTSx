package org.opentsx.chart.simple;

import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SigmaFilterTest {

    @Test
    void aggregatingPrintsNothing() {
        SigmaFilter filter = new SigmaFilter();
        filter.addCollect(constant("low", 1.0), false);
        filter.addCollect(constant("high", 3.0), false);
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(sink);
            System.setErr(sink);
            filter.aggregate();
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(2.0, 2.0, 2.0), filter.mwRAW.yValues);
    }

    private static TimeSeriesObject constant(String label, double value) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel(label);
        for (int i = 0; i < 3; i++) {
            row.addValuePair(i, value);
        }
        return row;
    }
}
