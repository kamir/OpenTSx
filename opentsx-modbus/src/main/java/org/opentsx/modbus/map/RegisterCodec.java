package org.opentsx.modbus.map;

import java.nio.ByteBuffer;

/**
 * Decodes register words into numbers and back. Steps: take {@code type.registers()} words; swap the two bytes of
 * each word if {@code byteSwap}; reverse the word order if {@link WordOrder#LITTLE}; read the bytes big endian.
 */
public final class RegisterCodec {

    private static final double TWO_64 = 18446744073709551616.0;

    private RegisterCodec() {
    }

    private static byte[] bytes(int[] regs, int offset, int n, WordOrder order, boolean byteSwap) {
        if (offset < 0 || offset + n > regs.length) {
            throw new IllegalArgumentException("need " + n + " registers at offset " + offset + ", have " + regs.length);
        }
        byte[] b = new byte[2 * n];
        for (int i = 0; i < n; i++) {
            int reg = regs[offset + (order == WordOrder.LITTLE ? n - 1 - i : i)];
            byte hi = (byte) (reg >>> 8);
            byte lo = (byte) reg;
            b[2 * i] = byteSwap ? lo : hi;
            b[2 * i + 1] = byteSwap ? hi : lo;
        }
        return b;
    }

    /** The raw (unscaled) value of {@code type} starting at {@code regs[offset]}. */
    public static double decode(int[] regs, int offset, DataType type, WordOrder order, boolean byteSwap) {
        if (type == DataType.BOOL) {
            throw new IllegalArgumentException("bool points are read as bits, not registers");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes(regs, offset, type.registers(), order, byteSwap));
        switch (type) {
            case UINT16: return bb.getShort() & 0xFFFF;
            case INT16: return bb.getShort();
            case UINT32: return bb.getInt() & 0xFFFFFFFFL;
            case INT32: return bb.getInt();
            case UINT64: {
                long l = bb.getLong();
                return l >= 0 ? (double) l : (double) l + TWO_64;
            }
            case INT64: return bb.getLong();
            case FLOAT32: return bb.getFloat();
            case FLOAT64: return bb.getDouble();
            default: throw new IllegalStateException(type.name());
        }
    }

    /** Register words for a raw value; integers are rounded and must fit the type. */
    public static int[] encode(double raw, DataType type, WordOrder order, boolean byteSwap) {
        if (type == DataType.BOOL) {
            throw new IllegalArgumentException("bool points are written as bits, not registers");
        }
        int n = type.registers();
        ByteBuffer bb = ByteBuffer.allocate(2 * n);
        if (type.integer()) {
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                throw new IllegalArgumentException(type.yamlName() + " cannot hold " + raw);
            }
            double r = Math.rint(raw);
            double lo = type.minRaw();
            double hi = lo + type.modulus();
            if (r < lo || r >= hi) {
                throw new IllegalArgumentException(raw + " does not fit " + type.yamlName());
            }
            long bits = type == DataType.UINT64 && r >= 9.223372036854775807E18 ? (long) (r - TWO_64) : (long) r;
            switch (n) {
                case 1: bb.putShort((short) bits); break;
                case 2: bb.putInt((int) bits); break;
                default: bb.putLong(bits);
            }
        } else if (type == DataType.FLOAT32) {
            bb.putFloat((float) raw);
        } else {
            bb.putDouble(raw);
        }
        byte[] b = bb.array();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int hi = b[2 * i] & 0xFF;
            int lo = b[2 * i + 1] & 0xFF;
            int word = byteSwap ? (lo << 8) | hi : (hi << 8) | lo;
            out[order == WordOrder.LITTLE ? n - 1 - i : i] = word;
        }
        return out;
    }
}
