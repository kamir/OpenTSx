package org.opentsx.chart.simple;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeriesCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.data.series.TimeSeriesObject;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Window;
import java.awt.image.BufferedImage;
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
    void storingAnotherChartWritesThatChart() throws Exception {
        MultiChart dialog = chart("own");
        JFreeChart other = ChartFactory.createXYLineChart("other", "x", "y", new XYSeriesCollection(),
                PlotOrientation.VERTICAL, false, false, false);
        other.setBackgroundPaint(Color.RED);
        try {
            dialog.store(other, dir.toFile(), "other");
        } finally {
            SwingUtilities.invokeAndWait(dialog::dispose);
        }

        BufferedImage image = ImageIO.read(dir.resolve("other.png").toFile());
        assertEquals(Color.RED.getRGB(), image.getRGB(1, 1));
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

    @Test
    void storingSeriesCreatesTheFolder() throws Exception {
        Path folder = dir.resolve("opentsx/plots");

        SwingUtilities.invokeAndWait(() -> MultiChart.store(rows(), "probe", "x", "y", false, folder.toString(),
                "probe", "no comment"));

        assertTrue(Files.size(folder.resolve("probe.png")) > 0);
        assertTrue(Files.size(folder.resolve("TAB_probe.dat")) > 0);
    }

    @Test
    void openingAndStoringSeriesCreatesTheFolder() throws Exception {
        Path folder = dir.resolve("opentsx/plots");
        try {
            SwingUtilities.invokeAndWait(() -> MultiChart.openAndStore(rows(), "probe", "x", "y", false,
                    folder.toString(), "probe", "no comment"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
            });
        }

        assertTrue(Files.size(folder.resolve("probe.png")) > 0);
        assertTrue(Files.size(folder.resolve("probe.mwt.data")) > 0);
    }

    private static Vector<TimeSeriesObject> rows() {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("probe");
        row.addValuePair(0.0, 1.0);
        row.addValuePair(1.0, 2.0);
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(row);
        return rows;
    }

    private static MultiChart chart(String label) throws Exception {
        MultiChart[] created = new MultiChart[1];
        SwingUtilities.invokeAndWait(() -> created[0] = new MultiChart(label));
        return created[0];
    }
}
