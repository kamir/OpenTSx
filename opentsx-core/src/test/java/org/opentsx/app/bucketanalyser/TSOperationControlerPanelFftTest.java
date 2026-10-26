package org.opentsx.app.bucketanalyser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.generators.TSGeneratorFINAL;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TSOperationControlerPanelFftTest {

    @TempDir
    Path home;

    ProbePanel panel;

    @BeforeEach
    void createPanel() throws Exception {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            SwingUtilities.invokeAndWait(() -> panel = new ProbePanel());
        } finally {
            System.setProperty("user.home", original);
        }
        panel.rows = new Vector<>();
    }

    @Test
    void derivesRateFromFractionalCoordinates() throws Exception {
        panel.rows.add(signal(10.25, 0.25));
        clickFft();
        assertNull(panel.error);
        assertEquals(0, panel.prompts);
        assertEquals(0.5, frequencyStep());
    }

    @Test
    void usesTheSampleGeneratorsActualRate() throws Exception {
        panel.rows.add(TSGeneratorFINAL.getSampleA().get(1));
        clickFft();
        assertNull(panel.error);
        assertEquals(0, panel.prompts);
        assertEquals(44000.0 / 65536.0, frequencyStep(), 1e-10);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0, 1700000000.0})
    void asksForAnExplicitRateForIntegerUnitCoordinates(double start) throws Exception {
        panel.rows.add(signal(start, 1.0));
        panel.answer = "32";
        clickFft();
        assertNull(panel.error);
        assertEquals(1, panel.prompts);
        assertEquals(4.0, frequencyStep());
    }

    @Test
    void cancellationProducesNoChart() throws Exception {
        panel.rows.add(signal(0.0, 1.0));
        clickFft();
        assertEquals(1, panel.prompts);
        assertNull(panel.results);
        assertNull(panel.error);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "-1", "NaN", "Infinity", "1e999"})
    void refusesInvalidExplicitRates(String answer) throws Exception {
        panel.rows.add(signal(0.0, 1.0));
        panel.answer = answer;
        clickFft();
        assertNull(panel.results);
        assertEquals("Sampling rate must be a finite positive number", panel.error);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.25, 0.6})
    void refusesInvalidCoordinates(double coordinate) throws Exception {
        TimeSeriesObject row = signal(0.0, 0.25);
        row.xValues.set(2, coordinate);
        panel.rows.add(row);
        clickFft();
        assertNull(panel.results);
        assertNotNull(panel.error);
        assertEquals(0, panel.prompts);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void refusesTooFewSamples(int count) throws Exception {
        TimeSeriesObject row = new TimeSeriesObject();
        for (int i = 0; i < count; i++) row.addValuePair(i * 0.25, i);
        panel.rows.add(row);
        clickFft();
        assertNull(panel.results);
        assertEquals("FFT needs at least three samples with matching coordinates", panel.error);
    }

    @Test
    void refusesNonFiniteValues() throws Exception {
        TimeSeriesObject row = signal(0.0, 0.25);
        row.yValues.set(2, Double.NaN);
        panel.rows.add(row);
        clickFft();
        assertNull(panel.results);
        assertEquals("FFT values and coordinates must be finite", panel.error);
    }

    @Test
    void refusesMismatchedCoordinateCounts() throws Exception {
        TimeSeriesObject row = signal(0.0, 0.25);
        row.xValues.remove(2);
        panel.rows.add(row);
        clickFft();
        assertNull(panel.results);
        assertEquals("FFT needs at least three samples with matching coordinates", panel.error);
    }

    @Test
    void refusesAnEmptySelection() throws Exception {
        clickFft();
        assertNull(panel.results);
        assertEquals("Select at least one series for FFT", panel.error);
    }

    private double frequencyStep() {
        assertNotNull(panel.results);
        assertEquals(1, panel.results.size());
        return ((Number) panel.results.firstElement().xValues.get(1)).doubleValue();
    }

    private void clickFft() throws Exception {
        var method = TSOperationControlerPanel.class.getDeclaredMethod("jButton3ActionPerformed", java.awt.event.ActionEvent.class);
        method.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                method.invoke(panel, new Object[]{null});
            } catch (InvocationTargetException e) {
                fail("FFT action threw instead of returning a result or an error", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private TimeSeriesObject signal(double start, double step) {
        TimeSeriesObject row = new TimeSeriesObject("Measured signal");
        for (int i = 0; i < 8; i++) row.addValuePair(start + i * step, Math.cos(2.0 * Math.PI * i / 8.0));
        return row;
    }

    static class ProbePanel extends TSOperationControlerPanel {
        String answer;
        String error;
        int prompts;
        Vector<TimeSeriesObject> results;

        String requestFftSamplingRate(TimeSeriesObject row) {
            prompts++;
            return answer;
        }

        void showFftError(String message) {
            error = message;
        }

        void showFftResults(Vector<TimeSeriesObject> values) {
            results = values;
        }
    }
}
