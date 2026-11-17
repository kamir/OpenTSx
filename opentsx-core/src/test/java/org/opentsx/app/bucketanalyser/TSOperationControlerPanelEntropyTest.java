package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;

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
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelEntropyTest {

    @TempDir
    Path home;

    TSOperationControlerPanel panel;
    MacroTrackerFrame tracker;
    MacroTrackerFrame previousTracker;
    Object previousFlowState;
    String previousFlow;
    String previousHome;
    List<Window> previousWindows;
    String answer = "4";
    int prompts;
    final List<String> errors = new ArrayList<>();
    final List<String> titles = new ArrayList<>();
    final List<Integer> messageTypes = new ArrayList<>();
    final AWTEventListener dialogs = this::answerDialog;

    @BeforeEach
    void createPanelAndRecorder() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        SwingUtilities.invokeAndWait(() -> {
            previousWindows = Arrays.asList(Window.getWindows());
            previousTracker = MacroTrackerFrame.frame;
            tracker = new MacroTrackerFrame();
            tracker.track = new Track("Entropy test", tracker.top);
            MacroTrackerFrame.frame = tracker;
            try {
                var flow = PFDRecorder.class.getDeclaredField("currentFlow");
                flow.setAccessible(true);
                previousFlowState = flow.get(PFDRecorder.getInstance());
                PFDRecorder.getInstance().reset("Entropy test");
                previousFlow = PFDRecorder.getInstance().toJSON();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            panel = new TSOperationControlerPanel();
            panel.label = "Selected series";
            panel.rows = new Vector<>();
            panel.rows.add(series("First series", 6));
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

    @Test
    void cancellationLeavesChartsAndRecordersUnchanged() throws Exception {
        answer = null;
        clickEntropy();
        assertEquals(1, prompts);
        assertTrue(errors.isEmpty());
        assertTrue(titles.isEmpty());
        assertEquals(0, ((GuardedSeries) panel.rows.firstElement()).cuts);
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty"})
    void emptySelectionsAreRejectedBeforeOpeningTheWindowDialog(String kind) throws Exception {
        panel.rows.clear();
        if (kind.equals("null")) panel.rows = null;
        clickEntropy();
        assertEquals(0, prompts);
        assertError("Select at least one series for entropy");
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null row", "null x", "null y", "missing x", "missing y", "empty", "single"})
    void invalidSecondSeriesIsRejectedBeforeOpeningTheWindowDialog(String kind) throws Exception {
        TimeSeriesObject invalid = series("Second series", 3);
        switch (kind) {
            case "null row" -> invalid = null;
            case "null x" -> invalid.xValues = null;
            case "null y" -> invalid.yValues = null;
            case "missing x" -> invalid.xValues.remove(2);
            case "missing y" -> invalid.yValues.remove(2);
            case "empty" -> invalid = series("Second series", 0);
            case "single" -> invalid = series("Second series", 1);
            default -> throw new IllegalArgumentException(kind);
        }
        panel.rows.add(invalid);
        clickEntropy();
        assertEquals(0, prompts);
        assertError("Entropy needs at least two samples with matching coordinates");
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "text", "1.5", "0", "-1", "2147483648", " 4 ", "NaN"})
    void invalidLengthsLeaveChartsAndRecordersUnchanged(String input) throws Exception {
        answer = input;
        clickEntropy();
        assertEquals(1, prompts);
        assertError("Entropy window length must be a positive integer");
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nan value", "infinite coordinate", "null value", "text coordinate"})
    void malformedSecondSeriesProducesAVisibleErrorWithoutPartialChartsOrRecording(String kind) throws Exception {
        TimeSeriesObject invalid = series("Second series", 4);
        switch (kind) {
            case "nan value" -> invalid.yValues.set(1, Double.NaN);
            case "infinite coordinate" -> invalid.xValues.set(1, Double.POSITIVE_INFINITY);
            case "null value" -> invalid.yValues.set(1, null);
            case "text coordinate" -> invalid.xValues.set(1, "invalid");
            default -> throw new IllegalArgumentException(kind);
        }
        panel.rows.add(invalid);
        clickEntropy();
        assertError("Entropy values and coordinates must be finite numbers");
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, Integer.MAX_VALUE})
    void validLengthsShowBothActualEntropySeriesWithoutChangingInput(int length) throws Exception {
        answer = Integer.toString(length);
        panel.rows.add(series("Second series", 4));
        List<List<?>> original = new ArrayList<>();
        for (TimeSeriesObject row : panel.rows) {
            original.add(new ArrayList<>(row.xValues));
            original.add(new ArrayList<>(row.yValues));
        }
        clickEntropy();
        assertEquals(1, prompts);
        assertTrue(errors.isEmpty());
        List<MultiChart> charts = charts();
        assertEquals(1, charts.size());
        assertEquals("H_Selected series", charts.get(0).chartTitle);
        assertEquals("H_Selected series", charts.get(0).chart.getTitle().getText());
        var dataset = charts.get(0).dataset;
        assertEquals(2, dataset.getSeriesCount());
        int step = Math.max(1, length / 4);
        for (int series = 0; series < panel.rows.size(); series++) {
            TimeSeriesObject row = panel.rows.get(series);
            int size = row.yValues.size();
            int expectedWindows = 1 + (size - 2) / step;
            assertEquals(row.getLabel() + "H(l=" + length + ")", dataset.getSeriesKey(series));
            assertEquals(expectedWindows, dataset.getItemCount(series));
            for (int item = 0; item < expectedWindows; item++) {
                int begin = item * step;
                int end = (int) Math.min(begin + (long) length, size);
                assertEquals((double) begin, dataset.getXValue(series, item));
                assertEquals(expectedEntropy(row, begin, end), dataset.getYValue(series, item), 1e-12);
            }
        }
        assertEquals(1, tracker.track.trackNode.getChildCount());
        assertEquals(1, tracker.track.nodes.size());
        assertEquals("H_Selected series", tracker.track.nodes.keys().nextElement());
        var recorded = new com.google.gson.Gson().fromJson(PFDRecorder.getInstance().toJSON(),
                PFDRecorder.ProcessingFlowDescriptor.class);
        assertEquals(1, recorded.steps.size());
        assertEquals("H of ", recorded.steps.get(0).operation);
        int index = 0;
        for (TimeSeriesObject row : panel.rows) {
            assertEquals(original.get(index++), row.xValues);
            assertEquals(original.get(index++), row.yValues);
        }
    }

    private double expectedEntropy(TimeSeriesObject row, int begin, int end) {
        int low = 0;
        for (int i = begin; i < end; i++) {
            if (((Double) row.yValues.get(i)) == 1.0) low++;
        }
        int count = end - begin;
        if (low == 0 || low == count) return 0;
        double probability = low / (double) count;
        return -probability * Math.log(probability) / Math.log(2)
                - (1 - probability) * Math.log(1 - probability) / Math.log(2);
    }

    private void clickEntropy() throws Exception {
        SwingUtilities.invokeAndWait(() -> field("jButton14", JButton.class).doClick(0));
    }

    private void assertError(String message) {
        assertEquals(List.of(message), errors);
        assertEquals(List.of("Entropy failed"), titles);
        assertEquals(List.of(JOptionPane.ERROR_MESSAGE), messageTypes);
    }

    private void assertRejected() {
        assertTrue(charts().isEmpty());
        assertEquals(0, tracker.track.trackNode.getChildCount());
        assertTrue(tracker.track.nodes.isEmpty());
        assertEquals(previousFlow, PFDRecorder.getInstance().toJSON());
    }

    private List<MultiChart> charts() {
        return Arrays.stream(Window.getWindows())
                .filter(window -> !previousWindows.contains(window))
                .filter(MultiChart.class::isInstance).map(MultiChart.class::cast).toList();
    }

    private void answerDialog(AWTEvent event) {
        if (event.getID() != WindowEvent.WINDOW_OPENED) return;
        JOptionPane pane = findOptionPane((Container) event.getSource());
        if (pane == null) return;
        if (pane.getWantsInput()) {
            prompts++;
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

    private GuardedSeries series(String label, int count) {
        GuardedSeries row = new GuardedSeries(label);
        for (int i = 0; i < count; i++) row.addValuePair(100 + i * 0.25, 1 + i % 2);
        return row;
    }

    static class GuardedSeries extends TimeSeriesObject {
        int previousStart = -1;
        int cuts;

        GuardedSeries(String label) {
            super(label);
        }

        @Override
        public TimeSeriesObject cutOut(int begin, int end) {
            // Bound the unfixed loop before exercising small and oversized windows.
            assertTrue(begin > previousStart && end > begin && end <= xValues.size(),
                    "Entropy windows must advance within the input bounds");
            previousStart = begin;
            cuts++;
            return super.cutOut(begin, end);
        }
    }
}
