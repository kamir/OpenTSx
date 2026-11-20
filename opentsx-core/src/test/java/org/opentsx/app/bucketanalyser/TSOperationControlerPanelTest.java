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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.folderStore.TSBASE;

import javax.swing.JTextArea;
import javax.swing.JTextField;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
    void storingAChartCreatesTheFolder() throws Exception {
        TSOperationControlerPanel panel = onEdt(TSOperationControlerPanel::new);
        Path folder = home.resolve("opentsx/plots");

        panel.storeChartImageAsPNG(chart(), folder.toFile(), "probe");

        assertTrue(Files.size(folder.resolve("probe.png")) > 0);
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
        panel.answer = null;
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
        panel.answer = "x";
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
    void theShuffleDialogAsksForTheNumberOfShuffles() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(), chart(), "probe"));
        panel.answer = null;

        click(panel, "jButton18ActionPerformed");

        assertEquals(List.of("Number of shuffles: "), panel.prompts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jButton14ActionPerformed", "jButton20ActionPerformed"})
    void cancellingANumberDialogDoesNothing(String action) throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(64)), chart(), "probe"));
        panel.answer = null;
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, action);
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, action + " opened a chart after a cancelled dialog");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jButton14ActionPerformed | abc | length: \"abc\" is not allowed; expected a whole number of at least 4",
            "jButton14ActionPerformed | 0 | length: \"0\" is not allowed; expected a whole number of at least 4",
            "jButton14ActionPerformed | 3 | length: \"3\" is not allowed; expected a whole number of at least 4",
            "jButton20ActionPerformed | abc | window length: \"abc\" is not allowed; expected a whole number above 0",
            "jButton20ActionPerformed | 0 | window length: \"0\" is not allowed; expected a whole number above 0"})
    void aDialogAnswerThatIsNotAllowedShowsTheMessageAndOpensNoChart(String action, String answer, String message)
            throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(64)), chart(), "probe"));
        panel.answer = answer;
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, action);
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals(message, panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, action + " opened a chart after it failed");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jButton10ActionPerformed | jtMIN | x | min: \"x\" is not allowed; expected a whole number",
            "jButton10ActionPerformed | jtMAX | 1.5 | max: \"1.5\" is not allowed; expected a whole number",
            "jButton10ActionPerformed | jtBINS | abc | bins: \"abc\" is not allowed; expected a whole number above 0",
            "jButton10ActionPerformed | jtBINS | 0 | bins: \"0\" is not allowed; expected a whole number above 0",
            "jButton20ActionPerformed | jtMIN | x | min: \"x\" is not allowed; expected a whole number",
            "jButton20ActionPerformed | jtBINS | -1 | bins: \"-1\" is not allowed; expected a whole number above 0"})
    void aNumberFieldThatIsNotAllowedShowsTheMessageAndOpensNoChart(String action, String field, String value,
            String message) throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(64)), chart(), "probe"));
        panel.answer = "8";
        type(panel, field, value);
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, action);
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals(message, panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, action + " opened a chart after it failed");
    }

    @Test
    void aHistogramOpensItsChartAndThenRecordsIt() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(64)), chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton10ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(Set.of("PDF_probe"), recorded);
        assertTrue(chartOpened, "the histogram opened no chart");
    }

    @Test
    void aFailingOperationLeavesTheMacroTrackUnchanged() throws Exception {
        Vector<TimeSeriesObject> twins = rows(wave(64));
        twins.add(wave(64));
        ProbePanel panel = onEdt(() -> new ProbePanel(twins, chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton13ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertNotNull(panel.actionError, "the standardize action showed no error");
        assertTrue(panel.actionError.startsWith("standardized series of probe: cannot open the chart ("),
                panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the standardize action opened a chart after it failed");
    }

    @Test
    void aFailingFftLeavesTheMacroTrackUnchanged() throws Exception {
        Vector<TimeSeriesObject> twins = rows(sampled(64));
        twins.add(sampled(64));
        ProbePanel panel = onEdt(() -> new ProbePanel(twins, chart(), "probe"));
        var method = TSOperationControlerPanel.class.getDeclaredMethod("jButton3ActionPerformed",
                java.awt.event.ActionEvent.class);
        method.setAccessible(true);
        Throwable[] thrown = new Throwable[1];
        Set<String> recorded;
        openTracker();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    method.invoke(panel, new Object[]{null});
                } catch (InvocationTargetException e) {
                    thrown[0] = e.getCause();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            recorded = recorded();
        } finally {
            disposeWindows();
            closeTracker();
        }

        assertInstanceOf(IllegalArgumentException.class, thrown[0]);
        assertEquals("This dataset already contains a series with the key FFT : sampled", thrown[0].getMessage());
        assertEquals(Set.of(), recorded);
    }

    @Test
    void theDfaCommentCarriesTheAlphaOfEachSeries() throws Exception {
        TSOperationControlerPanel.baseFolder = home.toString();
        Vector<TimeSeriesObject> series = rows(noise(4096));
        series.add(walk(4096));
        ProbePanel panel = onEdt(() -> new ProbePanel(series, chart(), "probe"));
        List<String> expected = new ArrayList<>();
        String comment;
        try {
            click(panel, "jButton5ActionPerformed");
            MultiChart chart = openChart("DFA of probe");
            for (Object fitted : chart.dataset.getSeries()) {
                String[] key = ((org.jfree.data.xy.XYSeries) fitted).getKey().toString().split("_alpha=");
                expected.add(key[0] + "\t" + key[1]);
            }
            comment = chart.getStatisticTextField().getText();
        } finally {
            disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(2, expected.size());
        assertNotEquals(expected.get(0).split("\t")[1], expected.get(1).split("\t")[1]);
        assertEquals(expected, List.of(comment.split("\n")));
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
            Window[] windows = Window.getWindows();
            for (Window window : windows) {
                visible[0] |= window.isVisible() && !(window instanceof MacroTrackerFrame);
            }
            for (Window window : windows) {
                window.dispose();
            }
        });
        return visible[0];
    }

    private static void type(TSOperationControlerPanel panel, String field, String text) throws Exception {
        var declared = TSOperationControlerPanel.class.getDeclaredField(field);
        declared.setAccessible(true);
        JTextField input = (JTextField) declared.get(panel);
        SwingUtilities.invokeAndWait(() -> input.setText(text));
    }

    private static MultiChart openChart(String titlePrefix) throws Exception {
        MultiChart[] found = new MultiChart[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof MultiChart && window.isVisible()
                        && ((MultiChart) window).chartTitle.startsWith(titlePrefix)) {
                    found[0] = (MultiChart) window;
                }
            }
        });
        assertNotNull(found[0], "no chart titled " + titlePrefix);
        return found[0];
    }

    private static void openTracker() throws Exception {
        SwingUtilities.invokeAndWait(() -> MacroTrackerFrame.init("probe"));
    }

    private static Set<String> recorded() {
        return new HashSet<>(MacroTrackerFrame.frame.track.nodes.keySet());
    }

    private static void closeTracker() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MacroTrackerFrame.frame.dispose();
            MacroTrackerFrame.frame = null;
        });
        PFDRecorder.getInstance().reset("New Flow");
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

    private static TimeSeriesObject sampled(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("sampled");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i * 0.25, Math.sin(i * 0.3));
        }
        return row;
    }

    private static TimeSeriesObject noise(int n) {
        Random random = new Random(3);
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("noise");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i, random.nextGaussian());
        }
        return row;
    }

    private static TimeSeriesObject walk(int n) {
        Random random = new Random(7);
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("walk");
        double y = 0;
        for (int i = 0; i < n; i++) {
            y += random.nextGaussian();
            row.addValuePair(i, y);
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
        String answer;
        final List<String> prompts = new ArrayList<>();

        ProbePanel(Vector<TimeSeriesObject> rows, JFreeChart chart, String label) {
            super(rows, chart, label, null);
        }

        void showSaveError(String message) {
            error = message;
        }

        void showActionError(String message) {
            actionError = message;
        }

        String requestInput(String prompt) {
            prompts.add(prompt);
            return answer;
        }
    }
}
