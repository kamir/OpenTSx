package org.opentsx.stats;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Unsigned LEB128 varints and zig-zag varints (as Avro {@code long}). */
final class Varints {

    private Varints() {
    }

    static void write(ByteArrayOutputStream out, long v) {
        if (v < 0) {
            throw new IllegalArgumentException("negative varint " + v);
        }
        writeUnsigned(out, v);
    }

    private static void writeUnsigned(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    static void writeZigZag(ByteArrayOutputStream out, long v) {
        writeUnsigned(out, (v << 1) ^ (v >> 63));
    }

    static long read(ByteBuffer in) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = in.get();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("varint too long");
            }
        }
    }

    static long readZigZag(ByteBuffer in) {
        long v = read(in);
        return (v >>> 1) ^ -(v & 1);
    }
}
