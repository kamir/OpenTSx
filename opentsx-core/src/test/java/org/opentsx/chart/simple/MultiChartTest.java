package org.opentsx.chart.simple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class MultiChartTest {

    @TempDir
    Path dir;

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

    @Test
    void storingAChartUnderAFileFailsNamingTheImage() throws Exception {
        File blocker = Files.writeString(dir.resolve("blocker"), "x").toFile();
        MultiChart chart = chart("blocked");
        try {
            UncheckedIOException e = assertThrows(UncheckedIOException.class,
                    () -> chart.store(chart.chart, blocker, "probe"));

            File image = new File(blocker.getAbsolutePath() + File.separator + "probe.png");
            assertEquals(image + ": cannot write the chart image (" + e.getCause() + ")", e.getMessage());
        } finally {
            SwingUtilities.invokeAndWait(chart::dispose);
        }
    }

    @Test
    void storingAChartWritesThePngWithoutPrintingOrSleeping() throws Exception {
        MultiChart chart = chart("stored");
        // The first drawing of a chart in a JVM clears an interrupt; draw once so that only the store meets it.
        chart.chart.createBufferedImage(600, 400);
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean stillInterrupted;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Thread.currentThread().interrupt();
            chart.store(chart.chart, dir.toFile(), "probe");
        } finally {
            stillInterrupted = Thread.interrupted();
            System.setOut(stdout);
            SwingUtilities.invokeAndWait(chart::dispose);
        }

        assertTrue(stillInterrupted, "storing the chart slept and cleared the interrupt");
        assertTrue(Files.size(dir.resolve("probe.png")) > 0);
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void storingSeriesWritesTheImageAndTheTableAndPrintsNothing() throws Exception {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("probe");
        row.addValuePair(0.0, 1.0);
        row.addValuePair(1.0, 2.0);
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(row);
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(() -> MultiChart.store(rows, "probe", "x", "y", false, dir.toString(), "probe",
                    "no comment"));
        } finally {
            System.setOut(stdout);
        }

        assertTrue(Files.size(dir.resolve("probe.png")) > 0);
        assertTrue(Files.size(dir.resolve("TAB_probe.dat")) > 0);
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    private static MultiChart chart(String label) throws Exception {
        MultiChart[] created = new MultiChart[1];
        SwingUtilities.invokeAndWait(() -> created[0] = new MultiChart(label));
        return created[0];
    }
}
