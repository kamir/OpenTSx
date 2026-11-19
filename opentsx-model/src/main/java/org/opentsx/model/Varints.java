package org.opentsx.model;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * LEB128 varints as Avro writes them: unsigned varints for non-negative longs and zig-zag varints for any long.
 * A read names its input as {@code subject} in the {@link IllegalArgumentException} it throws.
 */
public final class Varints {

    private Varints() {
    }

    public static void writeUnsigned(ByteArrayOutputStream out, long v) {
        if (v < 0) {
            throw new IllegalArgumentException("varint: \"" + v + "\" is not allowed; expected a whole number from 0 to "
                    + Long.MAX_VALUE);
        }
        writeBits(out, v);
    }

    public static void writeZigZag(ByteArrayOutputStream out, long v) {
        writeBits(out, (v << 1) ^ (v >> 63));
    }

    public static long readUnsigned(ByteBuffer in, String subject) {
        long v = readBits(in, subject);
        if (v < 0) {
            throw new IllegalArgumentException(subject + ": varint exceeds " + Long.MAX_VALUE
                    + "; expected a whole number from 0 to " + Long.MAX_VALUE);
        }
        return v;
    }

    public static long readZigZag(ByteBuffer in, String subject) {
        long z = readBits(in, subject);
        return (z >>> 1) ^ -(z & 1);
    }

    private static void writeBits(ByteArrayOutputStream out, long z) {
        while ((z & ~0x7FL) != 0) {
            out.write((int) ((z & 0x7F) | 0x80));
            z >>>= 7;
        }
        out.write((int) z);
    }

    private static long readBits(ByteBuffer in, String subject) {
        long z = 0;
        int shift = 0;
        while (true) {
            if (!in.hasRemaining()) {
                throw new IllegalArgumentException(subject + ": input ends inside a varint; expected complete varints");
            }
            int b = in.get() & 0xFF;
            if (shift == 63 && (b & 0x7E) != 0) {
                throw new IllegalArgumentException(subject
                        + ": varint exceeds 64 bits; expected at most 64 bits per varint");
            }
            z |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return z;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException(subject
                        + ": varint longer than 10 bytes; expected at most 10 bytes per varint");
            }
        }
    }
}
