package org.opentsx.algorithms.eventsynchronisation;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ESCalcTest {

    private static final int[] EVENTS = {0, 7, 12, 20, 31, 35, 44, 50, 62, 70};

    @Test
    void identicalSeriesAreSynchronized() {
        double[] r = ESCalc.calc(EVENTS, EVENTS);
        assertEquals(8.0 / 9.0, r[0], 1e-12);
        assertEquals(0.0, r[1], 1e-12);
    }

    @Test
    void delayIsNegativeWhenTheSecondSeriesFollows() {
        int[] later = IntStream.of(EVENTS).map(t -> t + 1).toArray();
        double[] r = ESCalc.calc(EVENTS, later);
        assertEquals(8.0 / 9.0, r[0], 1e-12);
        assertEquals(-8.0 / 9.0, r[1], 1e-12);
    }

    @Test
    void eventHalfwayBetweenTwoEventsIsNotMatchedTwice() {
        int[] x = IntStream.range(0, 10).map(k -> 12 + 40 * k).toArray();
        int[] y = IntStream.range(0, 20).map(k -> (k % 2 == 0 ? 10 : 14) + 40 * (k / 2)).toArray();
        assertEquals(0.0, ESCalc2.calcES(x, y)[0], 1e-12);
        assertEquals(0.0, ESCalc.calc(x, y)[0], 1e-12);
    }

    @Test
    void syncAndDelayStayInBoundsOnRandomEvents() {
        Random random = new Random(1);
        for (int k = 0; k < 2000; k++) {
            int[] x = random.ints(60, 0, 300).distinct().sorted().toArray();
            int[] y = random.ints(60, 0, 300).distinct().sorted().toArray();
            for (double[] r : new double[][] {ESCalc.calc(x, y), ESCalc2.calcES(x, y)}) {
                assertTrue(r[0] >= 0.0 && r[0] <= 1.0, "sync " + r[0]);
                assertTrue(r[1] >= -1.0 && r[1] <= 1.0, "delay " + r[1]);
            }
        }
    }
}
