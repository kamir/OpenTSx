package org.opentsx.data.series;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.EventQueue;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSeriesObjectFFTTest {

    @Test
    void conversionPreservesCoordinatesAndCopiesValues() {
        TimeSeriesObject input = new TimeSeriesObject();
        input.addValuePair(0.25, 2.0);
        input.addValuePair(0.5, 4.0);
        input.setLabel("Measured signal");

        TimeSeriesObjectFFT converted = TimeSeriesObjectFFT.convertToMessreiheFFT(input);

        assertEquals(input.xValues, converted.xValues);
        assertEquals(input.yValues, converted.yValues);
        assertEquals(input.getLabel(), converted.getLabel());
        assertNotSame(input.xValues, converted.xValues);
        assertNotSame(input.yValues, converted.yValues);
    }

    @Test
    void fftPreservesCalculatedFrequencyCoordinates() {
        TimeSeriesObjectFFT input = new TimeSeriesObjectFFT();
        for (int i = 0; i < 8; i++) {
            input.addValuePair(i / 32.0, Math.cos(2.0 * Math.PI * i / 8.0));
        }
        TimeSeriesObject expected = TimeSeriesObjectFFT.calcFFT(input, 32.0, TransformType.FORWARD);

        TimeSeriesObjectFFT actual = input.getFFT(32.0);

        assertEquals(expected.xValues, actual.xValues);
        assertEquals(expected.yValues, actual.yValues);
        assertEquals(4.0, ((Number) actual.xValues.get(1)).doubleValue());
    }

    @Test
    void transformsWriteNothingToStandardOutput() {
        TimeSeriesObjectFFT input = new TimeSeriesObjectFFT();
        for (int i = 0; i < 12; i++) {
            input.addValuePair(i, Math.cos(2.0 * Math.PI * i / 6.0));
        }
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            TimeSeriesObjectFFT.calcFFT(input, 1.0, TransformType.FORWARD);
            input.getModifiedTimeSeries_FourierFiltered(0.5);
            input.getModifiedTimeSeries_PhaseRandomized(new Random(1));
            input.diff(input);
            input.checkKonsistenz();
            input.split(5, 3);
        } finally {
            System.setOut(stdout);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void serializationUsesEnglishHeaders() {
        TimeSeriesObjectFFT input = new TimeSeriesObjectFFT("Measured signal");
        input.addValuePair(0.25, 2.0);
        input.addValuePair(0.5, 4.0);

        String text = input.toString();

        assertTrue(text.contains("# Time series"));
        assertTrue(text.contains("# Class : class org.opentsx.data.series.TimeSeriesObjectFFT"));
        assertTrue(text.contains("# Number of value pairs = 2"));
    }

    @Test
    void invalidRangesUseEnglishErrors() {
        TimeSeriesObjectFFT input = new TimeSeriesObjectFFT();
        input.addValuePair(0.25, 2.0);
        input.addValuePair(0.5, 4.0);

        Exception shift = assertThrows(Exception.class, () -> input.shift(3));
        Exception fit = assertThrows(Exception.class, () -> input.linFit(0.0, 1.0));

        assertEquals("TimeSeriesObject is shorter than the shift distance", shift.getMessage());
        assertEquals("Series is too short. xMax=0.5, x_max=1.0", fit.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"shuffle", "multiply", "randomized"})
    void aSurrogateKeepsTheAmplitudeSpectrumAndChangesTheSeries(String method) throws Exception {
        TimeSeriesObjectFFT input = surrogateInput();
        int windows = windowsAfterPendingEvents();

        TimeSeriesObjectFFT surrogate = surrogate(method, input, new Random(42));

        assertEquals(windows, windowsAfterPendingEvents(), "the surrogate opened a window");
        double[] in = input.getYData();
        double[] out = surrogate.getYData();
        assertEquals(in.length, out.length);
        double[] expected = amplitudes(in);
        double[] actual = amplitudes(out);
        assertEquals(0.0, actual[0], 1e-9);
        for (int k = 1; k < in.length; k++) {
            assertEquals(expected[k], actual[k], 1e-9, "amplitude of bin " + k);
        }
        double mean = expected[0] / in.length;
        double change = 0;
        for (int i = 0; i < in.length; i++) {
            change = Math.max(change, Math.abs(out[i] - (in[i] - mean)));
        }
        assertTrue(change > 0.1, "largest change " + change);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shuffle", "multiply", "randomized"})
    void aSurrogateDependsOnlyOnTheSeed(String method) {
        TimeSeriesObjectFFT input = surrogateInput();
        assertEquals(surrogate(method, input, new Random(7)).yValues, surrogate(method, input, new Random(7)).yValues);
        assertNotEquals(surrogate(method, input, new Random(7)).yValues, surrogate(method, input, new Random(8)).yValues);
    }

    private static TimeSeriesObjectFFT surrogate(String method, TimeSeriesObjectFFT input, Random random) {
        return switch (method) {
            case "shuffle" -> input.getPhaseRandomizedModifiedFFT_SHUFFLED_INV(random);
            case "multiply" -> input.getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV(random);
            case "randomized" -> input.getModifiedTimeSeries_PhaseRandomized(random);
            default -> throw new IllegalArgumentException(method);
        };
    }

    private static TimeSeriesObjectFFT surrogateInput() {
        TimeSeriesObjectFFT row = new TimeSeriesObjectFFT();
        int n = 64;
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            row.addValuePair(i, 2.0 + Math.sin(3 * t) + 0.5 * Math.cos(7 * t) + 0.25 * Math.sin(11 * t + 1.0)
                    + 0.4 * (i % 2 == 0 ? 1 : -1) + 0.3 * ((i * 37) % 11 - 5) / 5.0);
        }
        return row;
    }

    private static int windowsAfterPendingEvents() throws Exception {
        EventQueue.invokeAndWait(() -> { });
        return Window.getWindows().length;
    }

    private static double[] amplitudes(double[] values) {
        Complex[] c = new FastFourierTransformer(DftNormalization.STANDARD).transform(values, TransformType.FORWARD);
        double[] a = new double[c.length];
        for (int k = 0; k < c.length; k++) {
            a[k] = c[k].abs();
        }
        return a;
    }
}
