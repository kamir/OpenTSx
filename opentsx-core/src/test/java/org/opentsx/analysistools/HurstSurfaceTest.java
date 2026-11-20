package org.opentsx.analysistools;

import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class HurstSurfaceTest {

    @Test
    void plottingASurfacePrintsNothing() throws Exception {
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(fluctuation("a", 0.5));
        rows.add(fluctuation("b", 1.5));
        HurstSurface[] surface = new HurstSurface[1];
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(sink);
            System.setErr(sink);
            SwingUtilities.invokeAndWait(() -> surface[0] = new HurstSurface(rows, "probe", 1));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
            SwingUtilities.invokeAndWait(() -> {
                if (surface[0] != null) {
                    surface[0].frame.dispose();
                }
            });
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
        assertEquals(20, surface[0].getImage().getWidth(null));
        assertEquals(2, surface[0].getImage().getHeight(null));
    }

    private static TimeSeriesObject fluctuation(String label, double alpha) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel(label);
        for (int i = 0; i <= 120; i++) {
            double logS = i * 0.05;
            row.addValuePair(logS, alpha * logS);
        }
        return row;
    }
}
