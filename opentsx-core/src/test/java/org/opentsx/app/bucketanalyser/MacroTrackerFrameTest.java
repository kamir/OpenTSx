package org.opentsx.app.bucketanalyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MacroTrackerFrameTest {

    @Test
    void recordingATransformationPrintsNothing() {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
        } finally {
            System.setOut(stdout);
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recordingATransformationInTheOpenTrackerPrintsNothing() throws Exception {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.init("probe");
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
                MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("b", "c", "NORMALIZE"));
            });
        } finally {
            System.setOut(stdout);
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.frame.dispose();
                MacroTrackerFrame.frame = null;
            });
            PFDRecorder.getInstance().reset("New Flow");
        }

        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void addingASourcePrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> MacroTrackerFrame.addSource(TSBucketSource.getSource("probe")));

        assertEquals("", printed);
    }

    @Test
    void selectingANodePrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> {
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
            JTree tree = (JTree) field("jTree1");
            tree.setSelectionRow(tree.getRowCount() - 1);
        });

        assertEquals("", printed);
    }

    @Test
    void exportingJavaCodeCopiesItAndPrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> {
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
            click("jButton2ActionPerformed");
        });

        assertEquals("", printed);
        assertEquals("TSBucket tsbb = tsba.processBucket( \"DFA\", null );\n", clipboard());
    }

    @Test
    void exportingScalaCodeCopiesTheScalaCodeAndPrintsNothing() throws Exception {
        String printed = inOpenTracker(() -> {
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "DFA"));
            click("jButton1ActionPerformed");
        });

        assertEquals("", printed);
        assertEquals("val rddb = rdda.processBucket( \"DFA\", null );\n", clipboard());
    }

    @Test
    void showingAChartPrintsNothing() throws Exception {
        Track track = new Track();
        JDialog[] dialog = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> dialog[0] = new JDialog());
        track._registerDialogToNode("probe", dialog[0]);
        boolean shown;
        String printed;
        try {
            printed = captured(() -> SwingUtilities.invokeAndWait(() -> track.showDialog("probe")));
            shown = dialog[0].isVisible();
        } finally {
            SwingUtilities.invokeAndWait(dialog[0]::dispose);
        }

        assertTrue(shown, "the chart was not shown");
        assertEquals("", printed);
    }

    @Test
    void anOperationThatEndsInAnOpeningParenthesisRecordsItsNodeAndAStepWithAnEmptyArgument() throws Exception {
        List<String> nodes = new ArrayList<>();
        JsonArray[] steps = new JsonArray[1];
        String printed = inOpenTracker(() -> {
            PFDRecorder.getInstance().reset("New Flow");
            MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "x("));
            nodes.addAll(MacroTrackerFrame.frame.track.nodes.keySet());
            steps[0] = JsonParser.parseString(PFDRecorder.getInstance().toJSON()).getAsJsonObject()
                    .getAsJsonArray("steps");
        });

        assertEquals(List.of("b"), nodes);
        assertEquals(1, steps[0].size());
        JsonObject step = steps[0].get(0).getAsJsonObject();
        assertEquals("x", step.get("operation").getAsString());
        assertEquals("", step.getAsJsonObject("params").get("arg").getAsString());
        assertEquals("", printed);
    }

    @Test
    void anOperationWhosePfdRecorderCannotStartLeavesTheTrackUnchanged(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("output.txt");
        Path missing = dir.resolve("missing.properties");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dopentsx.config.file=" + missing,
                "-cp", System.getProperty("surefire.test.class.path"),
                RecordWithoutConfiguration.class.getName()))
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        int status = builder.start().waitFor();
        String printed = Files.readString(output);

        assertEquals(0, status, printed);
        String refusal = "IllegalStateException " + missing + ": no such file; give an existing file\n";
        assertTrue(printed.contains("first " + refusal), printed);
        assertTrue(printed.contains("second " + refusal), printed);
        assertTrue(printed.contains("nodes []\n"), printed);
    }

    public static class RecordWithoutConfiguration {
        public static void main(String[] args) throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.init("probe");
                try {
                    record("first", TSBucketTransformation.getTransformation("a", "b", "DFA"));
                    record("second", TSBucketTransformation.getTransformation("b", "c", "NORMALIZE"));
                    System.out.println("nodes " + MacroTrackerFrame.frame.track.nodes.keySet());
                } finally {
                    MacroTrackerFrame.frame.dispose();
                }
            });
            System.exit(0);
        }

        private static void record(String call, TSBucketTransformation t) {
            try {
                MacroTrackerFrame.addTransformation(t);
                System.out.println(call + " recorded");
            } catch (Throwable e) {
                System.out.println(call + " " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }

    @Test
    void addingASourceWithoutAnOpenTrackerDoesNotThrow() {
        assertNull(MacroTrackerFrame.frame);

        assertDoesNotThrow(() -> MacroTrackerFrame.addSource(TSBucketSource.getSourceStockData("probe")));
    }

    @Test
    void showChartOnASourceNodeShowsTheMessage() throws Exception {
        ProbeTracker tracker = openProbeTracker();
        try {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.addSource(TSBucketSource.getSourceStockData("probe"));
                select(tracker, tracker.jTree().getRowCount() - 1);
                clickOn(tracker, "jButton3ActionPerformed");
            });
        } finally {
            closeProbeTracker();
        }

        assertEquals(List.of("show Chart: no chart for SourceTSB{type=extracted via SDL2; name=probe}; select an "
                + "operation node"), tracker.errors);
    }

    @Test
    void showChartWithoutASelectionDoesNothing() throws Exception {
        ProbeTracker tracker = openProbeTracker();
        try {
            SwingUtilities.invokeAndWait(() -> clickOn(tracker, "jButton3ActionPerformed"));
        } finally {
            closeProbeTracker();
        }

        assertEquals(List.of(), tracker.errors);
    }

    @Test
    void showChartOnAProfileNodeShowsItsChart() throws Exception {
        ProbeTracker tracker = openProbeTracker();
        boolean shown;
        try {
            SwingUtilities.invokeAndWait(() -> {
                clickOn(new TSOperationControlerPanel(rows(), chart(), "probe", null), "jButton7ActionPerformed");
                tracker.track.dialogs.get("Profile_probe").setVisible(false);
                select(tracker, tracker.jTree().getRowCount() - 1);
                clickOn(tracker, "jButton3ActionPerformed");
            });
            shown = tracker.track.dialogs.get("Profile_probe").isVisible();
        } finally {
            disposeCharts();
            closeProbeTracker();
        }

        assertTrue(shown, "the chart of the Profile node was not shown");
        assertEquals(List.of(), tracker.errors);
    }

    @Test
    void showChartOnTheNodesOfSigmaBandAndMfDfaShowsTheirCharts() throws Exception {
        Path created = Path.of("databuffer");
        for (String part : new String[]{"MFDFA", "MFDFA of probe"}) {
            if (Files.exists(created)) {
                created = created.resolve(part);
            }
        }
        ProbeTracker tracker = openProbeTracker();
        RecordingPanel panel = onEdt(() -> new RecordingPanel(sampled(512), "probe"));
        List<String> shown = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                clickOn(panel, "jButton16ActionPerformed");
                clickOn(panel, "jButton6ActionPerformed");
            });
            SwingUtilities.invokeAndWait(() -> {
                for (String[] node : new String[][]{{"AVG of ", "AVG for probe"},
                        {"MFDFA of ", "sampled F(s,q) _raw_0"}}) {
                    MultiChart chart = openChart(node[1]);
                    chart.setVisible(false);
                    selectOperation(tracker, node[0]);
                    clickOn(tracker, "jButton3ActionPerformed");
                    if (chart.isVisible()) {
                        shown.add(node[0]);
                    }
                }
            });
        } finally {
            disposeCharts();
            closeProbeTracker();
            delete(created);
        }

        assertEquals(List.of(), panel.errors);
        assertEquals(List.of(), tracker.errors);
        assertEquals(List.of("AVG of ", "MFDFA of "), shown);
    }

    @Test
    void showChartOnTheMfDfaNodeOfTwoSeriesWithOneLabelShowsTheChartOfTheFirstSeries() throws Exception {
        Path created = Path.of("databuffer");
        for (String part : new String[]{"MFDFA", "MFDFA of probe"}) {
            if (Files.exists(created)) {
                created = created.resolve(part);
            }
        }
        Vector<TimeSeriesObject> rows = sampled(512);
        Random random = new Random(5);
        TimeSeriesObject louder = new TimeSeriesObject();
        louder.setLabel("sampled");
        for (int i = 0; i < 512; i++) {
            louder.addValuePair(i * 0.25, 1e6 * random.nextGaussian());
        }
        rows.add(louder);
        ProbeTracker tracker = openProbeTracker();
        RecordingPanel panel = onEdt(() -> new RecordingPanel(rows, "probe"));
        List<MultiChart> raw = new ArrayList<>();
        List<Double> shown = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> clickOn(panel, "jButton6ActionPerformed"));
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof MultiChart && window.isVisible()
                            && ((MultiChart) window).chartTitle.equals("sampled F(s,q) _raw_0")) {
                        raw.add((MultiChart) window);
                        window.setVisible(false);
                    }
                }
                selectOperation(tracker, "MFDFA of ");
                clickOn(tracker, "jButton3ActionPerformed");
                for (MultiChart chart : raw) {
                    if (chart.isVisible()) {
                        shown.add(chart.dataset.getSeries(0).getMaxY());
                    }
                }
            });
        } finally {
            disposeCharts();
            closeProbeTracker();
            delete(created);
        }

        assertEquals(List.of(), panel.errors);
        assertEquals(List.of(), tracker.errors);
        assertEquals(2, raw.size());
        assertEquals(1, shown.size(), "show Chart opened " + shown.size() + " of the two raw charts");
        assertTrue(shown.get(0) < 3, "show Chart opened the chart of the second series, log F(s) up to " + shown.get(0));
    }

    @Test
    void showChartOnTheNodesOfDfaAndFftShowsTheirCharts() throws Exception {
        ProbeTracker tracker = openProbeTracker();
        RecordingPanel panel = onEdt(() -> new RecordingPanel(sampled(1024), "probe"));
        List<String> shown = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                clickOn(panel, "jButton5ActionPerformed");
                clickOn(panel, "jButton3ActionPerformed");
                for (String[] node : new String[][]{{"DFA of ", "DFA of probe"}, {"FFT of ", "FFT of probe"}}) {
                    MultiChart chart = openChart(node[1]);
                    chart.setVisible(false);
                    selectOperation(tracker, node[0]);
                    clickOn(tracker, "jButton3ActionPerformed");
                    if (chart.isVisible()) {
                        shown.add(node[0]);
                    }
                }
            });
        } finally {
            disposeCharts();
            closeProbeTracker();
        }

        assertEquals(List.of(), panel.errors);
        assertEquals(List.of(), tracker.errors);
        assertEquals(List.of("DFA of ", "FFT of "), shown);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jButton4ActionPerformed | 'AC of ' | | AUTO-CORRELATION of probe",
            "jButton8ActionPerformed | 'Diff of ' | | Diff of probe",
            "jButton9ActionPerformed | 'LogRETURN of ' | | LogRETURN of probe",
            "jButton10ActionPerformed | 'PDF of ' | | y-value distributions of probe",
            "jButton12ActionPerformed | 'Norm MAX of ' | | NORM_MAX=1 of probe",
            "jButton13ActionPerformed | 'Standardized of ' | | NORM_STD=1, AVG=0 of probe",
            "jButton19ActionPerformed | 'CDF of ' | | y-value distributions of probe",
            "jButton20ActionPerformed | PDF (cross) | 8 | y-value distributions (tims slice l=8 of probe",
            "jButton21ActionPerformed | PDF (SW_pvalues) | | SW t-value distributions of probe",
            "jButton22ActionPerformed | 'ln of ' | | ln of probe"})
    void showChartOnTheNodeOfAnOperationShowsItsHiddenChartAgain(String action, String operation, String answer,
            String title) throws Exception {
        ProbeTracker tracker = openProbeTracker();
        RecordingPanel panel = onEdt(() -> new RecordingPanel(positive(128), "probe"));
        panel.answer = answer;
        boolean shown;
        try {
            SwingUtilities.invokeAndWait(() -> clickOn(panel, action));
            MultiChart[] chart = new MultiChart[1];
            SwingUtilities.invokeAndWait(() -> {
                chart[0] = openChart(title);
                chart[0].setVisible(false);
                selectOperation(tracker, operation);
                clickOn(tracker, "jButton3ActionPerformed");
            });
            shown = chart[0].isVisible();
        } finally {
            disposeCharts();
            closeProbeTracker();
        }

        assertEquals(List.of(), panel.errors);
        assertEquals(List.of(), tracker.errors);
        assertTrue(shown, "show Chart did not show the chart " + title + " of the node " + operation);
    }

    @Test
    void showChartOnTwoHistogramsWithOtherBinsShowsTheChartOfEachRun() throws Exception {
        List<List<Boolean>> shown = showChartOnTwoRuns("jButton10ActionPerformed", "PDF of ",
                "y-value distributions of probe", new String[]{"50", "10"}, (panel, bins) -> panel.bins(bins));

        assertEquals(List.of(List.of(true, false), List.of(false, true)), shown);
    }

    @Test
    void showChartOnTwoEntropiesWithOtherLengthsShowsTheChartOfEachRun() throws Exception {
        List<List<Boolean>> shown = showChartOnTwoRuns("jButton14ActionPerformed", "H of ", "H_probe",
                new String[]{"8", "16"}, (panel, length) -> panel.answer = length);

        assertEquals(List.of(List.of(true, false), List.of(false, true)), shown);
    }

    private static List<List<Boolean>> showChartOnTwoRuns(String action, String operation, String title,
            String[] settings, BiConsumer<RecordingPanel, String> set) throws Exception {
        ProbeTracker tracker = openProbeTracker();
        RecordingPanel panel = onEdt(() -> new RecordingPanel(positive(128), "probe"));
        List<MultiChart> charts = new ArrayList<>();
        List<List<Boolean>> shown = new ArrayList<>();
        try {
            for (String setting : settings) {
                SwingUtilities.invokeAndWait(() -> {
                    set.accept(panel, setting);
                    clickOn(panel, action);
                });
                SwingUtilities.invokeAndWait(() -> {
                    MultiChart chart = openChart(title);
                    chart.setVisible(false);
                    charts.add(chart);
                });
            }
            SwingUtilities.invokeAndWait(() -> {
                for (int run = 0; run < settings.length; run++) {
                    selectOperation(tracker, operation, run);
                    clickOn(tracker, "jButton3ActionPerformed");
                    shown.add(List.of(charts.get(0).isVisible(), charts.get(1).isVisible()));
                    charts.forEach(chart -> chart.setVisible(false));
                }
            });
        } finally {
            disposeCharts();
            closeProbeTracker();
        }

        assertEquals(List.of(), panel.errors);
        assertEquals(List.of(), tracker.errors);
        assertNotSame(charts.get(0), charts.get(1));
        return shown;
    }

    static class RecordingPanel extends TSOperationControlerPanel {
        final List<String> errors = new ArrayList<>();
        String answer;

        RecordingPanel(Vector<TimeSeriesObject> rows, String label) {
            super(rows, chart(), label, null);
        }

        void showActionError(String message) {
            errors.add(message);
        }

        void showFftError(String message) {
            errors.add(message);
        }

        String requestInput(String prompt) {
            return answer;
        }

        void bins(String bins) {
            try {
                var declared = TSOperationControlerPanel.class.getDeclaredField("jtBINS");
                declared.setAccessible(true);
                ((JTextField) declared.get(this)).setText(bins);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    static class ProbeTracker extends MacroTrackerFrame {
        final List<String> errors = new ArrayList<>();

        void showError(String message) {
            errors.add(message);
        }

        JTree jTree() {
            try {
                var declared = MacroTrackerFrame.class.getDeclaredField("jTree1");
                declared.setAccessible(true);
                return (JTree) declared.get(this);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static ProbeTracker openProbeTracker() throws Exception {
        ProbeTracker[] tracker = new ProbeTracker[1];
        SwingUtilities.invokeAndWait(() -> {
            tracker[0] = new ProbeTracker();
            tracker[0].track = new Track("probe", tracker[0].top);
            MacroTrackerFrame.frame = tracker[0];
        });
        return tracker[0];
    }

    private static void closeProbeTracker() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MacroTrackerFrame.frame.dispose();
            MacroTrackerFrame.frame = null;
        });
        PFDRecorder.getInstance().reset("New Flow");
    }

    private static void disposeCharts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof MacroTrackerFrame)) {
                    window.dispose();
                }
            }
        });
    }

    private static Vector<TimeSeriesObject> rows() {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("probe");
        for (int i = 0; i < 8; i++) {
            row.addValuePair(i, i % 3);
        }
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(row);
        return rows;
    }

    private static Vector<TimeSeriesObject> sampled(int n) {
        Random random = new Random(3);
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("sampled");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i * 0.25, random.nextGaussian());
        }
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(row);
        return rows;
    }

    private static Vector<TimeSeriesObject> positive(int n) {
        TimeSeriesObject row = new TimeSeriesObject();
        row.setLabel("positive");
        for (int i = 0; i < n; i++) {
            row.addValuePair(i, 2 + Math.sin(i * 0.3) + i % 7);
        }
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(row);
        return rows;
    }

    private static JFreeChart chart() {
        XYSeries series = new XYSeries("probe");
        series.add(0.0, 1.0);
        series.add(1.0, 2.0);
        return ChartFactory.createXYLineChart("probe", "x", "y", new XYSeriesCollection(series),
                PlotOrientation.VERTICAL, false, false, false);
    }

    private static MultiChart openChart(String titlePrefix) {
        MultiChart found = null;
        for (Window window : Window.getWindows()) {
            if (window instanceof MultiChart && window.isVisible()
                    && ((MultiChart) window).chartTitle.startsWith(titlePrefix)) {
                found = (MultiChart) window;
            }
        }
        assertNotNull(found, "no chart titled " + titlePrefix);
        return found;
    }

    private static void selectOperation(ProbeTracker tracker, String operation) {
        selectOperation(tracker, operation, 0);
    }

    private static void selectOperation(ProbeTracker tracker, String operation, int run) {
        JTree tree = tracker.jTree();
        int seen = 0;
        for (int row = 0; row < tree.getRowCount(); row++) {
            Object node = ((DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent()).getUserObject();
            if (node instanceof Transform && ((Transform) node).operation.equals(operation) && seen++ == run) {
                tree.setSelectionRow(row);
                return;
            }
        }
        fail("no node of run " + run + " of the operation " + operation);
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

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static void select(ProbeTracker tracker, int row) {
        tracker.jTree().setSelectionRow(row);
    }

    private static void clickOn(MacroTrackerFrame tracker, String action) {
        clickOn(MacroTrackerFrame.class, tracker, action);
    }

    private static void clickOn(TSOperationControlerPanel panel, String action) {
        clickOn(TSOperationControlerPanel.class, panel, action);
    }

    private static void clickOn(Class<?> type, Object component, String action) {
        try {
            var method = type.getDeclaredMethod(action, java.awt.event.ActionEvent.class);
            method.setAccessible(true);
            method.invoke(component, new Object[]{null});
        } catch (InvocationTargetException e) {
            fail(action + " threw", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    interface Step {
        void run() throws Exception;
    }

    private static String inOpenTracker(Step step) throws Exception {
        SwingUtilities.invokeAndWait(() -> MacroTrackerFrame.init("probe"));
        try {
            return captured(() -> SwingUtilities.invokeAndWait(() -> {
                try {
                    step.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                MacroTrackerFrame.frame.dispose();
                MacroTrackerFrame.frame = null;
            });
            PFDRecorder.getInstance().reset("New Flow");
        }
    }

    private static String captured(Step step) throws Exception {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(sink);
            System.setErr(sink);
            step.run();
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static Object field(String name) throws ReflectiveOperationException {
        var declared = MacroTrackerFrame.class.getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(MacroTrackerFrame.frame);
    }

    private static void click(String action) throws ReflectiveOperationException {
        var method = MacroTrackerFrame.class.getDeclaredMethod(action, java.awt.event.ActionEvent.class);
        method.setAccessible(true);
        try {
            method.invoke(MacroTrackerFrame.frame, new Object[]{null});
        } catch (InvocationTargetException e) {
            fail(action + " threw", e.getCause());
        }
    }

    private static String clipboard() throws Exception {
        return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    }
}
