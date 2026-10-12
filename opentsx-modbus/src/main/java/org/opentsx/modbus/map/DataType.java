package org.opentsx.modbus.map;

import java.util.Locale;

/** Value types of a register map point and the number of 16-bit registers they occupy. */
public enum DataType {
    BOOL(1, 1, false),
    UINT16(1, 16, false),
    INT16(1, 16, true),
    UINT32(2, 32, false),
    INT32(2, 32, true),
    UINT64(4, 64, false),
    INT64(4, 64, true),
    FLOAT32(2, 32, true),
    FLOAT64(4, 64, true);

    private final int registers;
    private final int bits;
    private final boolean signed;

    DataType(int registers, int bits, boolean signed) {
        this.registers = registers;
        this.bits = bits;
        this.signed = signed;
    }

    /** Registers (or, for BOOL, bits) the type occupies. */
    public int registers() {
        return registers;
    }

    public boolean integer() {
        return this != BOOL && this != FLOAT32 && this != FLOAT64;
    }

    public boolean signed() {
        return signed;
    }

    /** Smallest raw value of an integer type (as double). */
    public double minRaw() {
        return signed ? -Math.pow(2, bits - 1) : 0;
    }

    /** Number of distinct raw values of an integer type: 2^bits (wraparound modulus of a counter). */
    public double modulus() {
        return Math.pow(2, bits);
    }

    public static DataType parse(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "bool": case "boolean": case "bit": return BOOL;
            case "uint16": case "u16": return UINT16;
            case "int16": case "i16": return INT16;
            case "uint32": case "u32": return UINT32;
            case "int32": case "i32": return INT32;
            case "uint64": case "u64": return UINT64;
            case "int64": case "i64": return INT64;
            case "float32": case "float": case "f32": return FLOAT32;
            case "float64": case "double": case "f64": return FLOAT64;
            default:
                throw new IllegalArgumentException("unknown type '" + s
                        + "' (expected bool, uint16, int16, uint32, int32, uint64, int64, float32, float64)");
        }
    }

    public String yamlName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
