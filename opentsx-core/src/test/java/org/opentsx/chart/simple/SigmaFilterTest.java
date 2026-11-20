package org.opentsx.chart.simple;

import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

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

    @Test
    void theSigmaBandChartShowsTheMeanAndTheBand() throws Exception {
        Vector<TimeSeriesObject> rows = new Vector<>(List.of(constant("low", 1.0), constant("high", 3.0)));
        List<List<Double>> series = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> SigmaFilter.open(rows, "AVG for probe", true));
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof MultiChart && window.isVisible()
                            && ((MultiChart) window).chartTitle.equals("AVG for probe")) {
                        for (int i = 0; i < ((MultiChart) window).dataset.getSeriesCount(); i++) {
                            List<Double> values = new ArrayList<>();
                            for (int j = 0; j < ((MultiChart) window).dataset.getItemCount(i); j++) {
                                values.add(((MultiChart) window).dataset.getYValue(i, j));
                            }
                            series.add(values);
                        }
                    }
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
            });
        }

        double sigma = Math.sqrt(2.0);
        assertEquals(List.of(List.of(2.0, 2.0, 2.0), List.of(2.0 + sigma, 2.0 + sigma, 2.0 + sigma),
                List.of(2.0 - sigma, 2.0 - sigma, 2.0 - sigma)), series);
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
