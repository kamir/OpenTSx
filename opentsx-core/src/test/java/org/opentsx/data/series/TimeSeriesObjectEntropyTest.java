package org.opentsx.data.series;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesObjectEntropyTest {

    private static final double TWO_THIRDS_ENTROPY = 0.9182958340544896;
    private static final double THREE_FIFTHS_ENTROPY = 0.9709505944546686;
    private static final double FOUR_SEVENTHS_ENTROPY = 0.9852281360342515;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, Integer.MAX_VALUE})
    void positiveWindowsAdvanceClipCutsAndPreserveTheInput(int length) {
        GuardedSeries input = series();
        Vector originalX = input.xValues;
        Vector originalY = input.yValues;
        List<?> coordinates = new ArrayList<>(originalX);
        List<?> values = new ArrayList<>(originalY);
        TimeSeriesObject result = input.calcEntropyForWindow(length);
        assertResult(length, result);
        assertEquals(expectedStarts(length), input.starts);
        assertSame(originalX, input.xValues);
        assertSame(originalY, input.yValues);
        assertEquals(coordinates, input.xValues);
        assertEquals(values, input.yValues);
        assertEquals("Measured series", input.getLabel());
        assertEquals("Sample", input.getLabel_X());
        assertEquals("Measured value", input.getLabel_Y());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8})
    void existingWindowStartsAndPartialWindowEntropyRemainUnchanged(int length) {
        TimeSeriesObject input = new TimeSeriesObject();
        populate(input, 1, 2, 1, 2, 1, 2, 1);
        assertResult(length, input.calcEntropyForWindow(length));
        assertEquals(List.of(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0), input.yValues);
    }

    @Test
    void twoSamplesProduceOneClippedWindow() {
        GuardedSeries input = new GuardedSeries();
        populate(input, 1, 2);
        TimeSeriesObject result = input.calcEntropyForWindow(4);
        assertEquals(List.of(0.0), result.xValues);
        assertEquals(List.of(1.0), result.yValues);
        assertEquals(List.of(0), input.starts);
        assertEquals(List.of(1.0, 2.0), input.yValues);
    }

    @Test
    void zeroAndMaximumValuesStillShareTheLegacyBinZero() {
        TimeSeriesObject input = new TimeSeriesObject();
        populate(input, 0, 2, 0, 2);
        TimeSeriesObject result = input.calcEntropyForWindow(4);
        assertEquals(List.of(0.0, 1.0, 2.0), result.xValues);
        assertEquals(List.of(0.0, 0.0, 0.0), result.yValues);
        assertEquals(List.of(0.0, 2.0, 0.0, 2.0), input.yValues);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidLengthsAreRejectedBeforeCuttingOrMutatingTheInput(int length) {
        assertRejected(series(), length, "Entropy window length must be a positive integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "single", "null-x", "null-y", "missing-x", "missing-y"})
    void invalidShapesAreRejectedBeforeCuttingOrMutatingTheInput(String kind) {
        GuardedSeries input = series();
        switch (kind) {
            case "empty" -> {
                input.xValues.clear();
                input.yValues.clear();
            }
            case "single" -> {
                input.xValues.setSize(1);
                input.yValues.setSize(1);
            }
            case "null-x" -> input.xValues = null;
            case "null-y" -> input.yValues = null;
            case "missing-x" -> input.xValues.remove(6);
            case "missing-y" -> input.yValues.remove(6);
            default -> throw new IllegalArgumentException(kind);
        }
        assertRejected(input, 4, "Entropy needs at least two samples with matching coordinates");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-x", "null-y", "wrong-x", "wrong-y", "nan-x", "nan-y",
            "infinite-x", "infinite-y", "negative-infinite-x", "negative-infinite-y"})
    void invalidCellsAreRejectedIncludingTheFinalInputSample(String kind) {
        GuardedSeries input = series();
        switch (kind) {
            case "null-x" -> input.xValues.set(6, null);
            case "null-y" -> input.yValues.set(6, null);
            case "wrong-x" -> input.xValues.set(6, Long.valueOf(1));
            case "wrong-y" -> input.yValues.set(6, Integer.valueOf(2));
            case "nan-x" -> input.xValues.set(6, Double.NaN);
            case "nan-y" -> input.yValues.set(6, Double.NaN);
            case "infinite-x" -> input.xValues.set(6, Double.POSITIVE_INFINITY);
            case "infinite-y" -> input.yValues.set(6, Double.POSITIVE_INFINITY);
            case "negative-infinite-x" -> input.xValues.set(6, Double.NEGATIVE_INFINITY);
            case "negative-infinite-y" -> input.yValues.set(6, Double.NEGATIVE_INFINITY);
            default -> throw new IllegalArgumentException(kind);
        }
        assertRejected(input, 1, "Entropy values and coordinates must be finite numbers");
    }

    private void assertRejected(GuardedSeries input, int length, String expectedMessage) {
        Vector originalX = input.xValues;
        Vector originalY = input.yValues;
        List<?> coordinates = originalX == null ? null : new ArrayList<>(originalX);
        List<?> values = originalY == null ? null : new ArrayList<>(originalY);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> input.calcEntropyForWindow(length));
        assertEquals(expectedMessage, failure.getMessage());
        assertSame(originalX, input.xValues);
        assertSame(originalY, input.yValues);
        assertEquals(coordinates, input.xValues);
        assertEquals(values, input.yValues);
        assertEquals("Measured series", input.getLabel());
        assertTrue(input.starts.isEmpty());
    }

    private void assertResult(int length, TimeSeriesObject result) {
        List<Double> expectedValues = switch (length) {
            case 1 -> List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            case 2 -> List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
            case 3 -> List.of(TWO_THIRDS_ENTROPY, TWO_THIRDS_ENTROPY, TWO_THIRDS_ENTROPY,
                    TWO_THIRDS_ENTROPY, TWO_THIRDS_ENTROPY, 1.0);
            case 4 -> List.of(1.0, 1.0, 1.0, 1.0, TWO_THIRDS_ENTROPY, 1.0);
            case 7 -> List.of(FOUR_SEVENTHS_ENTROPY, 1.0, THREE_FIFTHS_ENTROPY, 1.0, TWO_THIRDS_ENTROPY, 1.0);
            case 8 -> List.of(FOUR_SEVENTHS_ENTROPY, THREE_FIFTHS_ENTROPY, TWO_THIRDS_ENTROPY);
            case Integer.MAX_VALUE -> List.of(FOUR_SEVENTHS_ENTROPY);
            default -> throw new IllegalArgumentException(Integer.toString(length));
        };
        assertEquals(expectedStarts(length).stream().map(Integer::doubleValue).toList(), result.xValues);
        assertEquals(expectedValues.size(), result.yValues.size());
        for (int i = 0; i < expectedValues.size(); i++) {
            assertEquals(expectedValues.get(i), (Double) result.yValues.get(i), 1e-12);
        }
        assertEquals("Measured seriesH(l=" + length + ")", result.getLabel());
    }

    private List<Integer> expectedStarts(int length) {
        return switch (length) {
            case 1, 2, 3, 4, 7 -> List.of(0, 1, 2, 3, 4, 5);
            case 8 -> List.of(0, 2, 4);
            case Integer.MAX_VALUE -> List.of(0);
            default -> throw new IllegalArgumentException(Integer.toString(length));
        };
    }

    private GuardedSeries series() {
        GuardedSeries input = new GuardedSeries();
        populate(input, 1, 2, 1, 2, 1, 2, 1);
        return input;
    }

    private void populate(TimeSeriesObject input, double... values) {
        double[] coordinates = {10.25, 12.0, 11.5, -2.0, 100.0, 0.125, 8.0};
        input.setLabel("Measured series");
        input.setLabel_X("Sample");
        input.setLabel_Y("Measured value");
        for (int i = 0; i < values.length; i++) input.addValuePair(coordinates[i], values[i]);
    }

    private static class GuardedSeries extends TimeSeriesObject {
        final List<Integer> starts = new ArrayList<>();

        @Override
        public TimeSeriesObject cutOut(int begin, int end) {
            // Bound unfixed loops before they repeat indefinitely or scan an oversized window.
            assertTrue(begin >= 0 && end > begin && end <= xValues.size(), "Cut must stay within the input");
            if (!starts.isEmpty()) {
                assertTrue(begin > starts.get(starts.size() - 1), "Cut starts must advance");
            }
            starts.add(begin);
            return super.cutOut(begin, end);
        }
    }
}
