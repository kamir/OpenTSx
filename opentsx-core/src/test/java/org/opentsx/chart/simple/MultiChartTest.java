package org.opentsx.chart.simple;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiChartTest {

    @Test
    void aChartWithPresetColorsPrintsNothing() throws Exception {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            MultiChart._initColors(new Color[]{Color.BLACK, Color.RED});
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(() -> new MultiChart("colors").dispose());
        } finally {
            System.setOut(stdout);
            MultiChart._initColors(null);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }
}
