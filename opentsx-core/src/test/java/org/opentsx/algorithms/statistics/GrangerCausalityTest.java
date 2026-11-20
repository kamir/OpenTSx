package org.opentsx.algorithms.statistics;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GrangerCausalityTest {

    private static final double[] X = {0.5, -1.2, 0.3, 1.8, -0.4, 0.9, -1.5, 0.2, 1.1, -0.7, 0.6, -0.1, 1.4, -0.9, 0.8, -0.3};
    private static final double[] Y = {0.2, -0.6, 0.9, 0.1, -0.4, 1.3, -0.2, -0.7, 0.6, 0.4, -1.1, 0.3, 0.0, 0.9, -0.5, 0.2};

    @Test
    void matchesTheFTestOfTwoLeastSquaresModelsWithIntercept() {
        // Reference: numpy.linalg.lstsq with a column of ones and scipy.stats.f.sf(F, l, n - 2l - 1).
        assertEquals(0.426926282119, GrangerCausality.grangerCausalityTest(Y, X, 1), 1e-9);
        assertEquals(0.560760354831, GrangerCausality.grangerCausalityTest(Y, X, 2), 1e-9);
    }

    @Test
    void findsTheDrivingSeriesAndNotTheDrivenOne() {
        int seeds = 200;
        int smallReverse = 0;
        double sumReverse = 0;
        for (int seed = 0; seed < seeds; seed++) {
            double[][] xy = drivenPair(seed, 500);
            double forward = GrangerCausality.grangerCausalityTest(xy[1], xy[0], 1);
            double reverse = GrangerCausality.grangerCausalityTest(xy[0], xy[1], 1);
            assertTrue(forward < 1e-6, "seed " + seed + ": p(x -> y) = " + forward);
            sumReverse += reverse;
            if (reverse < 0.05) {
                smallReverse++;
            }
        }
        double meanReverse = sumReverse / seeds;
        assertTrue(meanReverse > 0.4 && meanReverse < 0.6, "mean p(y -> x) = " + meanReverse);
        assertTrue(smallReverse <= seeds / 10, smallReverse + " of " + seeds + " seeds give p(y -> x) < 0.05");
    }

    @Test
    void aLagBelowOneFailsNamingTheLag() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GrangerCausality.grangerCausalityTest(Y, X, 0));
        assertEquals("l: 0 is out of range; expected a lag of 1 or more", e.getMessage());
    }

    @Test
    void seriesOfDifferentLengthFailNamingBothLengths() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GrangerCausality.grangerCausalityTest(Y, new double[]{1.0, 2.0, 3.0}, 1));
        assertEquals("x: 3 values do not match the 16 values of y; expected two series of the same length",
                e.getMessage());
    }

    @Test
    void tooFewValuesFailNamingTheLagAndTheMinimum() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GrangerCausality.grangerCausalityTest(new double[]{1, 2, 3, 4}, new double[]{1, 3, 2, 4}, 1));
        assertEquals("y: 4 values are too few for lag 1; expected at least 5 values", e.getMessage());
    }

    @Test
    void aLagTooLargeForAnIntMinimumFailsNamingTheMinimum() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GrangerCausality.grangerCausalityTest(Y, X, 1_000_000_000));
        assertEquals("y: 16 values are too few for lag 1000000000; expected at least 3000000002 values",
                e.getMessage());
    }

    @Test
    void aRegressionThatCannotBeSolvedFailsNamingTheInputAndKeepsTheCause() {
        double[] zeros = new double[16];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GrangerCausality.grangerCausalityTest(zeros, zeros, 1));
        assertEquals("y, x: the regression of lag 1 over 16 values cannot be solved (matrix is singular); expected"
                + " series that are not constant", e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void writesNothingToStandardOutput() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            GrangerCausality.grangerCausalityTest(Y, X, 1);
        } finally {
            System.setOut(original);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    private static double[][] drivenPair(long seed, int n) {
        Random random = new Random(seed);
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = random.nextGaussian();
        }
        y[0] = random.nextGaussian();
        for (int i = 1; i < n; i++) {
            y[i] = 0.5 * x[i - 1] + random.nextGaussian();
        }
        return new double[][]{x, y};
    }
}
