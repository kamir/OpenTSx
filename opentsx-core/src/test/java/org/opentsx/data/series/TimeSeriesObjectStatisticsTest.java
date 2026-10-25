package org.opentsx.data.series;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.algorithms.statistics.DatasetNormalizationTool;

import java.util.List;

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

    private TimeSeriesObject series() {
        TimeSeriesObject row = new TimeSeriesObject();
        row.addValuePair(0.25, 2.0);
        row.addValuePair(0.50, 4.0);
        row.addValuePair(0.75, 6.0);
        return row;
    }
}
