package org.opentsx.app.bucketanalyser;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.JDialog;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

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
    void aPfdStepThatCannotBeRecordedReachesTheCaller() throws Exception {
        RuntimeException[] thrown = new RuntimeException[1];
        String printed = inOpenTracker(() -> thrown[0] = assertThrows(IllegalStateException.class,
                () -> MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("a", "b", "x("))));

        assertEquals("x(: cannot record the PFD step (" + thrown[0].getCause() + ")", thrown[0].getMessage());
        assertEquals("", printed);
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
    void showChartOnAnOperationNodeWithoutItsChartNamesTheNodesThatHaveOne() throws Exception {
        ProbeTracker tracker = openProbeTracker();
        try {
            SwingUtilities.invokeAndWait(() -> {
                clickOn(new TSOperationControlerPanel(rows(), chart(), "probe", null), "jButton8ActionPerformed");
                select(tracker, tracker.jTree().getRowCount() - 1);
                clickOn(tracker, "jButton3ActionPerformed");
            });
        } finally {
            disposeCharts();
            closeProbeTracker();
        }

        assertEquals(List.of("show Chart: no chart for Transform{SIMPLE per Record Operation: Diff of }; select a "
                + "Profile, ENTROPY or Shuffle node"), tracker.errors);
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

    private static JFreeChart chart() {
        XYSeries series = new XYSeries("probe");
        series.add(0.0, 1.0);
        series.add(1.0, 2.0);
        return ChartFactory.createXYLineChart("probe", "x", "y", new XYSeriesCollection(series),
                PlotOrientation.VERTICAL, false, false, false);
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
