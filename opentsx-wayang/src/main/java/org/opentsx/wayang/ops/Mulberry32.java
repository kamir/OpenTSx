package org.opentsx.wayang.ops;

public final class Mulberry32 {

    private int state;

    public Mulberry32(long seed) {
        state = (int) seed;
    }

    public double nextDouble() {
        int t = state += 0x6d2b79f5;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }
}
