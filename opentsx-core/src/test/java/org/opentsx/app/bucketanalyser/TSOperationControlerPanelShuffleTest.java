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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelShuffleTest {

    @TempDir
    Path home;

    TSOperationControlerPanel panel;
    MacroTrackerFrame tracker;
    MacroTrackerFrame previousTracker;
    Object previousFlowState;
    String previousFlow;
    String previousHome;
    List<Window> previousWindows;
    String answer = "2";
    Level previousLogLevel;
    final List<Boolean> chartsVisibleWhenRecorded = new ArrayList<>();
    final List<String> prompts = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> titles = new ArrayList<>();
    final List<Integer> messageTypes = new ArrayList<>();
    final List<LogRecord> severeMessages = new ArrayList<>();
    final Logger logger = Logger.getLogger(TSOperationControlerPanel.class.getName());
    final AWTEventListener dialogs = this::answerDialog;
    final Handler logging = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue()) severeMessages.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void createPanelAndRecorder() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        previousLogLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(logging);
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
            tracker.track = new Track("Shuffle test", tracker.top);
            MacroTrackerFrame.frame = tracker;
            try {
                var flow = PFDRecorder.class.getDeclaredField("currentFlow");
                flow.setAccessible(true);
                previousFlowState = flow.get(PFDRecorder.getInstance());
                PFDRecorder.getInstance().reset("Shuffle test");
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
            logger.removeHandler(logging);
            logger.setLevel(previousLogLevel);
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty"})
    void emptySelectionsAreRejectedBeforeThePassDialog(String kind) throws Exception {
        panel.rows.clear();
        if (kind.equals("null")) panel.rows = null;
        clickShuffle();
        assertTrue(prompts.isEmpty());
        assertError("Select at least one series for shuffling");
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null row", "empty", "null x", "null y", "missing x", "missing y", "null label"})
    void malformedLaterRowsAreRejectedBeforeThePassDialog(String kind) throws Exception {
        panel.rows.add(invalidSeries(kind));
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertTrue(prompts.isEmpty());
        assertError(kind.equals("null label") ? "Shuffle requires a series label"
                : "Shuffle requires at least one coordinate-value pair");
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "text", "1.5", "0", "-1", "2147483648", " 2 ", "NaN"})
    void invalidPassCountsProduceAVisibleErrorWithoutChartsOrRecording(String input) throws Exception {
        answer = input;
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertError("Shuffle pass count must be a positive integer");
        assertRejected();
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationIsQuietAndDoesNotValidateCellsOrChangeInputs(boolean malformedCells) throws Exception {
        if (malformedCells) panel.rows.add(invalidSeries("nan y"));
        answer = null;
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertTrue(errors.isEmpty());
        assertTrue(titles.isEmpty());
        assertTrue(messageTypes.isEmpty());
        assertTrue(severeMessages.isEmpty());
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nan x", "nan y", "infinite x", "infinite y", "negative infinite x",
            "negative infinite y", "null x cell", "null y cell", "wrong x", "wrong y"})
    void invalidLaterCellsProduceNoPartialChartOrRecorderEntry(String kind) throws Exception {
        panel.rows.add(invalidSeries(kind));
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertError("Shuffle requires finite coordinates and values");
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @Test
    void invalidLaterCellsAreRejectedBeforeCopyingOrShufflingAnyEarlierRow() throws Exception {
        GuardedSource first = guardedSeries("First guarded series", 3);
        panel.rows.clear();
        panel.rows.add(first);
        panel.rows.add(invalidSeries("nan y"));
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertError("Shuffle requires finite coordinates and values");
        assertEquals(0, first.copies);
        assertTrue(first.shufflePasses.isEmpty());
        assertRejected();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @Test
    void anInvalidCountIsReportedBeforeMalformedCells() throws Exception {
        panel.rows.add(invalidSeries("wrong y"));
        answer = "invalid";
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertError("Shuffle pass count must be a positive integer");
        assertRejected();
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void realShufflingPreservesEveryCoordinateAxisAndValueMultiplicity(int passes) throws Exception {
        answer = Integer.toString(passes);
        panel.rows.add(secondSeries());
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertSuccessfulChart(selected, passes);
        assertSingleRecording();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "constant", "fft"})
    void validSmallAndFftSeriesRetainTheirCoordinatesAndLabels(String kind) throws Exception {
        TimeSeriesObject row;
        if (kind.equals("fft")) {
            row = new TimeSeriesObjectFFT("FFT series");
            row.addValuePair(0.25, -2.0);
            row.addValuePair(4.5, 8.0);
        } else {
            row = new TimeSeriesObject("Edge series");
            row.addValuePair(12.25, 4.0);
            if (kind.equals("constant")) {
                row.addValuePair(18.5, 4.0);
                row.addValuePair(21.0, 4.0);
            }
        }
        row.setLabel_X("Seconds");
        row.setLabel_Y("Amplitude");
        panel.rows.clear();
        panel.rows.add(row);
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertSuccessfulChart(selected, 2);
        assertSingleRecording();
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"one over limit", "integer overflow", "combined selection"})
    void excessiveWorkIsRejectedBeforeCopyingOrShuffling(String kind) throws Exception {
        GuardedSource first = guardedSeries("First guarded series", 2);
        panel.rows.clear();
        panel.rows.add(first);
        List<GuardedSource> sources = new ArrayList<>(List.of(first));
        if (kind.equals("combined selection")) {
            GuardedSource second = guardedSeries("Second guarded series", 2);
            second.yValues.set(0, Double.NaN);
            panel.rows.add(second);
            sources.add(second);
            answer = "3000000";
        } else {
            answer = kind.equals("integer overflow") ? Integer.toString(Integer.MAX_VALUE) : "5000001";
        }
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertError("Shuffle work exceeds the desktop limit of 10000000 sample visits");
        assertRejected();
        for (GuardedSource source : sources) {
            assertEquals(0, source.copies);
            assertTrue(source.shufflePasses.isEmpty());
        }
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theExactWorkBoundaryIsAcceptedWithoutPerformingMillionsOfShufflePasses(boolean multipleRows) throws Exception {
        GuardedSource first = guardedSeries("First guarded series", 2);
        panel.rows.clear();
        panel.rows.add(first);
        List<GuardedSource> sources = new ArrayList<>(List.of(first));
        int passes = multipleRows ? 2000000 : 5000000;
        if (multipleRows) {
            GuardedSource second = guardedSeries("Second guarded series", 3);
            panel.rows.add(second);
            sources.add(second);
        }
        answer = Integer.toString(passes);
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertSuccessfulChart(selected, passes);
        assertSingleRecording();
        for (GuardedSource source : sources) {
            assertEquals(1, source.copies);
            assertEquals(List.of(passes), source.shufflePasses);
        }
        assertEquals(selected, panel.rows);
        assertInputsUnchanged(original);
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
        panel.rows.add(invalidSeries("nan y"));
        List<TimeSeriesObject> selected = new ArrayList<>(panel.rows);
        List<RowState> original = snapshotInputs();
        clickShuffle();
        assertPrompt();
        assertError("Shuffle requires finite coordinates and values");
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
        prompts.clear();
        errors.clear();
        titles.clear();
        messageTypes.clear();
        clickShuffle();
        assertSuccessfulChart(retrySelection, 2);
        assertEquals(List.of(false, true), chartsVisibleWhenRecorded);
        assertEquals(nodeCount + 1, tracker.track.nodes.size());
        assertEquals(childCount + 1, tracker.track.trackNode.getChildCount());
        assertSame(existingNode, tracker.track.nodes.get("Previous output"));
        assertSame(existingNode, tracker.track.trackNode.getChildAt(0));
        assertNotNull(tracker.track.nodes.get("Shuffle_Selected series"));
        var expectedFlow = com.google.gson.JsonParser.parseString(seededJson).getAsJsonObject();
        var actualFlow = com.google.gson.JsonParser.parseString(PFDRecorder.getInstance().toJSON()).getAsJsonObject();
        var steps = actualFlow.getAsJsonArray("steps");
        assertEquals(2, steps.size());
        var appended = steps.remove(1).getAsJsonObject();
        assertEquals("Shuffle of Selected series", appended.get("operation").getAsString());
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
            case "null label" -> {
                row = new TimeSeriesObjectFFT(null);
                row.addValuePair(0.25, 2.0);
                row.addValuePair(4.5, 8.0);
                assertNull(row.getLabel());
            }
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
            default -> throw new IllegalArgumentException(kind);
        }
        return row;
    }

    private TimeSeriesObject firstSeries() {
        TimeSeriesObject row = new TimeSeriesObject("First series");
        row.setLabel_X("Seconds");
        row.setLabel_Y("Temperature");
        row.addValuePair(0.25, -2.0);
        row.addValuePair(1.75, 4.0);
        row.addValuePair(9.0, 4.0);
        return row;
    }

    private TimeSeriesObject secondSeries() {
        TimeSeriesObject row = new TimeSeriesObject("Second series");
        row.setLabel_X("Time");
        row.setLabel_Y("Pressure");
        row.addValuePair(-3.0, 0.0);
        row.addValuePair(4.125, 14.0);
        return row;
    }

    private GuardedSource guardedSeries(String label, int count) {
        GuardedSource row = new GuardedSource(label);
        row.setLabel_X("Seconds");
        row.setLabel_Y("Amplitude");
        for (int i = 0; i < count; i++) row.addValuePair(0.25 + i * 1.5, i + 1.0);
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

    private void clickShuffle() throws Exception {
        SwingUtilities.invokeAndWait(() -> field("jButton18", JButton.class).doClick(0));
    }

    private void assertPrompt() {
        assertEquals(List.of("Number of shuffle passes:"), prompts);
    }

    private void assertError(String message) {
        assertEquals(List.of(message), errors);
        assertEquals(List.of("Shuffle failed"), titles);
        assertEquals(List.of(JOptionPane.ERROR_MESSAGE), messageTypes);
        assertTrue(severeMessages.isEmpty());
    }

    private void assertRejected() {
        assertTrue(charts().isEmpty());
        assertTrue(chartsVisibleWhenRecorded.isEmpty());
        assertEquals(0, tracker.track.trackNode.getChildCount());
        assertTrue(tracker.track.nodes.isEmpty());
        assertEquals(previousFlow, PFDRecorder.getInstance().toJSON());
    }

    private void assertSuccessfulChart(List<TimeSeriesObject> selected, int passes) {
        assertPrompt();
        assertTrue(errors.isEmpty());
        assertTrue(titles.isEmpty());
        assertTrue(messageTypes.isEmpty());
        assertTrue(severeMessages.isEmpty());
        List<MultiChart> charts = charts();
        assertEquals(1, charts.size());
        MultiChart chart = charts.get(0);
        assertTrue(chart.isVisible());
        String title = "Shuffle_Selected series";
        assertEquals(title, chart.chartTitle);
        assertEquals(title, chart.chart.getTitle().getText());
        assertEquals(selected.size(), chart.dataset.getSeriesCount());
        Vector<TimeSeriesObject> shuffled = resultRows(chart);
        assertEquals(selected.size(), shuffled.size());
        for (int series = 0; series < selected.size(); series++) {
            TimeSeriesObject input = selected.get(series);
            TimeSeriesObject result = shuffled.get(series);
            assertNotSame(input, result);
            assertEquals(input.getLabel() + " (s" + passes + ")", result.getLabel());
            assertEquals(result.getLabel(), chart.dataset.getSeriesKey(series));
            assertEquals(input.getLabel_X(), result.getLabel_X());
            assertEquals(input.getLabel_Y(), result.getLabel_Y());
            assertEquals(input.xValues, result.xValues);
            assertNotSame(input.xValues, result.xValues);
            assertNotSame(input.yValues, result.yValues);
            List<Double> expected = new ArrayList<>(input.yValues);
            List<Double> actual = new ArrayList<>(result.yValues);
            Collections.sort(expected);
            Collections.sort(actual);
            assertEquals(expected, actual);
            assertEquals(input.yValues.size(), chart.dataset.getItemCount(series));
            for (int item = 0; item < input.yValues.size(); item++) {
                assertEquals(((Double) input.xValues.get(item)).doubleValue(), chart.dataset.getXValue(series, item));
                assertTrue(Double.isFinite(chart.dataset.getYValue(series, item)));
                assertEquals(((Double) result.yValues.get(item)).doubleValue(), chart.dataset.getYValue(series, item));
            }
        }
    }

    private void assertSingleRecording() {
        assertEquals(List.of(true), chartsVisibleWhenRecorded);
        assertEquals(1, tracker.track.trackNode.getChildCount());
        assertEquals(1, tracker.track.nodes.size());
        assertEquals("Shuffle_Selected series", tracker.track.nodes.keys().nextElement());
        var recorded = new com.google.gson.Gson().fromJson(PFDRecorder.getInstance().toJSON(),
                PFDRecorder.ProcessingFlowDescriptor.class);
        assertEquals(1, recorded.steps.size());
        assertEquals("Shuffle of Selected series", recorded.steps.get(0).operation);
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

    private void answerDialog(AWTEvent event) {
        if (event.getID() != WindowEvent.WINDOW_OPENED) return;
        JOptionPane pane = findOptionPane((Container) event.getSource());
        if (pane == null) return;
        if (pane.getWantsInput()) {
            prompts.add(String.valueOf(pane.getMessage()));
            pane.setInputValue(answer);
            pane.setValue(answer == null ? JOptionPane.CANCEL_OPTION : JOptionPane.OK_OPTION);
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

    static class GuardedSource extends TimeSeriesObject {
        int copies;
        final List<Integer> shufflePasses = new ArrayList<>();

        GuardedSource(String label) {
            super(label);
        }

        @Override
        public TimeSeriesObject copy() {
            copies++;
            GuardedClone clone = new GuardedClone(getLabel(), shufflePasses);
            clone.xValues.addAll(xValues);
            clone.yValues.addAll(yValues);
            clone.setLabel_X(getLabel_X());
            clone.setLabel_Y(getLabel_Y());
            return clone;
        }
    }

    static class GuardedClone extends TimeSeriesObject {
        final List<Integer> shufflePasses;

        GuardedClone(String label, List<Integer> shufflePasses) {
            super(label);
            this.shufflePasses = shufflePasses;
        }

        @Override
        public void shuffleYValues(int passes) {
            shufflePasses.add(passes);
            setLabel(getLabel() + " (s" + passes + ")");
        }
    }

    private record RowState(TimeSeriesObject row, Vector x, Vector y, List<?> coordinates, List<?> values,
                            String label, String xLabel, String yLabel) {}
}
