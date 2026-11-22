package org.opentsx.twin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The values of {@code new java.util.SplittableRandom(2026)} on OpenJDK 17 and 21, now fixed by the twin. */
class SplitMix64Test {

    @Test
    void givesTheValuesOfOpenJdkSplittableRandom() {
        SplitMix64 r = new SplitMix64(2026);
        assertEquals(0x3feb738ab3123291L, Double.doubleToLongBits(r.nextDouble()));
        assertEquals(1, r.nextInt(11));
        assertEquals(2, r.nextInt(3));
        assertEquals(7, r.nextInt(16));
        assertEquals(0xcaa69c1e0798ff49L, r.nextLong());
        assertEquals(115218412, r.nextInt(1_000_000_007));
        assertThrows(IllegalArgumentException.class, () -> r.nextInt(0));
    }
}
