package org.opentsx.modbus.map;

import org.opentsx.modbus.protocol.FunctionCode;

import java.util.Map;

/**
 * One data point of a register map. {@code value = raw * scale + offset} in {@code unit}.
 *
 * @param address 0-based protocol address (after applying {@code addressBase})
 * @param kind    {@link Kind#COUNTER} for monotonic counters (energy); enables wraparound/reset detection
 * @param min     plausibility band (optional); values outside are flagged, not dropped
 */
public record RegisterPoint(String name, String metric, String description, int address, FunctionCode function,
                            DataType type, WordOrder wordOrder, boolean byteSwap, double scale, double offset,
                            String unit, String obis, String origin, Kind kind, Double min, Double max,
                            Map<String, String> tags, SimSpec sim) {

    public enum Kind { GAUGE, COUNTER }

    /** Registers (or bits) occupied. */
    public int length() {
        return type.registers();
    }

    /** Exclusive end address. */
    public int end() {
        return address + length();
    }

    public double toEngineering(double raw) {
        return raw * scale + offset;
    }

    public double toRaw(double value) {
        return (value - offset) / scale;
    }

    public boolean plausible(double value) {
        return (min == null || value >= min) && (max == null || value <= max);
    }
}
