package org.opentsx.stats;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantileSketchTest {

    private static double exact(double[] sorted, double q) {
        return sorted[(int) Math.floor(q * (sorted.length - 1))];
    }

    @Test
    void quantilesHaveBoundedRelativeError() {
        Random r = new Random(1);
        double[] values = new double[100_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = switch (i % 3) {
                case 0 -> Math.exp(r.nextGaussian() * 2);        // log-normal, wide dynamic range
                case 1 -> -50 + 100 * r.nextDouble();            // symmetric around zero
                default -> 8 + 2 * r.nextGaussian();             // wind-speed like
            };
        }
        QuantileSketch s = new QuantileSketch(0.01);
        for (double v : values) {
            s.add(v);
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        for (double q : new double[]{0, 0.001, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 0.999, 1}) {
            double want = exact(sorted, q);
            double got = s.quantile(q);
            assertTrue(Math.abs(got - want) <= 0.01 * Math.abs(want) + 1e-9, q + ": " + got + " vs " + want);
        }
        assertEquals(values.length, s.count());
    }

    @Test
    void mergeEqualsSingleSketchAndBytesAreCanonical() {
        Random r = new Random(2);
        QuantileSketch all = new QuantileSketch();
        QuantileSketch a = new QuantileSketch();
        QuantileSketch b = new QuantileSketch();
        for (int i = 0; i < 10_000; i++) {
            double v = r.nextGaussian() * 10;
            all.add(v);
            (i % 2 == 0 ? a : b).add(v);
        }
        a.merge(b);
        assertEquals(all, a);
        assertArrayEquals(all.toBytes(), a.toBytes());
        QuantileSketch back = QuantileSketch.fromBytes(a.toBytes());
        assertEquals(a, back);
        assertEquals(a.count(), back.count());
        assertEquals(a.quantile(0.5), back.quantile(0.5));
    }

    @Test
    void zerosNaNsAndEmpty() {
        QuantileSketch s = new QuantileSketch();
        assertTrue(Double.isNaN(s.quantile(0.5)));
        s.add(Double.NaN);
        s.add(Double.POSITIVE_INFINITY);
        assertEquals(0, s.count());
        s.add(0.0);
        s.add(0.0);
        s.add(5.0);
        assertEquals(0.0, s.quantile(0.5));
        assertEquals(5.0, s.quantile(1.0), 0.05);
        assertThrows(IllegalArgumentException.class, () -> s.merge(new QuantileSketch(0.02)));
    }

    @Test
    void shareOutsideABand() {
        QuantileSketch s = new QuantileSketch();
        for (int i = 0; i < 1000; i++) {
            s.add(i < 990 ? 10 + (i % 20) : 100);    // 1 % far above 60
        }
        s.add(-5);                                     // one below 0
        assertEquals(1.0 / 1001, s.fractionBelow(0), 1e-12);
        assertEquals(10.0 / 1001, s.fractionAbove(60), 1e-12);
        assertEquals(0.0, s.fractionAbove(150), 1e-12);
    }

    @Test
    void collapsingBoundsMemory() {
        QuantileSketch s = new QuantileSketch(0.01, 64);
        for (int i = -300; i <= 300; i++) {
            s.add(Math.pow(10, i / 10.0));
        }
        assertTrue(s.toBytes().length < 64 * 2 * 4 + 32);
        assertEquals(601, s.count());
        double top = s.quantile(1.0);
        assertTrue(Math.abs(top - 1e30) <= 0.01 * 1e30);    // large values keep their accuracy
    }

    /**
     * Fixed test vector: the Python implementation (opentsx.stats.sketch) must produce the same bytes.
     * If this changes, the binary format changed - bump the format version instead.
     */
    @Test
    void testVector() {
        QuantileSketch s = new QuantileSketch(0.01);
        for (double v : new double[]{0.0, 1.0, 2.5, 2.5, -3.75, 8.2, 12.0, 1e-12, 1000.0, -0.5}) {
            s.add(v);
        }
        assertEquals("017b14ae47e17a843f020500015c0278012601ba0301024301ca0101",
                HexFormat.of().formatHex(s.toBytes()));
    }
}
