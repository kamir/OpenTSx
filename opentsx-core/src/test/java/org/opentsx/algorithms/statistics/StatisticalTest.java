package org.opentsx.algorithms.statistics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class StatisticalTest {

    private static final double[] SKEWED = {-1.9, -1.4, -1.2, -1.1, -0.9, -0.8, -0.8, -0.6, -0.5, -0.3,
            -0.2, 0.0, 0.3, 0.7, 1.2, 1.9, 2.8, 4.1, 5.6, 7.5};

    @Test
    void shapiroWilkMatchesTheReference() {
        double[] result = Statistical.shapiroWilk(SKEWED);
        // Reference: scipy.stats.shapiro, the same algorithm AS R94.
        assertEquals(0.8178496859, result[Statistical.SHAPIRO_WILK_WSTAT], 1e-5);
        assertEquals(0.0016147568, result[Statistical.SHAPIRO_WILK_PVALUE], 1e-5);
    }

    @Test
    void shapiroWilkDoesNotChangeWhenAConstantIsAdded() {
        double[] shifted = Arrays.stream(SKEWED).map(v -> v + 100).toArray();
        double[] result = Statistical.shapiroWilk(SKEWED);
        double[] shiftedResult = Statistical.shapiroWilk(shifted);
        assertEquals(shiftedResult[Statistical.SHAPIRO_WILK_WSTAT], result[Statistical.SHAPIRO_WILK_WSTAT], 1e-9);
        assertEquals(shiftedResult[Statistical.SHAPIRO_WILK_PVALUE], result[Statistical.SHAPIRO_WILK_PVALUE], 1e-9);
    }
}
