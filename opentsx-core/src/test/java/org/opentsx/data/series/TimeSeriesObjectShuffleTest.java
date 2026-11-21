package org.opentsx.data.series;

import org.junit.jupiter.api.Test;
import org.opentsx.data.generator.RNGWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectShuffleTest {

    @Test
    void aShuffleAfterTheSameSeedGivesTheSameOrder() {
        TimeSeriesObject first = series(100);
        TimeSeriesObject second = series(100);

        RNGWrapper.init(7);
        first.shuffleYValues();
        RNGWrapper.init(7);
        second.shuffleYValues();

        assertEquals(first.yValues, second.yValues);
        assertNotEquals(series(100).yValues, first.yValues);
        assertEquals(series(100).yValues, sorted(first.yValues));
        assertEquals(series(100).xValues, first.xValues);
    }

    @Test
    void severalRoundsAfterTheSameSeedGiveTheSameOrderAndNameTheRounds() {
        TimeSeriesObject first = series(100);
        TimeSeriesObject second = series(100);

        RNGWrapper.init(7);
        first.shuffleYValues(3);
        RNGWrapper.init(7);
        second.shuffleYValues(3);

        assertEquals(first.yValues, second.yValues);
        assertNotEquals(series(100).yValues, first.yValues);
        assertEquals(series(100).yValues, sorted(first.yValues));
        assertEquals("values (s3)", first.getLabel());
    }

    @Test
    void aShuffleOfTheXValuesAfterTheSameSeedGivesTheSameOrder() {
        TimeSeriesObject first = series(100);
        TimeSeriesObject second = series(100);

        RNGWrapper.init(7);
        first.shuffleXValues();
        RNGWrapper.init(7);
        second.shuffleXValues();

        assertEquals(first.xValues, second.xValues);
        assertNotEquals(series(100).xValues, first.xValues);
        assertEquals(series(100).xValues, sorted(first.xValues));
        assertEquals(series(100).yValues, first.yValues);
    }

    private static List<Double> sorted(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted;
    }

    private static TimeSeriesObject series(int n) {
        TimeSeriesObject series = new TimeSeriesObject("values");
        for (int i = 0; i < n; i++) {
            series.addValuePair(i, i);
        }
        return series;
    }
}
