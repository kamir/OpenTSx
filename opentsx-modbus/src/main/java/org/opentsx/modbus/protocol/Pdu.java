package org.opentsx.modbus.protocol;

/**
 * Protocol data units of the read functions 0x01-0x04 (request, response, exception response). Independent of the
 * framing (MBAP or RTU).
 */
public final class Pdu {

    private Pdu() {
    }

    /** A parsed read request (server side). */
    public record ReadRequest(int function, int address, int quantity) {
    }

    public static byte[] readRequest(FunctionCode function, int address, int quantity) {
        checkRange(function, address, quantity);
        return new byte[]{(byte) function.code(), (byte) (address >>> 8), (byte) address,
                (byte) (quantity >>> 8), (byte) quantity};
    }

    static void checkRange(FunctionCode function, int address, int quantity) {
        if (address < 0 || address > 0xFFFF) {
            throw new IllegalArgumentException("address " + address + " outside 0..65535");
        }
        if (quantity < 1 || quantity > function.maxQuantity()) {
            throw new IllegalArgumentException("quantity " + quantity + " outside 1.." + function.maxQuantity()
                    + " for " + function);
        }
        if (address + quantity > 0x10000) {
            throw new IllegalArgumentException("address " + address + " + quantity " + quantity + " exceeds 65536");
        }
    }

    /** Throws the matching {@link ModbusExceptionResponse} if {@code pdu} is an exception response to {@code function}. */
    public static void checkException(byte[] pdu, FunctionCode function) throws ModbusException {
        if (pdu.length < 1) {
            throw new ModbusProtocolException("empty PDU");
        }
        int fc = pdu[0] & 0xFF;
        if (fc == (function.code() | 0x80)) {
            if (pdu.length != 2) {
                throw new ModbusProtocolException("exception response of length " + pdu.length + " (expected 2)");
            }
            throw new ModbusExceptionResponse(function.code(), pdu[1] & 0xFF);
        }
        if (fc != function.code()) {
            throw new ModbusProtocolException(String.format("response function 0x%02X does not match request 0x%02X",
                    fc, function.code()));
        }
    }

    /** Register values (unsigned 16-bit) of a 0x03/0x04 response. */
    public static int[] parseRegisters(byte[] pdu, FunctionCode function, int quantity) throws ModbusException {
        checkException(pdu, function);
        if (function.bits()) {
            throw new IllegalArgumentException(function + " returns bits, not registers");
        }
        int expected = 2 * quantity;
        if (pdu.length < 2 || (pdu[1] & 0xFF) != expected || pdu.length != 2 + expected) {
            throw new ModbusProtocolException("register response has byte count " + (pdu.length < 2 ? -1 : pdu[1] & 0xFF)
                    + " and length " + pdu.length + ", expected byte count " + expected + " for " + quantity + " registers");
        }
        int[] out = new int[quantity];
        for (int i = 0; i < quantity; i++) {
            out[i] = ((pdu[2 + 2 * i] & 0xFF) << 8) | (pdu[3 + 2 * i] & 0xFF);
        }
        return out;
    }

    /** Bit values of a 0x01/0x02 response (LSB of the first byte is the first address). */
    public static boolean[] parseBits(byte[] pdu, FunctionCode function, int quantity) throws ModbusException {
        checkException(pdu, function);
        if (!function.bits()) {
            throw new IllegalArgumentException(function + " returns registers, not bits");
        }
        int expected = (quantity + 7) / 8;
        if (pdu.length < 2 || (pdu[1] & 0xFF) != expected || pdu.length != 2 + expected) {
            throw new ModbusProtocolException("bit response has length " + pdu.length + ", expected byte count " + expected);
        }
        boolean[] out = new boolean[quantity];
        for (int i = 0; i < quantity; i++) {
            out[i] = (pdu[2 + i / 8] & (1 << (i % 8))) != 0;
        }
        return out;
    }

    // ---- server side ----

    public static ReadRequest parseReadRequest(byte[] pdu) throws ModbusProtocolException {
        if (pdu.length != 5) {
            throw new ModbusProtocolException("read request PDU must have 5 bytes, got " + pdu.length);
        }
        return new ReadRequest(pdu[0] & 0xFF, ((pdu[1] & 0xFF) << 8) | (pdu[2] & 0xFF),
                ((pdu[3] & 0xFF) << 8) | (pdu[4] & 0xFF));
    }

    public static byte[] registersResponse(FunctionCode function, int[] registers) {
        byte[] out = new byte[2 + 2 * registers.length];
        out[0] = (byte) function.code();
        out[1] = (byte) (2 * registers.length);
        for (int i = 0; i < registers.length; i++) {
            out[2 + 2 * i] = (byte) (registers[i] >>> 8);
            out[3 + 2 * i] = (byte) registers[i];
        }
        return out;
    }

    public static byte[] bitsResponse(FunctionCode function, boolean[] bits) {
        int n = (bits.length + 7) / 8;
        byte[] out = new byte[2 + n];
        out[0] = (byte) function.code();
        out[1] = (byte) n;
        for (int i = 0; i < bits.length; i++) {
            if (bits[i]) {
                out[2 + i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return out;
    }

    public static byte[] exceptionResponse(int function, int exceptionCode) {
        return new byte[]{(byte) ((function | 0x80) & 0xFF), (byte) exceptionCode};
    }
}
