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
import org.opentsx.tsbucket.folderStore.TSBASE;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Window;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
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

    @Test
    void theDfaActionPrintsNothing() throws Exception {
        TSOperationControlerPanel.baseFolder = home.toString();
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(1024)), chart(), "probe"));
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            click(panel, "jButton5ActionPerformed");
        } finally {
            System.setOut(stdout);
            disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aDfaThatFailsShowsTheMessageAndOpensNoChart() throws Exception {
        TSOperationControlerPanel.baseFolder = home.toString();
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(3)), chart(), "probe"));
        boolean chartOpened;
        try {
            click(panel, "jButton5ActionPerformed");
        } finally {
            chartOpened = disposeWindows();
        }

        assertNotNull(panel.actionError, "the DFA action showed no error");
        assertTrue(panel.actionError.startsWith("DFA of wave: cannot compute the fluctuation function ("),
                panel.actionError);
        assertFalse(chartOpened, "the DFA action opened a chart after it failed");
    }

    @Test
    void cancellingTheShuffleDialogDoesNothing() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(), chart(), "probe"));
        panel.shuffleAnswer = null;
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(TSOperationControlerPanel.class.getName());
        List<String> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean chartOpened;
        jul.addHandler(handler);
        try {
            click(panel, "jButton18ActionPerformed");
        } finally {
            jul.removeHandler(handler);
            chartOpened = disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(List.of(), logged);
        assertFalse(chartOpened, "cancelling the shuffle opened a chart");
    }

    @Test
    void aShuffleCountThatIsNotANumberShowsTheMessageAndOpensNoChart() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(), chart(), "probe"));
        panel.shuffleAnswer = "x";
        boolean chartOpened;
        try {
            click(panel, "jButton18ActionPerformed");
        } finally {
            chartOpened = disposeWindows();
        }

        assertNotNull(panel.actionError, "the shuffle action showed no error");
        assertTrue(panel.actionError.startsWith(
                "shuffle of probe: cannot shuffle the series (java.lang.NumberFormatException"), panel.actionError);
        assertFalse(chartOpened, "the shuffle action opened a chart after it failed");
    }

    @Test
    void aBucketThatCannotBeWrittenFailsNamingIt() throws Exception {
        Path blocker = Files.writeString(home.resolve("blocker"), "x");
        TSOperationControlerPanel panel = onEdt(() -> new TSOperationControlerPanel(rows(), chart(), "probe", null));
        String base = TSBASE.BASE_PATH;
        try {
            TSBASE.BASE_PATH = blocker.toString();
            UncheckedIOException e = assertThrows(UncheckedIOException.class, panel::persistTSB);
            assertEquals(panel.track + "/probe: cannot write the bucket (" + e.getCause() + ")", e.getMessage());
        } finally {
            TSBASE.BASE_PATH = base;
        }
    }

    private static void click(TSOperationControlerPanel panel, String action) throws Exception {
        var method = TSOperationControlerPanel.class.getDeclaredMethod(action, java.awt.event.ActionEvent.class);
        method.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                method.invoke(panel, new Object[]{null});
            } catch (InvocationTargetException e) {
                fail(action + " threw instead of showing the error", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static boolean disposeWindows() throws Exception {
        boolean[] visible = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                visible[0] |= window.isVisible();
                window.dispose();
            }
        });
        return visible[0];
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
        return rows(row);
    }

    private static Vector<TimeSeriesObject> rows(TimeSeriesObject row) {
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(row);
        return rows;
    }

    private static TimeSeriesObject wave(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("wave");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i, Math.sin(i * 0.3) + i % 7);
        }
        return row;
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
        String actionError;
        String shuffleAnswer;

        ProbePanel(Vector<TimeSeriesObject> rows, JFreeChart chart, String label) {
            super(rows, chart, label, null);
        }

        void showSaveError(String message) {
            error = message;
        }

        void showActionError(String message) {
            actionError = message;
        }

        String requestShuffleRounds() {
            return shuffleAnswer;
        }
    }
}
