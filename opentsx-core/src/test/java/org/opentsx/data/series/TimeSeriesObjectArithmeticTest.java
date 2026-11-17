package org.opentsx.data.series;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectArithmeticTest {

    @Test
    void addSumsPointwiseAndKeepsTheCoordinates() {
        TimeSeriesObject sum = series(1.0, 2.0, 3.0).add(series(10.0, 20.0, 30.0));
        assertEquals(List.of(0.25, 0.5, 0.75), sum.xValues);
        assertEquals(List.of(11.0, 22.0, 33.0), sum.yValues);
    }

    @Test
    void addToAnEmptySeriesCopiesTheSummand() {
        TimeSeriesObject sum = new TimeSeriesObject().add(series(1.0, 2.0, 3.0));
        assertEquals(List.of(0.25, 0.5, 0.75), sum.xValues);
        assertEquals(List.of(1.0, 2.0, 3.0), sum.yValues);
    }

    @Test
    void addOfSeriesWithDifferentLengthsFails() {
        TimeSeriesObject three = series(1.0, 2.0, 3.0);
        TimeSeriesObject two = series(1.0, 2.0);
        IllegalArgumentException longer = assertThrows(IllegalArgumentException.class, () -> three.add(two));
        assertEquals("add: the series have 3 and 2 values; expected series of equal length", longer.getMessage());
        IllegalArgumentException shorter = assertThrows(IllegalArgumentException.class, () -> two.add(three));
        assertEquals("add: the series have 2 and 3 values; expected series of equal length", shorter.getMessage());
    }

    @Test
    void addOfFftSeriesWithDifferentLengthsFailsAndKeepsTheCoordinates() {
        TimeSeriesObjectFFT three = TimeSeriesObjectFFT.convertToMessreiheFFT(series(1.0, 2.0, 3.0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> three.add(series(1.0, 2.0)));
        assertEquals("add: the series have 3 and 2 values; expected series of equal length", e.getMessage());
        assertEquals(List.of(0.25, 0.5, 0.75), three.add(series(1.0, 1.0, 1.0)).xValues);
    }

    @Test
    void addUpToALengthFailsWhenASeriesIsShorter() {
        TimeSeriesObject three = series(1.0, 2.0, 3.0);
        TimeSeriesObject two = series(1.0, 2.0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> three.add(two, 3));
        assertEquals("add: the series have 3 and 2 values; expected at least 3 values in each", e.getMessage());
    }

    @Test
    void averageForAllKeepsTheCoordinatesOfTheShortestLength() {
        Vector<TimeSeriesObject> rows = new Vector<>();
        rows.add(series(1.0, 2.0, 3.0));
        rows.add(series(3.0, 4.0));
        TimeSeriesObject mean = TimeSeriesObject.averageForAll(rows);
        assertEquals(List.of(0.25, 0.5), mean.xValues);
        assertEquals(List.of(2.0, 3.0), mean.yValues);
    }

    @Test
    void divideByZeroGivesNaNAndKeepsTheCoordinates() {
        TimeSeriesObject quotient = series(1.0, 2.0, 0.0).divide_by(series(2.0, 0.0, 0.0));
        assertEquals(List.of(0.25, 0.5, 0.75), quotient.xValues);
        assertEquals(0.5, (Double) quotient.yValues.get(0));
        assertTrue(Double.isNaN((Double) quotient.yValues.get(1)));
        assertTrue(Double.isNaN((Double) quotient.yValues.get(2)));
    }

    @Test
    void divideBySeriesOfAnotherLengthFails() {
        TimeSeriesObject three = series(1.0, 2.0, 3.0);
        TimeSeriesObject two = series(1.0, 2.0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> two.divide_by(three));
        assertEquals("divide_by: the series have 2 and 3 values; expected series of equal length", e.getMessage());
    }

    @Test
    void zerosAreReplacedByTheMeanAndNoMethodIgnoresGapArguments() {
        TimeSeriesObject filled = series(2.0, 0.0, 4.0).replaceZeroWithAverage();
        assertEquals(List.of(2.0, 2.0, 4.0), filled.yValues);
        assertThrows(NoSuchMethodException.class,
                () -> TimeSeriesObject.class.getMethod("fillGapWithValue", double.class, double.class));
    }

    private static TimeSeriesObject series(double... values) {
        TimeSeriesObject row = new TimeSeriesObject();
        for (int i = 0; i < values.length; i++) {
            row.addValuePair(0.25 * (i + 1), values[i]);
        }
        return row;
    }
}
