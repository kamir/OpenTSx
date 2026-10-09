package org.opentsx.model;

import java.security.SecureRandom;

/** ULID: 48-bit millisecond timestamp + 80 random bits, Crockford base32, 26 characters, lexicographically time-ordered. */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String next() {
        return of(System.currentTimeMillis(), RANDOM);
    }

    static String of(long epochMillis, java.util.Random random) {
        if (epochMillis < 0 || epochMillis >= (1L << 48)) {
            throw new IllegalArgumentException("timestamp out of ULID range: " + epochMillis);
        }
        char[] out = new char[26];
        long t = epochMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (t & 31)];
            t >>>= 5;
        }
        byte[] r = new byte[10];
        random.nextBytes(r);
        // 80 bits -> 16 base32 chars
        long hi = ((r[0] & 0xFFL) << 32) | ((r[1] & 0xFFL) << 24) | ((r[2] & 0xFFL) << 16) | ((r[3] & 0xFFL) << 8) | (r[4] & 0xFFL);
        long lo = ((r[5] & 0xFFL) << 32) | ((r[6] & 0xFFL) << 24) | ((r[7] & 0xFFL) << 16) | ((r[8] & 0xFFL) << 8) | (r[9] & 0xFFL);
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
