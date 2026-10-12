package org.opentsx.modbus.protocol;

import java.util.Locale;

/** The read functions this module speaks, with the protocol limit on the quantity per request. */
public enum FunctionCode {
    READ_COILS(0x01, true, 2000, "coil"),
    READ_DISCRETE_INPUTS(0x02, true, 2000, "discrete"),
    READ_HOLDING_REGISTERS(0x03, false, 125, "holding"),
    READ_INPUT_REGISTERS(0x04, false, 125, "input");

    private final int code;
    private final boolean bits;
    private final int maxQuantity;
    private final String yamlName;

    FunctionCode(int code, boolean bits, int maxQuantity, String yamlName) {
        this.code = code;
        this.bits = bits;
        this.maxQuantity = maxQuantity;
        this.yamlName = yamlName;
    }

    public int code() {
        return code;
    }

    /** Coils and discrete inputs return bits; holding and input registers return 16-bit words. */
    public boolean bits() {
        return bits;
    }

    /** 125 registers or 2000 bits per request (Modbus Application Protocol V1.1b3, 6.1-6.4). */
    public int maxQuantity() {
        return maxQuantity;
    }

    /** Name used in register maps: {@code coil}, {@code discrete}, {@code holding}, {@code input}. */
    public String yamlName() {
        return yamlName;
    }

    public static FunctionCode ofCode(int code) {
        for (FunctionCode f : values()) {
            if (f.code == code) {
                return f;
            }
        }
        throw new IllegalArgumentException("unsupported function code " + code);
    }

    /** Accepts {@code holding}, {@code input}, {@code coil}, {@code discrete} (also with -register/-input suffixes) or 1-4. */
    public static FunctionCode parse(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        switch (v) {
            case "1": case "01": case "coil": case "coils":
                return READ_COILS;
            case "2": case "02": case "discrete": case "discrete-input": case "discrete-inputs":
                return READ_DISCRETE_INPUTS;
            case "3": case "03": case "holding": case "holding-register": case "holding-registers":
                return READ_HOLDING_REGISTERS;
            case "4": case "04": case "input": case "input-register": case "input-registers":
                return READ_INPUT_REGISTERS;
            default:
                throw new IllegalArgumentException("unknown function '" + s + "' (expected holding, input, coil, discrete or 1-4)");
        }
    }
}
