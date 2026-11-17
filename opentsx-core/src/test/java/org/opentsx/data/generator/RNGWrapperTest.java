package org.opentsx.data.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RNGWrapperTest {

    @Test
    void poissonSamplesHaveTheRequestedMean() {
        RNGWrapper.init();
        int n = 10_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += RNGWrapper.getStdPoisson(0.2);
        }
        assertEquals(0.2, sum / n, 0.02);
    }
}
