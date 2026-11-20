package org.opentsx.app.bucketanalyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
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
import java.util.Arrays;
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
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(new Unread(1024)), chart(), "probe"));
        boolean chartOpened;
        try {
            click(panel, "jButton5ActionPerformed");
        } finally {
            chartOpened = disposeWindows();
        }

        assertNotNull(panel.actionError, "the DFA action showed no error");
        assertTrue(panel.actionError.startsWith("DFA of unread: cannot compute the fluctuation function ("),
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
            "jButton14ActionPerformed | abc | length: \"abc\" is not allowed; expected a whole number from 4 to "
                    + "2147483647",
            "jButton14ActionPerformed | 0 | length: \"0\" is not allowed; expected a whole number from 4 to 2147483647",
            "jButton14ActionPerformed | 3 | length: \"3\" is not allowed; expected a whole number from 4 to 2147483647",
            "jButton14ActionPerformed | 3000000000 | length: \"3000000000\" is not allowed; expected a whole number "
                    + "from 4 to 2147483647",
            "jButton20ActionPerformed | abc | window length: \"abc\" is not allowed; expected a whole number from 1 to "
                    + "2147483647",
            "jButton20ActionPerformed | 0 | window length: \"0\" is not allowed; expected a whole number from 1 to "
                    + "2147483647",
            "jButton20ActionPerformed | 3000000000 | window length: \"3000000000\" is not allowed; expected a whole "
                    + "number from 1 to 2147483647"})
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
            "jButton10ActionPerformed | jtMIN | x | min: \"x\" is not allowed; expected a whole number from "
                    + "-2147483648 to 2147483647",
            "jButton10ActionPerformed | jtMIN | -3000000000 | min: \"-3000000000\" is not allowed; expected a whole "
                    + "number from -2147483648 to 2147483647",
            "jButton10ActionPerformed | jtMAX | 1.5 | max: \"1.5\" is not allowed; expected a whole number from "
                    + "-2147483648 to 2147483647",
            "jButton10ActionPerformed | jtMAX | 3000000000 | max: \"3000000000\" is not allowed; expected a whole "
                    + "number from -2147483648 to 2147483647",
            "jButton10ActionPerformed | jtBINS | abc | bins: \"abc\" is not allowed; expected a whole number from 1 to "
                    + "2147483647",
            "jButton10ActionPerformed | jtBINS | 0 | bins: \"0\" is not allowed; expected a whole number from 1 to "
                    + "2147483647",
            "jButton10ActionPerformed | jtBINS | 3000000000 | bins: \"3000000000\" is not allowed; expected a whole "
                    + "number from 1 to 2147483647",
            "jButton20ActionPerformed | jtMIN | x | min: \"x\" is not allowed; expected a whole number from "
                    + "-2147483648 to 2147483647",
            "jButton20ActionPerformed | jtBINS | -1 | bins: \"-1\" is not allowed; expected a whole number from 1 to "
                    + "2147483647"})
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
    void aHistogramOpensItsChartAndRecordsIt() throws Exception {
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jButton4ActionPerformed | 64 | | auto-correlation | AC_wave",
            "jButton7ActionPerformed | 64 | | profile | wave_Profil",
            "jButton8ActionPerformed | 64 | | diffs | wave_Returns",
            "jButton9ActionPerformed | 64 | | returns | wave_Log(RelDiff)",
            "jButton10ActionPerformed | 64 | | histogram | wave_y",
            "jButton12ActionPerformed | 64 | | max-normalized series | wave (max=<max>)",
            "jButton13ActionPerformed | 64 | | standardized series | wave (stdv=1.0;mw=0.0)",
            "jButton14ActionPerformed | 64 | 8 | entropy | waveH(l=8)",
            "jButton22ActionPerformed | 64 | | ln | wave_ln",
            "jButton19ActionPerformed | 64 | | CDF | wave_CDF",
            "jButton18ActionPerformed | 64 | 3 | shuffle | wave (s3)"})
    void anOperationWhoseChartCannotOpenShowsTheMessageAndLeavesTheMacroTrackUnchanged(String action, int values,
            String answer, String operation, String key) throws Exception {
        Vector<TimeSeriesObject> twins = rows(wave(values));
        twins.add(wave(values));
        String max = String.valueOf(Arrays.stream(wave(values).getYData()).max().getAsDouble());
        ProbePanel panel = onEdt(() -> new ProbePanel(twins, chart(), "probe"));
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

        assertEquals(operation + " of probe: cannot open the chart (java.lang.IllegalArgumentException: This dataset "
                + "already contains a series with the key " + key.replace("<max>", max) + "); give each series its own "
                + "label", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, action + " opened a chart after it failed");
    }

    @Test
    void aDfaWhoseChartCannotOpenShowsTheMessageAndLeavesTheMacroTrackUnchanged() throws Exception {
        Vector<TimeSeriesObject> twins = rows(wave(1024));
        twins.add(wave(1024));
        ProbePanel panel = onEdt(() -> new ProbePanel(twins, chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton5ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertNotNull(panel.actionError, "the DFA action showed no error");
        assertTrue(panel.actionError.startsWith("DFA of probe: cannot open the chart (java.lang."
                + "IllegalArgumentException: This dataset already contains a series with the key wave_alpha="),
                panel.actionError);
        assertTrue(panel.actionError.endsWith("); give each series its own label"), panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the DFA action opened a chart after it failed");
    }

    @Test
    void theCdfOfTwoSeriesWithTheirOwnLabelsNamesEachSeriesAndRecordsIt() throws Exception {
        Vector<TimeSeriesObject> rows = rows(wave(64));
        TimeSeriesObject other = wave(64);
        other.setLabel("other");
        rows.add(other);
        ProbePanel panel = onEdt(() -> new ProbePanel(rows, chart(), "probe"));
        List<String> names = new ArrayList<>();
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton19ActionPerformed");
            recorded = recorded();
            assertNull(panel.actionError, panel.actionError);
            for (Object series : openChart("y-value distributions of probe").dataset.getSeries()) {
                names.add(((XYSeries) series).getKey().toString());
            }
        } finally {
            disposeWindows();
            closeTracker();
        }

        assertEquals(List.of("wave_CDF", "other_CDF"), names);
        assertEquals(Set.of("CDF_probe"), recorded);
    }

    @Test
    void aStandardizedChartThatCannotOpenShowsTheMessage() throws Exception {
        Vector<TimeSeriesObject> twins = rows(wave(64));
        twins.add(wave(64));
        ProbePanel panel = onEdt(() -> new ProbePanel(twins, chart(), "probe"));
        try {
            click(panel, "jButton13ActionPerformed");
        } finally {
            disposeWindows();
        }

        assertNotNull(panel.actionError, "the standardize action showed no error");
        assertTrue(panel.actionError.startsWith("standardized series of probe: cannot open the chart ("),
                panel.actionError);
    }

    @Test
    void aNormalityTestThatCannotTestASliceShowsTheMessageAndLeavesTheMacroTrackUnchanged() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(new Untestable()), chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton21ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals("SW test of untestable: cannot test the values 0 to 20 (java.io.IOException: the slice cannot "
                + "be tested)", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the normality test opened a chart after it failed");
    }

    @Test
    void aShuffleOfASeriesWhoseLabelEndsInAnOpeningParenthesisRecordsIt() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(), chart(), "x("));
        panel.answer = "3";
        Set<String> recorded;
        JsonArray steps;
        openTracker();
        PFDRecorder.getInstance().reset("New Flow");
        try {
            click(panel, "jButton18ActionPerformed");
            recorded = recorded();
            steps = JsonParser.parseString(PFDRecorder.getInstance().toJSON()).getAsJsonObject()
                    .getAsJsonArray("steps");
        } finally {
            disposeWindows();
            closeTracker();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(Set.of("Shuffle_x("), recorded);
        assertEquals(1, steps.size());
    }

    @Test
    void aFailingFftShowsTheMessageAndLeavesTheMacroTrackUnchanged() throws Exception {
        Vector<TimeSeriesObject> twins = rows(sampled(64));
        twins.add(sampled(64));
        ProbePanel panel = onEdt(() -> new ProbePanel(twins, chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton3ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals("FFT of probe: cannot open the chart (java.lang.IllegalArgumentException: This dataset already "
                + "contains a series with the key FFT : sampled); give each series its own label", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the FFT action opened a chart after it failed");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "constant | java.lang.IllegalArgumentException: Normalization requires a finite positive standard deviation",
            "NaN | java.lang.IllegalArgumentException: Normalization requires finite coordinates and values"})
    void aSeriesThatCannotBeStandardizedShowsTheMessageAndOpensNoChart(String kind, String cause) throws Exception {
        TimeSeriesObject row = kind.equals("constant") ? constant(64) : wave(64);
        if (kind.equals("NaN")) {
            row.yValues.set(5, Double.NaN);
            row.setLabel("gap");
        }
        Vector<TimeSeriesObject> rows = rows(wave(64));
        rows.add(row);
        ProbePanel panel = onEdt(() -> new ProbePanel(rows, chart(), "probe"));
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

        assertEquals("standardized series of " + row.getLabel() + ": cannot standardize the values (" + cause + "); "
                + "select series with at least two finite values that are not all equal", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the standardize action opened a chart after it failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"constant", "NaN"})
    void aSliceTheNormalityTestCannotEvaluateStillOpensBothChartsAndPrintsNothing(String kind) throws Exception {
        TimeSeriesObject row = kind.equals("constant") ? constant(240) : wave(240);
        if (kind.equals("NaN")) {
            row.yValues.set(5, Double.NaN);
        }
        double w = row.cutOut(0, 20).getSWTestResult()[0];
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(row), chart(), "probe"));
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            click(panel, "jButton21ActionPerformed");
            openChart("SW t-value distributions of probe");
            openChart("SW CDF of probe");
        } finally {
            System.setOut(stdout);
            disposeWindows();
        }

        assertEquals(kind.equals("constant") ? -1.0 : Double.NaN, w);
        assertNull(panel.actionError, panel.actionError);
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theNormalityTestOfASeriesOf100ValuesOpensBothCharts() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(100)), chart(), "probe"));
        List<String> names = new ArrayList<>();
        try {
            click(panel, "jButton21ActionPerformed");
            for (Object series : openChart("SW t-value distributions of probe").dataset.getSeries()) {
                names.add(((XYSeries) series).getKey().toString());
            }
            openChart("SW CDF of probe");
        } finally {
            disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(List.of("l=20", "l=40", "l=60", "l=80", "l=100"), names);
    }

    @Test
    void theHistogramCrossCutPrintsNothing() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(wave(64)), chart(), "probe"));
        panel.answer = "8";
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean chartOpened;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            click(panel, "jButton20ActionPerformed");
            openChart("y-value distributions (tims slice l=8 of probe");
        } finally {
            System.setOut(stdout);
            chartOpened = disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertTrue(chartOpened, "the histogram cross-cut opened no chart");
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anAutoCorrelationThatFailsShowsTheMessageAndOpensNoChart() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(new Unshiftable()), chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton4ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals("auto-correlation of unshiftable: cannot correlate lag 0 (java.lang.Exception: the series cannot "
                + "be shifted)", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the auto-correlation opened a chart after it failed");
    }

    @Test
    void anMfDfaThatFailsShowsTheMessageAndOpensNoChart() throws Exception {
        Path created = Paths.get("databuffer");
        for (String part : new String[]{"MFDFA", "MFDFA of probe"}) {
            if (Files.exists(created)) {
                created = created.resolve(part);
            }
        }
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(new Unreadable()), chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton6ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
            delete(created);
        }

        assertEquals("MFDFA of probe: cannot compute the analysis (java.lang.IllegalStateException: the values "
                + "cannot be read)", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the MF-DFA opened a chart after it failed");
    }

    @Test
    void showPnOnAPanelWithoutItsFolderShowsTheMessageAndOpensNothing() throws Exception {
        ProbePanel panel = panelBelow(home);
        File folder = home.resolve("opentsx/plots/default").toFile();

        click(panel, "jButton2ActionPerformed");

        assertEquals(folder + ": no such folder; press save PN to create it", panel.actionError);
        assertEquals(List.of(), panel.opened);
    }

    @Test
    void showPnThatCannotOpenTheFolderShowsTheRemedy() throws Exception {
        ProbePanel panel = panelBelow(home);
        File folder = Files.createDirectories(home.resolve("opentsx/plots/default")).toFile();
        panel.openFailure = new IOException("no file manager");

        click(panel, "jButton2ActionPerformed");

        assertEquals(List.of(folder), panel.opened);
        assertEquals(folder + ": cannot open the folder (java.io.IOException: no file manager); open it in a file "
                + "manager", panel.actionError);
    }

    @Test
    void showPnOnADesktopWithoutOpenSupportShowsTheRemedy() throws Exception {
        ProbePanel panel = panelBelow(home);
        File folder = Files.createDirectories(home.resolve("opentsx/plots/default")).toFile();
        panel.openFailure = new UnsupportedOperationException("open is not supported");

        click(panel, "jButton2ActionPerformed");

        assertEquals(List.of(folder), panel.opened);
        assertEquals(folder + ": cannot open the folder (java.lang.UnsupportedOperationException: open is not "
                + "supported); open it in a file manager", panel.actionError);
    }

    @Test
    void aDfaOfAShortSeriesFitsAFiniteAlpha() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(noise(1024)), chart(), "probe"));
        double alpha;
        try {
            click(panel, "jButton5ActionPerformed");
            MultiChart chart = openChart("DFA of probe");
            String key = chart.dataset.getSeries(0).getKey().toString();
            alpha = Double.parseDouble(key.split("_alpha=")[1]);
        } finally {
            disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(0.5, alpha, 0.1);
    }

    @ParameterizedTest
    @CsvSource({"100, 0", "143, 1"})
    void aDfaOfASeriesTooShortForTheFitShowsTheMessageAndRecordsNothing(int values, int windows) throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(noise(values)), chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton5ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals("DFA of noise: " + values + " values give " + windows + " window sizes in the fit range; use from "
                + "144 to 3160031 values", panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the DFA action opened a chart for a series too short for the fit");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "3160031 | DFA of unread: cannot compute the fluctuation function (java.lang.IllegalStateException: the "
                    + "values cannot be read)",
            "3160032 | DFA of unread: 3160032 values give 1 window sizes in the fit range; use from 144 to 3160031 "
                    + "values"})
    void aDfaCountsTheWindowSizesInTheFitRangeBeforeItReadsTheValues(int values, String message) throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(new Unread(values)), chart(), "probe"));
        boolean chartOpened;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton5ActionPerformed");
            recorded = recorded();
        } finally {
            chartOpened = disposeWindows();
            closeTracker();
        }

        assertEquals(message, panel.actionError);
        assertEquals(Set.of(), recorded);
        assertFalse(chartOpened, "the DFA action opened a chart after it stopped");
    }

    @Test
    void aDfaOf144ValuesFitsAFiniteAlphaAndRecordsIt() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(noise(144)), chart(), "probe"));
        double alpha;
        Set<String> recorded;
        openTracker();
        try {
            click(panel, "jButton5ActionPerformed");
            recorded = recorded();
            String key = openChart("DFA of probe").dataset.getSeries(0).getKey().toString();
            alpha = Double.parseDouble(key.split("_alpha=")[1]);
        } finally {
            disposeWindows();
            closeTracker();
        }

        assertNull(panel.actionError, panel.actionError);
        assertTrue(Double.isFinite(alpha), "alpha " + alpha);
        assertEquals(Set.of("DFA_2probe"), recorded);
    }

    @Test
    void aDfaOfALongSeriesUses250WindowSizes() throws Exception {
        ProbePanel panel = onEdt(() -> new ProbePanel(rows(noise(4096)), chart(), "probe"));
        int windows;
        try {
            click(panel, "jButton5ActionPerformed");
            windows = openChart("DFA of probe").dataset.getSeries(0).getItemCount();
        } finally {
            disposeWindows();
        }

        assertNull(panel.actionError, panel.actionError);
        assertEquals(250, windows);
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

    private static TimeSeriesObject constant(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("constant");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i, 1.0);
        }
        return row;
    }

    private ProbePanel panelBelow(Path userHome) throws Exception {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", userHome.toString());
            return onEdt(() -> new ProbePanel(rows(), chart(), "probe"));
        } finally {
            System.setProperty("user.home", original);
        }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
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

    static class Unshiftable extends TimeSeriesObject {
        Unshiftable() {
            setLabel("unshiftable");
            for (int i = 0; i < 8; i++) {
                addValuePair(i, i % 3);
            }
        }

        @Override
        public TimeSeriesObject shift(int offset) throws Exception {
            throw new Exception("the series cannot be shifted");
        }
    }

    static class Unreadable extends TimeSeriesObject {
        Unreadable() {
            setLabel("unreadable");
            for (int i = 0; i < 64; i++) {
                addValuePair(i, i % 3);
            }
        }

        @Override
        public double[][] getData() {
            throw new IllegalStateException("the values cannot be read");
        }
    }

    static class Unread extends TimeSeriesObject {
        Unread(int values) {
            setLabel("unread");
            Double zero = 0.0;
            for (int i = 0; i < values; i++) {
                xValues.add(zero);
                yValues.add(zero);
            }
        }

        @Override
        public double[][] getData() {
            throw new IllegalStateException("the values cannot be read");
        }
    }

    static class Untestable extends TimeSeriesObject {
        Untestable() {
            setLabel("untestable");
            for (int i = 0; i < 100; i++) {
                addValuePair(i, i % 3);
            }
        }

        @Override
        public TimeSeriesObject cutOut(int begin, int end) {
            TimeSeriesObject slice = new TimeSeriesObject() {
                @Override
                public double[] getSWTestResult() throws IOException {
                    throw new IOException("the slice cannot be tested");
                }
            };
            for (int i = begin; i < end; i++) {
                slice.addValuePair(i, i % 3);
            }
            return slice;
        }
    }

    static class ProbePanel extends TSOperationControlerPanel {
        String error;
        String actionError;
        String answer;
        Exception openFailure;
        final List<String> prompts = new ArrayList<>();
        final List<File> opened = new ArrayList<>();

        ProbePanel(Vector<TimeSeriesObject> rows, JFreeChart chart, String label) {
            super(rows, chart, label, null);
        }

        void openFolder(File folder) throws IOException {
            opened.add(folder);
            if (openFailure instanceof IOException) {
                throw (IOException) openFailure;
            }
            if (openFailure != null) {
                throw (RuntimeException) openFailure;
            }
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
