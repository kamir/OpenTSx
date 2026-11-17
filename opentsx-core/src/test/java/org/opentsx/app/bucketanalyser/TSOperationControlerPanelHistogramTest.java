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

class TSOperationControlerPanelHistogramTest {

    @TempDir
    Path home;

    TSOperationControlerPanel panel;
    MacroTrackerFrame tracker;
    MacroTrackerFrame previousTracker;
    List<Window> previousWindows;
    String previousHome;
    String previousFlow;
    Object previousFlowState;
    String answer = "2";
    int prompts;
    final List<String> errors = new ArrayList<>();
    final AWTEventListener dialogs = this::answerDialog;

    @BeforeEach
    void createPanelAndRecorder() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        SwingUtilities.invokeAndWait(() -> {
            previousWindows = Arrays.asList(Window.getWindows());
            previousTracker = MacroTrackerFrame.frame;
            tracker = new MacroTrackerFrame();
            tracker.track = new Track("Histogram test", tracker.top);
            MacroTrackerFrame.frame = tracker;
            previousFlow = PFDRecorder.getInstance().toJSON();
            try {
                var flow = PFDRecorder.class.getDeclaredField("currentFlow");
                flow.setAccessible(true);
                previousFlowState = flow.get(PFDRecorder.getInstance());
                PFDRecorder.getInstance().reset("Histogram test");
                previousFlow = PFDRecorder.getInstance().toJSON();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            panel = new TSOperationControlerPanel();
            panel.label = "Selected series";
            panel.rows = new Vector<>();
            panel.rows.add(series(0, 0, 1, 1, 0));
            field("jtMIN", JTextField.class).setText("0");
            field("jtMAX", JTextField.class).setText("2");
            field("jtBINS", JTextField.class).setText("4");
            Toolkit.getDefaultToolkit().addAWTEventListener(dialogs, AWTEvent.WINDOW_EVENT_MASK);
        });
    }

    @AfterEach
    void closeWindows() throws Exception {
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
        field("jtMIN", JTextField.class).setText("invalid");
        field("jtBINS", JTextField.class).setText("0");
        clickHistogram();
        assertEquals(1, prompts);
        assertTrue(errors.isEmpty());
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "text", "1.5", "0", "-1", "2147483648", "-2147483649", "NaN", "Infinity"})
    void invalidWindowLengthsLeaveChartsAndRecordersUnchanged(String input) throws Exception {
        answer = input;
        clickHistogram();
        assertEquals(1, prompts);
        assertEquals(List.of("Window length must be a positive integer"), errors);
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty", "null first", "no samples", "empty first"})
    void emptySelectionsAreRejectedBeforePrompting(String selection) throws Exception {
        panel.rows.clear();
        if (selection.equals("null")) panel.rows = null;
        if (selection.equals("null first")) panel.rows.add(null);
        if (selection.equals("no samples") || selection.equals("empty first")) {
            panel.rows.add(new TimeSeriesObject("Empty series"));
        }
        if (selection.equals("empty first")) panel.rows.add(series(0, 1));
        clickHistogram();
        assertEquals(0, prompts);
        assertEquals(List.of("Select at least one nonempty series for a cross-cut histogram"), errors);
        assertRejected();
    }

    @ParameterizedTest
    @CsvSource({"jtMIN,text", "jtMIN,1.5", "jtMIN,2", "jtMIN,3", "jtMIN,-2147483649",
            "jtMAX,NaN", "jtMAX,Infinity", "jtMAX,0", "jtMAX,2147483648"})
    void invalidBoundsLeaveChartsAndRecordersUnchanged(String name, String value) throws Exception {
        field(name, JTextField.class).setText(value);
        clickHistogram();
        assertEquals(List.of("Histogram bounds must be integers with minimum less than maximum"), errors);
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text", "1.5", "0", "-1", "2147483648"})
    void invalidBinsLeaveChartsAndRecordersUnchanged(String value) throws Exception {
        field("jtBINS", JTextField.class).setText(value);
        clickHistogram();
        assertEquals(List.of("Histogram bin count must be a positive integer"), errors);
        assertRejected();
    }

    @ParameterizedTest
    @CsvSource({"1,3", "2,3", "5,3", "6,3", "2147483647,3", "2,0", "2,5", "2,7", "6,7", "2147483647,7"})
    void validWindowsPoolRawCountsAndPreserveTheFinalPartialSlice(int length, int secondLength) throws Exception {
        answer = Integer.toString(length);
        double[] second = new double[secondLength];
        for (int i = 0; i < secondLength; i++) second[i] = i % 2;
        panel.rows.add(series(second));
        List<List<?>> original = new ArrayList<>();
        for (TimeSeriesObject row : panel.rows) {
            original.add(new ArrayList<>(row.xValues));
            original.add(new ArrayList<>(row.yValues));
        }
        clickHistogram();
        assertTrue(errors.isEmpty());
        assertEquals(1, prompts);
        List<MultiChart> charts = charts();
        assertEquals(1, charts.size());
        var dataset = charts.get(0).dataset;
        int count = panel.rows.firstElement().xValues.size();
        int expectedSlices = (int) ((count + (long) length - 1) / length);
        assertEquals(expectedSlices, dataset.getSeriesCount());
        int position = 0;
        for (int slice = 0; slice < expectedSlices; slice++) {
            long end = position + (long) length;
            int[] expected = new int[2];
            for (TimeSeriesObject row : panel.rows) {
                for (int i = position; i < Math.min(end, row.yValues.size()); i++) {
                    expected[((Double) row.yValues.get(i)).intValue()]++;
                }
            }
            assertEquals(position + "_" + end, dataset.getSeriesKey(slice));
            int item = 0;
            for (int value = 0; value <= 1; value++) {
                if (expected[value] == 0) continue;
                assertEquals((double) value, dataset.getXValue(slice, item));
                assertEquals((double) expected[value], dataset.getYValue(slice, item));
                item++;
            }
            assertEquals(item, dataset.getItemCount(slice));
            position += Math.min(length, count - position);
        }
        assertEquals(1, tracker.track.trackNode.getChildCount());
        assertEquals(1, tracker.track.nodes.size());
        assertEquals("PDF_cross_Selected series", tracker.track.nodes.keys().nextElement());
        assertEquals(1, new com.google.gson.Gson().fromJson(PFDRecorder.getInstance().toJSON(),
                PFDRecorder.ProcessingFlowDescriptor.class).steps.size());
        int index = 0;
        for (TimeSeriesObject row : panel.rows) {
            assertEquals(original.get(index++), row.xValues);
            assertEquals(original.get(index++), row.yValues);
        }
    }

    private void clickHistogram() throws Exception {
        SwingUtilities.invokeAndWait(() -> field("jButton20", JButton.class).doClick(0));
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

    private TimeSeriesObject series(double... values) {
        TimeSeriesObject row = new TimeSeriesObject("Measured series") {
            @Override
            public TimeSeriesObject cutOut(int begin, int end) {
                // Bound the legacy loop before exercising invalid lengths on the unfixed implementation.
                assertTrue(end > begin && end <= xValues.size(), "Slice must advance within the series bounds");
                return super.cutOut(begin, end);
            }
        };
        for (int i = 0; i < values.length; i++) row.addValuePair(100 + i * 0.25, values[i]);
        return row;
    }
}
