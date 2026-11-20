package org.opentsx.app.bucketanalyser;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Vector;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TSOperationControlerPanelTest {

    @TempDir(factory = PlainName.class)
    Path home;

    String baseFolder;

    @BeforeEach
    void saveTheBaseFolder() {
        baseFolder = TSOperationControlerPanel.baseFolder;
    }

    @AfterEach
    void restoreTheBaseFolder() {
        TSOperationControlerPanel.baseFolder = baseFolder;
    }

    @Test
    void openingAPanelPrintsNothingAndCreatesNoFolder() throws Exception {
        String original = System.getProperty("user.home");
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setProperty("user.home", home.toString());
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(TSOperationControlerPanel::new);
        } finally {
            System.setOut(stdout);
            System.setProperty("user.home", original);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(home.resolve("opentsx")));
    }

    @Test
    void storingAChartUnderAFileFailsNamingTheImage() throws Exception {
        File blocker = Files.writeString(home.resolve("blocker"), "x").toFile();
        TSOperationControlerPanel panel = onEdt(TSOperationControlerPanel::new);

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> panel.storeChartImageAsPNG(chart(), blocker, "probe"));

        File image = new File(blocker.getAbsolutePath() + File.separator + "probe.png");
        assertEquals(image + ": cannot write the chart image (" + e.getCause() + ")", e.getMessage());
    }

    @Test
    void storingAChartWritesThePngWithoutPrintingOrSleeping() throws Exception {
        TSOperationControlerPanel panel = onEdt(TSOperationControlerPanel::new);
        JFreeChart chart = chart();
        // The first drawing of a chart in a JVM clears an interrupt; draw once so that only the store meets it.
        chart.createBufferedImage(800, 600);
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean stillInterrupted;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Thread.currentThread().interrupt();
            panel.storeChartImageAsPNG(chart, home.toFile(), "probe");
        } finally {
            stillInterrupted = Thread.interrupted();
            System.setOut(stdout);
        }

        assertTrue(stillInterrupted, "storing the chart slept and cleared the interrupt");
        assertTrue(Files.size(home.resolve("probe.png")) > 0);
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aCaptionThatCannotBeReadClosesItsFile() throws Exception {
        TSOperationControlerPanel.baseFolder = home.toString();
        FailingCaption caption = new FailingCaption();
        TSOperationControlerPanel panel = onEdt(() -> new TSOperationControlerPanel(rows(), chart(), "probe", caption));
        caption.fail = true;

        assertThrows(IllegalStateException.class, panel::save);

        Path file = home.resolve("default").resolve("caption_probe.tex");
        assertTrue(Files.exists(file));
        assertFalse(isOpen(file));
    }

    @Test
    void theSaveActionShowsAChartImageThatCannotBeWritten() throws Exception {
        TSOperationControlerPanel.baseFolder = home.toString();
        Path image = Files.createDirectories(home.resolve("default").resolve("probe.png"));
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(), chart(), "probe"));

        var save = TSOperationControlerPanel.class.getDeclaredMethod("jButton1ActionPerformed",
                java.awt.event.ActionEvent.class);
        save.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                save.invoke(panel, new Object[]{null});
            } catch (InvocationTargetException e) {
                fail("the save action threw instead of showing the error", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });

        assertNotNull(panel.error, "the save action showed no error");
        assertTrue(panel.error.startsWith(image.toFile().getAbsolutePath() + ": cannot write the chart image ("),
                panel.error);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> create) throws Exception {
        Object[] created = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                created[0] = create.call();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        @SuppressWarnings("unchecked")
        T result = (T) created[0];
        return result;
    }

    private static JFreeChart chart() {
        XYSeries series = new XYSeries("probe");
        series.add(0.0, 1.0);
        series.add(1.0, 2.0);
        return ChartFactory.createXYLineChart("probe", "x", "y", new XYSeriesCollection(series),
                PlotOrientation.VERTICAL, false, false, false);
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

    private static boolean isOpen(Path file) throws IOException {
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        Path real = file.toRealPath();
        try (Stream<Path> links = Files.list(fds)) {
            return links.anyMatch(fd -> real.equals(target(fd)));
        }
    }

    private static Path target(Path fd) {
        try {
            return fd.resolveSibling(Files.readSymbolicLink(fd));
        } catch (IOException gone) {
            return null;
        }
    }

    /**
     * The panel replaces every character of its folder but letters, digits, '.' and '/' with '_', so the '-' of the
     * default "junit-" prefix would move the files out of the temporary directory.
     */
    static class PlainName implements TempDirFactory {
        @Override
        public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
            return Files.createTempDirectory("junit");
        }
    }

    static class FailingCaption extends JTextArea {
        volatile boolean fail;

        @Override
        public String getText() {
            if (fail) {
                throw new IllegalStateException("the caption cannot be read");
            }
            return super.getText();
        }
    }

    static class ProbePanel extends TSOperationControlerPanel {
        String error;

        ProbePanel(Vector<TimeSeriesObject> rows, JFreeChart chart, String label) {
            super(rows, chart, label, null);
        }

        void showSaveError(String message) {
            error = message;
        }
    }
}
