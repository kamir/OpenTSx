package org.opentsx.modbus.protocol;

/**
 * CRC-16/MODBUS (polynomial 0xA001 reflected, initial value 0xFFFF). On the wire the low byte comes first.
 * Check value: {@code crc("123456789") == 0x4B37}.
 */
public final class Crc16 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ 0xA001 : c >>> 1;
            }
            TABLE[i] = c;
        }
    }

    private Crc16() {
    }

    public static int of(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = (crc >>> 8) ^ TABLE[(crc ^ data[i]) & 0xFF];
        }
        return crc;
    }

    public static int of(byte[] data) {
        return of(data, 0, data.length);
    }

    /** Appends the CRC (low byte first) to {@code data}. */
    public static byte[] append(byte[] data) {
        int crc = of(data);
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) (crc & 0xFF);
        out[data.length + 1] = (byte) (crc >>> 8);
        return out;
    }

    /** True if the last two bytes of {@code frame} are the CRC of the bytes before them. */
    public static boolean valid(byte[] frame) {
        if (frame.length < 3) {
            return false;
        }
        int crc = of(frame, 0, frame.length - 2);
        return (frame[frame.length - 2] & 0xFF) == (crc & 0xFF) && (frame[frame.length - 1] & 0xFF) == (crc >>> 8);
    }
}
