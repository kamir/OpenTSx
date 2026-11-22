package org.opentsx.twin;

/**
 * The SplitMix64 generator of {@link java.util.SplittableRandom}, defined here because the JDK fixes the values of a
 * seeded {@code SplittableRandom} only within one program. A seed gives the values that
 * {@code new SplittableRandom(seed)} gives on OpenJDK 17 and 21, on every JVM.
 */
public final class SplitMix64 {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long seed;

    public SplitMix64(long seed) {
        this.seed = seed;
    }

    public long nextLong() {
        long z = seed += GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in {@code [0, 1)}. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in {@code [0, bound)}. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        int r = nextInt();
        int m = bound - 1;
        if ((bound & m) == 0) {
            return r & m;
        }
        int u = r >>> 1;
        while (u + m - (r = u % bound) < 0) {
            u = nextInt() >>> 1;
        }
        return r;
    }

    private int nextInt() {
        long z = seed += GAMMA;
        z = (z ^ (z >>> 33)) * 0x62A9D9ED799705F5L;
        return (int) (((z ^ (z >>> 28)) * 0xCB24D0A5C88C35B3L) >>> 32);
    }
}
