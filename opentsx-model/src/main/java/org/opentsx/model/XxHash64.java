package org.opentsx.model;

/**
 * xxHash64 (XXH64) with seed 0 by default. Self-contained so the model has no hashing dependency;
 * verified against the reference vectors in the test suite and against the Python {@code xxhash} package.
 */
public final class XxHash64 {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private XxHash64() {
    }

    public static long hash(byte[] data) {
        return hash(data, 0L);
    }

    public static long hash(byte[] data, long seed) {
        int len = data.length;
        int i = 0;
        long h;
        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            int limit = len - 32;
            do {
                v1 = round(v1, getLong(data, i));
                v2 = round(v2, getLong(data, i + 8));
                v3 = round(v3, getLong(data, i + 16));
                v4 = round(v4, getLong(data, i + 24));
                i += 32;
            } while (i <= limit);
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = merge(h, v1);
            h = merge(h, v2);
            h = merge(h, v3);
            h = merge(h, v4);
        } else {
            h = seed + P5;
        }
        h += len;
        while (i + 8 <= len) {
            h ^= round(0, getLong(data, i));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            i += 8;
        }
        if (i + 4 <= len) {
            h ^= (getInt(data, i) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            i += 4;
        }
        while (i < len) {
            h ^= (data[i] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            i++;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    /** 16 lower-case hex characters, zero padded (unsigned). */
    public static String hex(long hash) {
        String s = Long.toHexString(hash);
        return "0".repeat(16 - s.length()) + s;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        return acc * P1;
    }

    private static long merge(long h, long v) {
        h ^= round(0, v);
        return h * P1 + P4;
    }

    private static long getLong(byte[] b, int i) {
        return (b[i] & 0xFFL)
                | (b[i + 1] & 0xFFL) << 8
                | (b[i + 2] & 0xFFL) << 16
                | (b[i + 3] & 0xFFL) << 24
                | (b[i + 4] & 0xFFL) << 32
                | (b[i + 5] & 0xFFL) << 40
                | (b[i + 6] & 0xFFL) << 48
                | (b[i + 7] & 0xFFL) << 56;
    }

    private static int getInt(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }
}
