package org.opentsx.algorithms.detrending.methods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.algorithms.detrending.DetrendingMethodFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DFACoreTest {

    @Test
    void theWindowSizesAreLoggedAtDebugWithoutADebugFlag() {
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.DFA2);
        dfa.getPara().setzSValues(20);
        dfa.setNrOfValues(1000);
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            dfa.initIntervalSlog();
        } finally {
            System.setErr(err);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("logarithmic window sizes s=["), log);
        assertTrue(log.contains("allocating results windows="), log);
    }

    @Test
    void aFitOfOrderTwoReturnsTheCoefficientsOfAQuadratic() {
        DFA dfa = new DFA();
        dfa.pr = new double[40];
        for (int i = 0; i < dfa.pr.length; i++) {
            double x = i - 5;
            dfa.pr[i] = 3.0 - 2.0 * x + 0.5 * x * x;
        }
        assertArrayEquals(new double[]{3.0, -2.0, 0.5}, dfa.fitn(5, 30, 2), 1e-9);
    }

    @Test
    void aFitOfOrderThreeReturnsTheCoefficientsOfACubic() {
        DFA dfa = new DFA();
        dfa.pr = new double[12];
        for (int i = 0; i < dfa.pr.length; i++) {
            dfa.pr[i] = 1.0 + 0.25 * i - 0.75 * i * i + 0.125 * i * i * i;
        }
        assertArrayEquals(new double[]{1.0, 0.25, -0.75, 0.125}, dfa.fitn(0, 11, 3), 1e-9);
    }

    @Test
    void aFitNeedsMorePointsThanCoefficients() {
        DFA dfa = new DFA();
        dfa.pr = new double[10];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> dfa.fitn(0, 2, 3));
        assertEquals("fitn: 3 points cannot determine a polynomial of order 3; expected at least 4 points",
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void dfaOfOrderNRemovesATrendOfOrderNMinusOne(int order) {
        int n = 2048;
        double[] noise = new double[n];
        double[] trended = new double[n];
        Random random = new Random(11);
        for (int i = 0; i < n; i++) {
            noise[i] = random.nextGaussian();
            double t = i / (double) n;
            trended[i] = noise[i] + (order == 2 ? 5.0 * t : 5.0 * t * t);
        }
        double[] expected = fluctuation(order, noise);
        double[] actual = fluctuation(order, trended);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-6 * expected[i], "s index " + i);
        }
    }

    @Test
    void tooFewValuesForTheLogarithmicWindowsFailNamingTheCount() {
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(2);
        dfa.setNrOfValues(21);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, dfa::initIntervalSlog);
        assertEquals("initIntervalSlog: 21 values are too few; expected at least 22 values", e.getMessage());
    }

    @Test
    void windowSizesAndTheCalculationWriteNothingToStandardOutput() {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(1);
            double[] values = new double[256];
            Random random = new Random(5);
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextGaussian();
            }
            dfa.setNrOfValues(values.length);
            dfa.initIntervalS();
            dfa.showS();
            dfa.initIntervalSlog();
            dfa.setZR(values);
            dfa.calc();
            DetrendingMethodFactory.getDetrendingMethod(DetrendingMethodFactory.MFDFA2);
        } finally {
            System.setOut(stdout);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }

    private static double[] fluctuation(int order, double[] values) {
        IDetrendingMethod dfa = DetrendingMethodFactory.getDetrendingMethod(order);
        dfa.setNrOfValues(values.length);
        dfa.initIntervalSlog();
        dfa.setZR(values);
        dfa.calc();
        return dfa.getResults()[3];
    }
}
