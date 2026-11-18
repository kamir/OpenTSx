package org.opentsx.data.series;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.algorithms.statistics.DatasetNormalizationTool;

import java.time.Duration;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectStatisticsTest {

    @Test
    void recalculatedMeanReflectsChangedValues() {
        TimeSeriesObject row = series();
        row.calcAverage();
        assertEquals(4.0, row.getAvarage());
        row.yValues.set(0, 8.0);
        assertEquals(6.0, row.getAvarage2());
    }

    @Test
    void meanIsComputedWithoutCalcAverage() {
        TimeSeriesObject row = series();
        assertEquals(4.0, row.getAvarage());
        row.yValues.set(0, 8.0);
        assertEquals(6.0, row.getAvarage());
    }

    @Test
    void meanOfAnFftSeriesIsComputedWithoutCalcAverage() {
        TimeSeriesObjectFFT row = new TimeSeriesObjectFFT();
        row.addValuePair(1.0, 2.0);
        row.addValuePair(2.0, 6.0);
        assertEquals(4.0, row.getAvarage());
        row.yValues.set(0, 8.0);
        assertEquals(7.0, row.getAvarage());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3})
    void entropyWindowShorterThanFourFails(int length) {
        TimeSeriesObject row = series();
        IllegalArgumentException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IllegalArgumentException.class, () -> row.calcEntropyForWindow(length)));
        assertEquals("window length: " + length + " is less than 4; expected a window length of at least 4 values",
                e.getMessage());
    }

    @Test
    void entropyWindowOfFourAdvancesByOneValue() {
        TimeSeriesObject entropy = series().calcEntropyForWindow(4);
        assertEquals(List.of(0.0, 1.0), entropy.xValues);
    }

    @Test
    void centeringPreservesCoordinatesAndInputValues() {
        TimeSeriesObject input = series();
        TimeSeriesObject centered = input.subtractAverage();
        assertEquals(List.of(-2.0, 0.0, 2.0), centered.yValues);
        assertEquals(input.xValues, centered.xValues);
        assertNotSame(input.xValues, centered.xValues);
        assertEquals(List.of(2.0, 4.0, 6.0), input.yValues);
        assertEquals(0.0, centered.getAvarage2());
    }

    @Test
    void normalizationPreservesCoordinatesLabelsAndInputValues() {
        TimeSeriesObject input = series();
        input.setLabel("Measured series");
        input.setLabel_X("Seconds");
        input.setLabel_Y("Temperature");
        TimeSeriesObject normalized = input.normalizeToStdevIsOne();
        assertEquals(List.of(-1.0, 0.0, 1.0), normalized.yValues);
        assertEquals(0.0, normalized.getAvarage2());
        assertEquals(1.0, normalized.getStddev());
        assertEquals(input.xValues, normalized.xValues);
        assertEquals("Seconds", normalized.getLabel_X());
        assertEquals("Temperature", normalized.getLabel_Y());
        assertEquals(List.of(2.0, 4.0, 6.0), input.yValues);
    }

    @ParameterizedTest
    @CsvSource({"0,3,3", "1,3,2", "1,2,1", "2,8,1", "2,2,0", "3,5,0"})
    void windowsAreHalfOpenAndPreserveOriginalCoordinates(int start, int end, int size) {
        TimeSeriesObject input = series();
        TimeSeriesObject window = input.cutOut(start, end);
        assertEquals(size, window.yValues.size());
        for (int i = 0; i < size; i++) {
            assertEquals(input.xValues.get(start + i), window.xValues.get(i));
            assertEquals(input.yValues.get(start + i), window.yValues.get(i));
        }
        assertEquals(3, input.yValues.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty", "single", "constant", "nan", "infinity", "null-value",
            "missing-coordinate", "nan-coordinate", "null-x", "null-y", "overflow"})
    void normalizationRejectsInvalidSeries(String kind) {
        TimeSeriesObject row = series();
        switch (kind) {
            case "null" -> row = null;
            case "empty" -> row = new TimeSeriesObject();
            case "single" -> row = row.cutOut(0, 1);
            case "constant" -> { row.yValues.set(0, 4.0); row.yValues.set(2, 4.0); }
            case "nan" -> row.yValues.set(0, Double.NaN);
            case "infinity" -> row.yValues.set(0, Double.POSITIVE_INFINITY);
            case "null-value" -> row.yValues.set(0, null);
            case "missing-coordinate" -> row.xValues.remove(0);
            case "nan-coordinate" -> row.xValues.set(0, Double.NaN);
            case "null-x" -> row.xValues = null;
            case "null-y" -> row.yValues = null;
            case "overflow" -> { row.yValues.set(0, Double.MAX_VALUE); row.yValues.set(1, -Double.MAX_VALUE); }
            default -> throw new IllegalArgumentException(kind);
        }
        TimeSeriesObject input = row;
        assertThrows(IllegalArgumentException.class,
                () -> DatasetNormalizationTool.normalizeToMWZeroANDSTDevONE(input));
    }

    @Test
    void logarithmsOfValuesThatAreNotPositiveAreNaN() {
        TimeSeriesObject log10 = values(10.0, 0.0, -1.0);
        log10.calcLog10_for_Y();
        assertEquals(List.of(1.0, Double.NaN, Double.NaN), log10.yValues);

        TimeSeriesObject ln = values(Math.E, 0.0, -1.0);
        ln.calcLn_for_Y();
        assertEquals(List.of(1.0, Double.NaN, Double.NaN), ln.yValues);

        TimeSeriesObject lnCopy = values(Math.E, 0.0, -1.0).calcMR_Ln_for_Y();
        assertEquals(List.of(1.0, Double.NaN, Double.NaN), lnCopy.yValues);
        assertEquals(List.of(1.0, 2.0, 3.0), lnCopy.xValues);
    }

    @Test
    void theLogarithmHasOneMethodThatReturnsANewSeries() {
        assertThrows(NoSuchMethodException.class, () -> TimeSeriesObject.class.getMethod("calcMR_Log_for_Y"));
    }

    @Test
    void meanOfRowsDividesEachPointByTheNumberOfRows() throws Exception {
        TimeSeriesObject mean = TimeSeriesObject.calcAveragOfRows(new Vector<>(List.of(values(1.0, 1.0), values(3.0, 3.0))));
        assertEquals(List.of(2.0, 2.0), mean.yValues);
        assertEquals(List.of(1.0, 2.0), mean.xValues);
        TimeSeriesObject partial = TimeSeriesObject.calcAveragOfRows(new Vector<>(List.of(values(1.0, 1.0, 1.0), values(3.0))));
        assertEquals(List.of(2.0, 1.0, 1.0), partial.yValues);
    }

    @Test
    void xForYReturnsTheFirstPointWithinEpsilon() {
        TimeSeriesObject row = new TimeSeriesObject();
        row.addValuePair(0.5, 3.0);
        row.addValuePair(1.5, 7.0);
        row.addValuePair(2.5, 7.05);
        assertEquals(1.5, row.getX_for_Y2(7.02, 0.1));
        assertEquals(0.5, row.getX_for_Y2(3.0, 0.1));
        assertTrue(Double.isNaN(row.getX_for_Y2(5.0, 0.1)));
        assertTrue(Double.isNaN(new TimeSeriesObject().getX_for_Y2(5.0, 0.1)));
    }

    private static TimeSeriesObject values(double... y) {
        TimeSeriesObject row = new TimeSeriesObject();
        for (int i = 0; i < y.length; i++) {
            row.addValuePair(i + 1.0, y[i]);
        }
        return row;
    }

    private TimeSeriesObject series() {
        TimeSeriesObject row = new TimeSeriesObject();
        row.addValuePair(0.25, 2.0);
        row.addValuePair(0.50, 4.0);
        row.addValuePair(0.75, 6.0);
        return row;
    }
}
