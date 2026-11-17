package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelSeriesHistogramTest {

    @TempDir
    Path home;

    TSOperationControlerPanel panel;
    RecordingTracker tracker;
    MacroTrackerFrame previousTracker;
    Object previousFlowState;
    String previousFlow;
    String previousHome;
    List<Window> previousWindows;
    final List<String> errors = new ArrayList<>();
    final List<String> titles = new ArrayList<>();
    final List<Integer> messageTypes = new ArrayList<>();
    final AWTEventListener dialogs = this::closeErrorDialog;

    @BeforeEach
    void createPanelAndRecorder() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        SwingUtilities.invokeAndWait(() -> {
            previousWindows = Arrays.asList(Window.getWindows());
            previousTracker = MacroTrackerFrame.frame;
            tracker = new RecordingTracker();
            tracker.track = new Track("Series histogram test", tracker.top);
            MacroTrackerFrame.frame = tracker;
            try {
                var flow = PFDRecorder.class.getDeclaredField("currentFlow");
                flow.setAccessible(true);
                previousFlowState = flow.get(PFDRecorder.getInstance());
                PFDRecorder.getInstance().reset("Series histogram test");
                previousFlow = PFDRecorder.getInstance().toJSON();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            panel = new TSOperationControlerPanel();
            panel.label = "Selected series";
            panel.rows = new Vector<>();
            panel.rows.add(series("First series", 0, 0, 1, 2));
            field("jtMIN", JTextField.class).setText("0");
            field("jtMAX", JTextField.class).setText("2");
            field("jtBINS", JTextField.class).setText("4");
            Toolkit.getDefaultToolkit().addAWTEventListener(dialogs, AWTEvent.WINDOW_EVENT_MASK);
        });
    }

    @AfterEach
    void restoreDesktopAndRecorderState() throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                Toolkit.getDefaultToolkit().removeAWTEventListener(dialogs);
                for (Window window : Window.getWindows()) {
                    if (!previousWindows.contains(window)) window.dispose();
                }
                MacroTrackerFrame.frame = previousTracker;
                try {
                    var flow = PFDRecorder.class.getDeclaredField("currentFlow");
                    flow.setAccessible(true);
                    flow.set(PFDRecorder.getInstance(), previousFlowState);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty"})
    void emptySelectionsLeaveChartsAndRecordersUnchanged(String kind) throws Exception {
        panel.rows.clear();
        if (kind.equals("null")) panel.rows = null;
        clickHistogram();
        assertError("Select at least one series for a histogram");
        assertRejected();
    }

    @ParameterizedTest
    @CsvSource({"jtMIN,''", "jtMIN,text", "jtMIN,1.5", "jtMIN,2", "jtMIN,3",
            "jtMIN,-2147483649", "jtMAX,2147483648", "jtMAX,NaN", "jtMAX,' 2 '"})
    void invalidBoundsLeaveInputChartsAndRecordersUnchanged(String name, String value) throws Exception {
        setField(name, value);
        List<Object> original = snapshotRows();
        clickHistogram();
        assertError("Histogram bounds must be integers with minimum less than maximum");
        assertRejected();
        assertEquals(original, snapshotRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text", "1.5", "0", "-1", "2147483648", " 4 "})
    void invalidBinCountsLeaveInputChartsAndRecordersUnchanged(String value) throws Exception {
        setField("jtBINS", value);
        List<Object> original = snapshotRows();
        clickHistogram();
        assertError("Histogram bin count must be a positive integer");
        assertRejected();
        assertEquals(original, snapshotRows());
    }

    @Test
    void rejectedInputPreservesExistingHistoryAndAValidRetryAppendsOneEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> MacroTrackerFrame.addTransformation(
                TSBucketTransformation.getTransformation("Previous input", "Previous output", "Previous operation")));
        String seededFlow = PFDRecorder.getInstance().toJSON();
        var previousNode = tracker.track.nodes.get("Previous output");
        List<Object> original = snapshotRows();
        setField("jtBINS", "0");
        clickHistogram();
        assertError("Histogram bin count must be a positive integer");
        assertTrue(charts().isEmpty());
        assertEquals(seededFlow, PFDRecorder.getInstance().toJSON());
        assertEquals(1, tracker.track.nodes.size());
        assertEquals(1, tracker.track.trackNode.getChildCount());
        assertSame(previousNode, tracker.track.nodes.get("Previous output"));
        assertSame(previousNode, tracker.track.trackNode.getChildAt(0));
        assertEquals(original, snapshotRows());

        errors.clear();
        titles.clear();
        messageTypes.clear();
        setField("jtBINS", "4");
        clickHistogram();
        assertTrue(errors.isEmpty());
        List<MultiChart> results = charts();
        assertEquals(1, results.size());
        MultiChart result = results.get(0);
        assertEquals("y-value distributions of Selected series", result.chartTitle);
        assertEquals("y-value distributions of Selected series", result.chart.getTitle().getText());
        assertEquals(1, result.dataset.getSeriesCount());
        assertSeries(result, 0, "First series_First series values", new double[]{0, 1, 2}, new double[]{2, 1, 1});
        assertTrue(tracker.chartPresentWhenRecorded);
        assertEquals(2, tracker.track.nodes.size());
        assertEquals(2, tracker.track.trackNode.getChildCount());
        assertSame(previousNode, tracker.track.nodes.get("Previous output"));
        assertSame(previousNode, tracker.track.trackNode.getChildAt(0));
        assertNotNull(tracker.track.nodes.get("PDF_Selected series"));
        var gson = new com.google.gson.Gson();
        var previous = gson.fromJson(seededFlow, PFDRecorder.ProcessingFlowDescriptor.class);
        var recorded = gson.fromJson(PFDRecorder.getInstance().toJSON(), PFDRecorder.ProcessingFlowDescriptor.class);
        assertEquals(2, recorded.steps.size());
        assertEquals(gson.toJson(previous.steps.get(0)), gson.toJson(recorded.steps.get(0)));
        assertEquals("PDF of ", recorded.steps.get(1).operation);
        assertEquals(original, snapshotRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null row", "null x", "null y", "missing x", "missing y", "empty"})
    void invalidSecondSeriesLeavesNoPartialChartOrRecorderEntry(String kind) throws Exception {
        TimeSeriesObject invalid = series("Second series", 1, 2);
        switch (kind) {
            case "null row" -> invalid = null;
            case "null x" -> invalid.xValues = null;
            case "null y" -> invalid.yValues = null;
            case "missing x" -> invalid.xValues.remove(1);
            case "missing y" -> invalid.yValues.remove(1);
            case "empty" -> invalid = series("Second series");
            default -> throw new IllegalArgumentException(kind);
        }
        panel.rows.add(invalid);
        List<Object> original = snapshotRows();
        clickHistogram();
        assertError("Histogram requires at least one coordinate-value pair");
        assertRejected();
        assertEquals(original, snapshotRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null x", "null y", "nan y", "infinite x", "negative infinite y", "wrong x"})
    void malformedSecondSeriesLeavesNoPartialChartOrRecorderEntry(String kind) throws Exception {
        TimeSeriesObject invalid = series("Second series", 1, 2);
        switch (kind) {
            case "null x" -> invalid.xValues.set(1, null);
            case "null y" -> invalid.yValues.set(1, null);
            case "nan y" -> invalid.yValues.set(1, Double.NaN);
            case "infinite x" -> invalid.xValues.set(1, Double.POSITIVE_INFINITY);
            case "negative infinite y" -> invalid.yValues.set(1, Double.NEGATIVE_INFINITY);
            case "wrong x" -> invalid.xValues.set(1, Integer.valueOf(2));
            default -> throw new IllegalArgumentException(kind);
        }
        panel.rows.add(invalid);
        List<Object> original = snapshotRows();
        clickHistogram();
        assertError("Histogram requires finite coordinates and values");
        assertRejected();
        assertEquals(original, snapshotRows());
    }

    @Test
    void validSeriesKeepTheirLabelsRawCountsAndInputCoordinates() throws Exception {
        panel.rows.add(series("Second series", 1, 1, 2));
        List<Object> original = snapshotRows();
        clickHistogram();
        MultiChart result = assertSuccessfulChart(2);
        assertSeries(result, 0, "First series_First series values", new double[]{0, 1, 2}, new double[]{2, 1, 1});
        assertSeries(result, 1, "Second series_Second series values", new double[]{1, 2}, new double[]{2, 1});
        assertTrue(tracker.chartPresentWhenRecorded);
        assertEquals(original, snapshotRows());
    }

    @Test
    void aSingleFinitePairIsAValidHistogramInput() throws Exception {
        panel.rows.clear();
        panel.rows.add(series("Single series", 2));
        List<Object> original = snapshotRows();
        clickHistogram();
        MultiChart result = assertSuccessfulChart(1);
        assertSeries(result, 0, "Single series_Single series values", new double[]{2}, new double[]{1});
        assertEquals(original, snapshotRows());
    }

    @Test
    void oneBinRemainsValidWithItsExistingRawCountSemantics() throws Exception {
        panel.rows.clear();
        panel.rows.add(series("Zero series", 0, 0, 0));
        setField("jtBINS", "1");
        clickHistogram();
        assertSeries(assertSuccessfulChart(1), 0, "Zero series_Zero series values", new double[]{0}, new double[]{3});
    }

    @Test
    void defaultHistogramParametersKeepTheExistingBinThresholds() throws Exception {
        setField("jtMIN", "-10");
        setField("jtMAX", "10");
        setField("jtBINS", "50");
        List<Object> original = snapshotRows();
        clickHistogram();
        assertSeries(assertSuccessfulChart(1), 0, "First series_First series values",
                new double[]{0.12, 1.0, 2.32}, new double[]{2, 1, 1});
        assertEquals(original, snapshotRows());
    }

    @Test
    void negativeIntegerBoundsAreAccepted() throws Exception {
        panel.rows.clear();
        panel.rows.add(series("Signed series", -2, -1, 0, 1, 2));
        setField("jtMIN", "-2");
        List<Object> original = snapshotRows();
        clickHistogram();
        assertSeries(assertSuccessfulChart(1), 0, "Signed series_Signed series values",
                new double[]{-2, -0.5, 1, 2.5}, new double[]{1, 1, 2, 1});
        assertEquals(original, snapshotRows());
    }

    private void clickHistogram() throws Exception {
        SwingUtilities.invokeAndWait(() -> field("jButton10", JButton.class).doClick(0));
    }

    private void setField(String name, String value) throws Exception {
        SwingUtilities.invokeAndWait(() -> field(name, JTextField.class).setText(value));
    }

    private void assertError(String message) {
        assertEquals(List.of(message), errors);
        assertEquals(List.of("Histogram failed"), titles);
        assertEquals(List.of(JOptionPane.ERROR_MESSAGE), messageTypes);
    }

    private void assertRejected() {
        assertTrue(charts().isEmpty());
        assertEquals(0, tracker.track.trackNode.getChildCount());
        assertTrue(tracker.track.nodes.isEmpty());
        assertEquals(previousFlow, PFDRecorder.getInstance().toJSON());
    }

    private MultiChart assertSuccessfulChart(int count) {
        assertTrue(errors.isEmpty());
        List<MultiChart> results = charts();
        assertEquals(1, results.size());
        MultiChart result = results.get(0);
        assertEquals("y-value distributions of Selected series", result.chartTitle);
        assertEquals("y-value distributions of Selected series", result.chart.getTitle().getText());
        assertEquals(count, result.dataset.getSeriesCount());
        assertEquals(1, tracker.track.trackNode.getChildCount());
        assertEquals(1, tracker.track.nodes.size());
        assertEquals("PDF_Selected series", tracker.track.nodes.keys().nextElement());
        var recorded = new com.google.gson.Gson().fromJson(PFDRecorder.getInstance().toJSON(),
                PFDRecorder.ProcessingFlowDescriptor.class);
        assertEquals(1, recorded.steps.size());
        assertEquals("PDF of ", recorded.steps.get(0).operation);
        return result;
    }

    private void assertSeries(MultiChart chart, int index, String label, double[] coordinates, double[] counts) {
        assertEquals(label, chart.dataset.getSeriesKey(index));
        assertEquals(coordinates.length, chart.dataset.getItemCount(index));
        for (int item = 0; item < coordinates.length; item++) {
            assertEquals(coordinates[item], chart.dataset.getXValue(index, item), 1e-12);
            assertEquals(counts[item], chart.dataset.getYValue(index, item));
        }
    }

    private List<Object> snapshotRows() {
        List<Object> snapshot = new ArrayList<>();
        for (TimeSeriesObject row : panel.rows) {
            if (row == null) {
                snapshot.add(null);
            } else {
                snapshot.add(List.of(row.getLabel(), row.getLabel_X(), row.getLabel_Y()));
                snapshot.add(row.xValues == null ? null : new ArrayList<>(row.xValues));
                snapshot.add(row.yValues == null ? null : new ArrayList<>(row.yValues));
            }
        }
        return snapshot;
    }

    private List<MultiChart> charts() {
        return Arrays.stream(Window.getWindows())
                .filter(window -> !previousWindows.contains(window))
                .filter(MultiChart.class::isInstance).map(MultiChart.class::cast).toList();
    }

    private void closeErrorDialog(AWTEvent event) {
        if (event.getID() != WindowEvent.WINDOW_OPENED) return;
        JOptionPane pane = findOptionPane((Container) event.getSource());
        if (pane == null) return;
        errors.add(String.valueOf(pane.getMessage()));
        titles.add(((JDialog) event.getSource()).getTitle());
        messageTypes.add(pane.getMessageType());
        pane.setValue(JOptionPane.OK_OPTION);
    }

    private JOptionPane findOptionPane(Container parent) {
        if (parent instanceof JOptionPane pane) return pane;
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) {
                JOptionPane pane = findOptionPane(container);
                if (pane != null) return pane;
            }
        }
        return null;
    }

    private <T> T field(String name, Class<T> type) {
        try {
            var field = TSOperationControlerPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(panel));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private TimeSeriesObject series(String label, double... values) {
        double[] coordinates = {10.5, -2.0, 100.25, 3.0, 0.125};
        TimeSeriesObject row = new TimeSeriesObject(label);
        row.setLabel_X("Sample");
        row.setLabel_Y(label + " values");
        for (int i = 0; i < values.length; i++) row.addValuePair(coordinates[i], values[i]);
        return row;
    }

    private class RecordingTracker extends MacroTrackerFrame {
        boolean chartPresentWhenRecorded;

        @Override
        public void refresh() {
            chartPresentWhenRecorded = !charts().isEmpty();
            super.refresh();
        }
    }
}
