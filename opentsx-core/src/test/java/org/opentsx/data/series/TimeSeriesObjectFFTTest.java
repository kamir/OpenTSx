package org.opentsx.data.series;

import org.apache.commons.math3.transform.TransformType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
