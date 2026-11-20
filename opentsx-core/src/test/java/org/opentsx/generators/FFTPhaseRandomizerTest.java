package org.opentsx.generators;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Vector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class FFTPhaseRandomizerTest {

    double[] betas;
    Vector<TimeSeriesObject> check;

    @BeforeEach
    void saveTheStaticTables() {
        betas = FFTPhaseRandomizer.betas;
        check = FFTPhaseRandomizer.check;
        FFTPhaseRandomizer.check = new Vector<>();
    }

    @AfterEach
    void restoreTheStaticTables() {
        FFTPhaseRandomizer.betas = betas;
        FFTPhaseRandomizer.check = check;
    }

    @Test
    void anErrorChartWithoutBetasFailsNamingTheStepAndPrintsNothing() throws Exception {
        FFTPhaseRandomizer.betas = null;
        Method calcErrorChart = FFTPhaseRandomizer.class.getDeclaredMethod("calcErrorChart", double[][].class,
                int.class, int.class, double.class);
        calcErrorChart.setAccessible(true);
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, UTF_8);
        InvocationTargetException thrown;
        try {
            System.setOut(sink);
            System.setErr(sink);
            thrown = assertThrows(InvocationTargetException.class,
                    () -> calcErrorChart.invoke(null, new double[][]{{0.5, 0.6}}, 1, 2, 100.0));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        IllegalStateException e = assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals("calcErrorChart: beta index 0 failed; expected a beta and 2 alphas for it", e.getMessage());
        assertInstanceOf(NullPointerException.class, e.getCause());
        assertEquals("", captured.toString(UTF_8));
        assertTrue(FFTPhaseRandomizer.check.isEmpty());
    }

    @Test
    void anErrorChartAddsMeanStandardDeviationAndBounds() throws Exception {
        FFTPhaseRandomizer.betas = new double[]{0.25};
        Method calcErrorChart = FFTPhaseRandomizer.class.getDeclaredMethod("calcErrorChart", double[][].class,
                int.class, int.class, double.class);
        calcErrorChart.setAccessible(true);

        calcErrorChart.invoke(null, new double[][]{{0.5, 0.7}}, 1, 2, 10.0);

        assertEquals(4, FFTPhaseRandomizer.check.size());
        TimeSeriesObject mean = FFTPhaseRandomizer.check.get(0);
        assertEquals("mean", mean.getLabel());
        assertEquals(0.25, ((Number) mean.xValues.get(0)).doubleValue());
        assertEquals(0.6, ((Number) mean.yValues.get(0)).doubleValue(), 1e-12);
    }

    @Test
    void phaseRandomizingWritesNothingToStandardOutputOrError() throws Exception {
        TimeSeriesObject noise = FFTPhaseRandomizer.getRandomRow(256);
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, UTF_8);
        try {
            System.setOut(sink);
            System.setErr(sink);
            FFTPhaseRandomizer.getPhaseRandomizedRow(noise, false, true, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
            FFTPhaseRandomizer.getPhaseRandomizedRow(noise, false, true, 0,
                    FFTPhaseRandomizer.MODE_multiply_phase_with_random_value);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals("", captured.toString(UTF_8));
    }
}
