package org.opentsx.modbus;

import org.junit.jupiter.api.Test;
import org.opentsx.modbus.map.DataType;
import org.opentsx.modbus.map.RegisterCodec;
import org.opentsx.modbus.map.WordOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegisterCodecTest {

    private static final double[][] SAMPLES = {
            /* uint16 */ {0, 1, 4711, 65535},
            /* int16 */ {-32768, -1, 0, 1, 32767},
            /* uint32 */ {0, 65536, 305419896, 4294967295.0},
            /* int32 */ {-2147483648.0, -123456789, 0, 2147483647},
            /* uint64 */ {0, 1e15, 9007199254740992.0, 1.8446744073709550e19},
            /* int64 */ {-9.223372036854775808E18, -1e15, 0, 4503599627370496.0},
            /* float32 */ {0, -0.5, 123.456f, 3.4e38f, Float.MIN_VALUE},
            /* float64 */ {0, -1e-300, 98765432.123456789, Double.MAX_VALUE},
    };
    private static final DataType[] TYPES = {DataType.UINT16, DataType.INT16, DataType.UINT32, DataType.INT32,
            DataType.UINT64, DataType.INT64, DataType.FLOAT32, DataType.FLOAT64};

    @Test
    void everyTypeWordOrderAndByteSwapRoundTrips() {
        for (int t = 0; t < TYPES.length; t++) {
            for (WordOrder order : WordOrder.values()) {
                for (boolean swap : new boolean[]{false, true}) {
                    for (double v : SAMPLES[t]) {
                        int[] regs = RegisterCodec.encode(v, TYPES[t], order, swap);
                        assertEquals(TYPES[t].registers(), regs.length);
                        double back = RegisterCodec.decode(regs, 0, TYPES[t], order, swap);
                        assertEquals(v, back, 0.0, TYPES[t] + " " + order + " swap=" + swap + " " + v);
                    }
                }
            }
        }
    }

    @Test
    void float32KnownLayouts() {
        // 123.456f = 0x42F6E979
        assertArrayEquals(new int[]{0x42F6, 0xE979}, RegisterCodec.encode(123.456f, DataType.FLOAT32, WordOrder.BIG, false));
        assertArrayEquals(new int[]{0xE979, 0x42F6}, RegisterCodec.encode(123.456f, DataType.FLOAT32, WordOrder.LITTLE, false));
        assertArrayEquals(new int[]{0xF642, 0x79E9}, RegisterCodec.encode(123.456f, DataType.FLOAT32, WordOrder.BIG, true));
        assertArrayEquals(new int[]{0x79E9, 0xF642}, RegisterCodec.encode(123.456f, DataType.FLOAT32, WordOrder.LITTLE, true));
        assertEquals(123.456f, RegisterCodec.decode(new int[]{0, 0x42F6, 0xE979}, 1, DataType.FLOAT32, WordOrder.BIG, false), 0.0);
    }

    @Test
    void integerKnownLayouts() {
        assertArrayEquals(new int[]{0x1234, 0x5678}, RegisterCodec.encode(0x12345678, DataType.UINT32, WordOrder.BIG, false));
        assertArrayEquals(new int[]{0x5678, 0x1234}, RegisterCodec.encode(0x12345678, DataType.UINT32, WordOrder.LITTLE, false));
        assertArrayEquals(new int[]{0xFFFF}, RegisterCodec.encode(-1, DataType.INT16, WordOrder.BIG, false));
        assertEquals(65535, RegisterCodec.decode(new int[]{0xFFFF}, 0, DataType.UINT16, WordOrder.BIG, false));
        assertEquals(-1, RegisterCodec.decode(new int[]{0xFFFF}, 0, DataType.INT16, WordOrder.BIG, false));
        assertEquals(0x3412, RegisterCodec.decode(new int[]{0x1234}, 0, DataType.UINT16, WordOrder.BIG, true));
        assertEquals(-2, RegisterCodec.decode(new int[]{0xFFFF, 0xFFFE}, 0, DataType.INT32, WordOrder.BIG, false));
        assertEquals(-65537, RegisterCodec.decode(new int[]{0xFFFF, 0xFFFE}, 0, DataType.INT32, WordOrder.LITTLE, false));
        assertArrayEquals(new int[]{0x0001, 0x0002, 0x0003, 0x0004},
                RegisterCodec.encode(0x0001000200030004L, DataType.INT64, WordOrder.BIG, false));
        assertArrayEquals(new int[]{0x0004, 0x0003, 0x0002, 0x0001},
                RegisterCodec.encode(0x0001000200030004L, DataType.INT64, WordOrder.LITTLE, false));
        assertEquals(1.8446744073709552E19 - 1, RegisterCodec.decode(new int[]{0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF}, 0,
                DataType.UINT64, WordOrder.BIG, false), 4096.0);
    }

    @Test
    void outOfRangeAndShortInputAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.encode(65536, DataType.UINT16, WordOrder.BIG, false));
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.encode(-1, DataType.UINT32, WordOrder.BIG, false));
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.encode(Double.NaN, DataType.INT32, WordOrder.BIG, false));
        assertThrows(IllegalArgumentException.class, () -> RegisterCodec.decode(new int[]{1}, 0, DataType.FLOAT32, WordOrder.BIG, false));
    }
}
