package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
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
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelMaximumNormalizationTest {

    @TempDir
    Path home;

    TSOperationControlerPanel panel;
    MacroTrackerFrame tracker;
    MacroTrackerFrame previousTracker;
    Object previousFlowState;
    String previousFlow;
    String previousHome;
    List<Window> previousWindows;
    int prompts;
    final List<Boolean> chartsVisibleWhenRecorded = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> titles = new ArrayList<>();
    final List<Integer> messageTypes = new ArrayList<>();
    final AWTEventListener dialogs = this::closeDialog;

    @BeforeEach
    void createPanelAndRecorder() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        SwingUtilities.invokeAndWait(() -> {
            previousWindows = Arrays.asList(Window.getWindows());
            previousTracker = MacroTrackerFrame.frame;
            tracker = new MacroTrackerFrame() {
                @Override
                public void refresh() {
                    chartsVisibleWhenRecorded.add(charts().stream().anyMatch(Window::isVisible));
                    super.refresh();
                }
            };
            tracker.track = new Track("Maximum normalization test", tracker.top);
            MacroTrackerFrame.frame = tracker;
            try {
                var flow = PFDRecorder.class.getDeclaredField("currentFlow");
                flow.setAccessible(true);
                previousFlowState = flow.get(PFDRecorder.getInstance());
                PFDRecorder.getInstance().reset("Maximum normalization test");
                previousFlow = PFDRecorder.getInstance().toJSON();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            panel = new TSOperationControlerPanel();
            panel.label = "Selected series";
            panel.rows = new Vector<>();
            panel.rows.add(firstSeries());
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
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty"})
    void emptySelectionsProduceAVisibleErrorWithoutChartsOrRecording(String kind) throws Exception {
        panel.rows.clear();
        if (kind.equals("null")) panel.rows = null;
        clickNormalization();
        assertError("Select at least one series for maximum normalization");
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null row", "empty", "null x", "null y", "missing x", "missing y",
            "nan x", "nan y", "infinite x", "infinite y", "negative infinite x", "negative infinite y",
            "null x cell", "null y cell", "wrong x", "wrong y", "all zero", "signed zero",
            "maximum zero", "all negative", "overflow"})
    void anInvalidLaterRowProducesNoPartialChartOrRecorderEntry(String kind) throws Exception {
        panel.rows.add(invalidSeries(kind));
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertError(expectedMessage(kind));
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null row", "nan x", "all negative", "overflow"})
    void anInvalidFirstRowProducesTheSameVisibleErrorAndLeavesAllInputsUnchanged(String kind) throws Exception {
        panel.rows.set(0, invalidSeries(kind));
        panel.rows.add(secondSeries());
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertError(expectedMessage(kind));
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @Test
    void aLaterFftSeriesWithANullLabelProducesNoPartialChartOrRecorderEntry() throws Exception {
        TimeSeriesObjectFFT row = new TimeSeriesObjectFFT(null);
        row.setLabel_X("Seconds");
        row.setLabel_Y("Amplitude");
        row.addValuePair(0.25, 2.0);
        row.addValuePair(4.5, 8.0);
        assertNull(row.getLabel());
        panel.rows.add(row);
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertError("Maximum normalization requires a series label");
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @Test
    void normalizesAnFftSeriesWithItsActualCoordinatesAndLabels() throws Exception {
        TimeSeriesObjectFFT row = new TimeSeriesObjectFFT("FFT series");
        row.setLabel_X("Seconds");
        row.setLabel_Y("Amplitude");
        row.addValuePair(0.25, 2.0);
        row.addValuePair(4.5, 8.0);
        panel.rows.clear();
        panel.rows.add(row);
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertSuccessfulChart(selected, List.of(List.of(0.25, 1.0)), List.of(8.0));
        assertSingleRecording();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalizesMultipleRowsWithTheirActualCoordinatesAndLabels(boolean reverseOrder) throws Exception {
        panel.rows.add(secondSeries());
        if (reverseOrder) Collections.reverse(panel.rows);
        List<List<Double>> expected = new ArrayList<>(List.of(List.of(1.0 / 3, 2.0 / 3, 1.0),
                List.of(5.0 / 7, 1.0)));
        List<Double> maxima = new ArrayList<>(List.of(6.0, 14.0));
        if (reverseOrder) {
            Collections.reverse(expected);
            Collections.reverse(maxima);
        }
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertSuccessfulChart(selected, expected, maxima);
        assertSingleRecording();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "constant", "mixed signs", "subnormal", "extreme", "underflow"})
    void validEdgeValuesProduceFiniteNormalizedOutputWithoutChangingTheSource(String kind) throws Exception {
        panel.rows.clear();
        TimeSeriesObject row = firstSeries();
        List<Double> expected;
        double maximum;
        switch (kind) {
            case "single" -> {
                row.xValues.clear();
                row.yValues.clear();
                row.addValuePair(12.25, 7.5);
                expected = List.of(1.0);
                maximum = 7.5;
            }
            case "constant" -> {
                row.yValues.set(0, 4.0);
                row.yValues.set(2, 4.0);
                expected = List.of(1.0, 1.0, 1.0);
                maximum = 4.0;
            }
            case "mixed signs" -> {
                row.yValues.set(0, -4.0);
                row.yValues.set(1, 0.0);
                row.yValues.set(2, 2.0);
                expected = List.of(-2.0, 0.0, 1.0);
                maximum = 2.0;
            }
            case "subnormal" -> {
                row.yValues.set(0, Double.MIN_VALUE);
                row.yValues.set(1, 2 * Double.MIN_VALUE);
                row.yValues.set(2, 4 * Double.MIN_VALUE);
                expected = List.of(0.25, 0.5, 1.0);
                maximum = 4 * Double.MIN_VALUE;
            }
            case "extreme" -> {
                row.yValues.set(0, -Double.MAX_VALUE);
                row.yValues.set(1, 0.0);
                row.yValues.set(2, Double.MAX_VALUE);
                expected = List.of(-1.0, 0.0, 1.0);
                maximum = Double.MAX_VALUE;
            }
            case "underflow" -> {
                row.xValues.setSize(2);
                row.yValues.setSize(2);
                row.yValues.set(0, -Double.MIN_VALUE);
                row.yValues.set(1, Double.MAX_VALUE);
                expected = List.of(-0.0, 1.0);
                maximum = Double.MAX_VALUE;
            }
            default -> throw new IllegalArgumentException(kind);
        }
        panel.rows.add(row);
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertSuccessfulChart(selected, List.of(expected), List.of(maximum));
        assertSingleRecording();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
        if (kind.equals("underflow")) {
            assertEquals(Double.doubleToLongBits(-0.0),
                    Double.doubleToLongBits((Double) resultRows(charts().get(0)).get(0).yValues.get(0)));
        }
    }

    @Test
    void rejectionPreservesExistingHistoryAndRetryAppendsOnlyTheSuccessfulTransformation() throws Exception {
        SwingUtilities.invokeAndWait(() -> MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation(
                "Previous input", "Previous output", "Previous operation")));
        String seededJson = PFDRecorder.getInstance().toJSON();
        var existingNode = tracker.track.nodes.get("Previous output");
        int nodeCount = tracker.track.nodes.size();
        int childCount = tracker.track.trackNode.getChildCount();
        assertNotNull(existingNode);
        assertEquals(1, nodeCount);
        assertEquals(1, childCount);
        assertEquals(List.of(false), chartsVisibleWhenRecorded);
        panel.rows.add(invalidSeries("all zero"));
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickNormalization();
        assertError("Maximum normalization requires a positive maximum");
        assertTrue(charts().isEmpty());
        assertEquals(List.of(false), chartsVisibleWhenRecorded);
        assertEquals(seededJson, PFDRecorder.getInstance().toJSON());
        assertEquals(nodeCount, tracker.track.nodes.size());
        assertEquals(childCount, tracker.track.trackNode.getChildCount());
        assertSame(existingNode, tracker.track.nodes.get("Previous output"));
        assertSame(existingNode, tracker.track.trackNode.getChildAt(0));
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);

        panel.rows.set(1, secondSeries());
        List<TimeSeriesObject> retrySelection = new ArrayList<>(panel.rows);
        List<RowState> retryInputs = snapshotInputs();
        errors.clear();
        titles.clear();
        messageTypes.clear();
        clickNormalization();
        assertSuccessfulChart(retrySelection, List.of(List.of(1.0 / 3, 2.0 / 3, 1.0), List.of(5.0 / 7, 1.0)),
                List.of(6.0, 14.0));
        assertEquals(List.of(false, true), chartsVisibleWhenRecorded);
        assertEquals(nodeCount + 1, tracker.track.nodes.size());
        assertEquals(childCount + 1, tracker.track.trackNode.getChildCount());
        assertSame(existingNode, tracker.track.nodes.get("Previous output"));
        assertSame(existingNode, tracker.track.trackNode.getChildAt(0));
        assertNotNull(tracker.track.nodes.get("NormMAX_Selected series"));
        var expectedFlow = com.google.gson.JsonParser.parseString(seededJson).getAsJsonObject();
        var actualFlow = com.google.gson.JsonParser.parseString(PFDRecorder.getInstance().toJSON()).getAsJsonObject();
        var steps = actualFlow.getAsJsonArray("steps");
        assertEquals(2, steps.size());
        var appended = steps.remove(1).getAsJsonObject();
        assertEquals("Norm MAX of ", appended.get("operation").getAsString());
        assertEquals(expectedFlow, actualFlow);
        assertEquals(retrySelection, panel.rows);
        assertInputsUnchanged(retryInputs);
    }

    private TimeSeriesObject invalidSeries(String kind) {
        TimeSeriesObject row = firstSeries();
        row.setLabel("Invalid series");
        switch (kind) {
            case "null row" -> row = null;
            case "empty" -> {
                row.xValues.clear();
                row.yValues.clear();
            }
            case "null x" -> row.xValues = null;
            case "null y" -> row.yValues = null;
            case "missing x" -> row.xValues.remove(2);
            case "missing y" -> row.yValues.remove(2);
            case "nan x" -> row.xValues.set(1, Double.NaN);
            case "nan y" -> row.yValues.set(1, Double.NaN);
            case "infinite x" -> row.xValues.set(1, Double.POSITIVE_INFINITY);
            case "infinite y" -> row.yValues.set(1, Double.POSITIVE_INFINITY);
            case "negative infinite x" -> row.xValues.set(1, Double.NEGATIVE_INFINITY);
            case "negative infinite y" -> row.yValues.set(1, Double.NEGATIVE_INFINITY);
            case "null x cell" -> row.xValues.set(1, null);
            case "null y cell" -> row.yValues.set(1, null);
            case "wrong x" -> row.xValues.set(1, Long.valueOf(1));
            case "wrong y" -> row.yValues.set(1, Integer.valueOf(2));
            case "all zero", "signed zero" -> {
                row.yValues.set(0, 0.0);
                row.yValues.set(1, kind.equals("signed zero") ? -0.0 : 0.0);
                row.yValues.set(2, 0.0);
            }
            case "maximum zero" -> {
                row.yValues.set(0, -4.0);
                row.yValues.set(1, -2.0);
                row.yValues.set(2, 0.0);
            }
            case "all negative" -> {
                row.yValues.set(0, -4.0);
                row.yValues.set(1, -2.0);
                row.yValues.set(2, -3.0);
            }
            case "overflow" -> {
                row.yValues.set(0, -Double.MAX_VALUE);
                row.yValues.set(1, Double.MIN_VALUE);
                row.yValues.set(2, 0.0);
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return row;
    }

    private String expectedMessage(String kind) {
        return switch (kind) {
            case "null row", "empty", "null x", "null y", "missing x", "missing y" ->
                    "Maximum normalization requires at least one coordinate-value pair";
            case "all zero", "signed zero", "maximum zero", "all negative" ->
                    "Maximum normalization requires a positive maximum";
            case "overflow" -> "Maximum normalization produced a non-finite value";
            default -> "Maximum normalization requires finite coordinates and values";
        };
    }

    private TimeSeriesObject firstSeries() {
        TimeSeriesObject row = new TimeSeriesObject("First series");
        row.setLabel_X("Seconds");
        row.setLabel_Y("Temperature");
        row.addValuePair(0.25, 2.0);
        row.addValuePair(1.75, 4.0);
        row.addValuePair(9.0, 6.0);
        return row;
    }

    private TimeSeriesObject secondSeries() {
        TimeSeriesObject row = new TimeSeriesObject("Second series");
        row.setLabel_X("Time");
        row.setLabel_Y("Pressure");
        row.addValuePair(-3.0, 10.0);
        row.addValuePair(4.125, 14.0);
        return row;
    }

    private List<RowState> snapshotInputs() {
        List<RowState> state = new ArrayList<>();
        for (TimeSeriesObject row : panel.rows) {
            if (row == null) continue;
            state.add(new RowState(row, row.xValues, row.yValues,
                    row.xValues == null ? null : new ArrayList<>(row.xValues),
                    row.yValues == null ? null : new ArrayList<>(row.yValues),
                    row.getLabel(), row.getLabel_X(), row.getLabel_Y()));
        }
        return state;
    }

    private void assertInputsUnchanged(List<RowState> original) {
        for (RowState state : original) {
            assertSame(state.x, state.row.xValues);
            assertSame(state.y, state.row.yValues);
            assertEquals(state.coordinates, state.row.xValues);
            assertEquals(state.values, state.row.yValues);
            assertEquals(state.label, state.row.getLabel());
            assertEquals(state.xLabel, state.row.getLabel_X());
            assertEquals(state.yLabel, state.row.getLabel_Y());
        }
    }

    private void clickNormalization() throws Exception {
        SwingUtilities.invokeAndWait(() -> field("jButton12", JButton.class).doClick(0));
    }

    private void assertError(String message) {
        assertEquals(0, prompts);
        assertEquals(List.of(message), errors);
        assertEquals(List.of("Normalization failed"), titles);
        assertEquals(List.of(JOptionPane.ERROR_MESSAGE), messageTypes);
    }

    private void assertRejected() {
        assertTrue(charts().isEmpty());
        assertTrue(chartsVisibleWhenRecorded.isEmpty());
        assertEquals(0, tracker.track.trackNode.getChildCount());
        assertTrue(tracker.track.nodes.isEmpty());
        assertEquals(previousFlow, PFDRecorder.getInstance().toJSON());
    }

    private void assertSuccessfulChart(List<TimeSeriesObject> selected, List<List<Double>> expected,
                                       List<Double> maxima) {
        assertEquals(0, prompts);
        assertTrue(errors.isEmpty());
        assertTrue(titles.isEmpty());
        assertTrue(messageTypes.isEmpty());
        List<MultiChart> charts = charts();
        assertEquals(1, charts.size());
        MultiChart chart = charts.get(0);
        assertTrue(chart.isVisible());
        String title = "NORM_MAX=1 of Selected series";
        assertEquals(title, chart.chartTitle);
        assertEquals(title, chart.chart.getTitle().getText());
        assertEquals(selected.size(), chart.dataset.getSeriesCount());
        Vector<TimeSeriesObject> normalized = resultRows(chart);
        assertEquals(selected.size(), normalized.size());
        for (int series = 0; series < selected.size(); series++) {
            TimeSeriesObject input = selected.get(series);
            TimeSeriesObject result = normalized.get(series);
            List<Double> values = expected.get(series);
            assertNotSame(input, result);
            assertEquals(input.getLabel() + " (max=" + maxima.get(series) + ")", result.getLabel());
            assertEquals(result.getLabel(), chart.dataset.getSeriesKey(series));
            assertEquals(input.getLabel_X(), result.getLabel_X());
            assertEquals(input.getLabel_Y(), result.getLabel_Y());
            assertEquals(input.xValues, result.xValues);
            assertNotSame(input.xValues, result.xValues);
            assertNotSame(input.yValues, result.yValues);
            assertEquals(values.size(), result.yValues.size());
            assertEquals(values.size(), chart.dataset.getItemCount(series));
            for (int item = 0; item < values.size(); item++) {
                assertEquals(((Double) input.xValues.get(item)).doubleValue(), chart.dataset.getXValue(series, item));
                assertTrue(Double.isFinite(chart.dataset.getYValue(series, item)));
                assertEquals(values.get(item), chart.dataset.getYValue(series, item), 1e-12);
                assertEquals(values.get(item), (Double) result.yValues.get(item), 1e-12);
            }
        }
    }

    private void assertSingleRecording() {
        assertEquals(List.of(true), chartsVisibleWhenRecorded);
        assertEquals(1, tracker.track.trackNode.getChildCount());
        assertEquals(1, tracker.track.nodes.size());
        assertEquals("NormMAX_Selected series", tracker.track.nodes.keys().nextElement());
        var recorded = new com.google.gson.Gson().fromJson(PFDRecorder.getInstance().toJSON(),
                PFDRecorder.ProcessingFlowDescriptor.class);
        assertEquals(1, recorded.steps.size());
        assertEquals("Norm MAX of ", recorded.steps.get(0).operation);
    }

    private List<MultiChart> charts() {
        return Arrays.stream(Window.getWindows())
                .filter(window -> !previousWindows.contains(window))
                .filter(MultiChart.class::isInstance).map(MultiChart.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private Vector<TimeSeriesObject> resultRows(MultiChart chart) {
        try {
            var rows = MultiChart.class.getDeclaredField("rows");
            rows.setAccessible(true);
            return (Vector<TimeSeriesObject>) rows.get(chart);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void closeDialog(AWTEvent event) {
        if (event.getID() != WindowEvent.WINDOW_OPENED) return;
        JOptionPane pane = findOptionPane((Container) event.getSource());
        if (pane == null) return;
        if (pane.getWantsInput()) {
            prompts++;
            pane.setInputValue(null);
            pane.setValue(JOptionPane.CANCEL_OPTION);
        } else {
            errors.add(String.valueOf(pane.getMessage()));
            titles.add(((JDialog) event.getSource()).getTitle());
            messageTypes.add(pane.getMessageType());
            pane.setValue(JOptionPane.OK_OPTION);
        }
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

    private record RowState(TimeSeriesObject row, Vector x, Vector y, List<?> coordinates, List<?> values,
                            String label, String xLabel, String yLabel) {}
}
