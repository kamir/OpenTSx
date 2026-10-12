package org.opentsx.modbus.sim;

import org.opentsx.modbus.map.DataType;
import org.opentsx.modbus.map.RegisterCodec;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.protocol.FunctionCode;
import org.opentsx.modbus.protocol.ModbusExceptionResponse;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.Map;

/**
 * The register tables of a simulated device (coils, discrete inputs, holding and input registers). Only
 * {@linkplain #define defined} addresses are served; a read touching an undefined address is answered with
 * ILLEGAL_DATA_ADDRESS, as strict meters do. Thread-safe.
 */
public final class RegisterImage {

    private final Map<FunctionCode, int[]> words = new EnumMap<>(FunctionCode.class);
    private final Map<FunctionCode, BitSet> defined = new EnumMap<>(FunctionCode.class);

    public RegisterImage() {
        for (FunctionCode f : FunctionCode.values()) {
            words.put(f, new int[0x10000]);
            defined.put(f, new BitSet(0x10000));
        }
    }

    /** An image that serves exactly the addresses of the given points. */
    public static RegisterImage of(Iterable<RegisterPoint> points) {
        RegisterImage img = new RegisterImage();
        for (RegisterPoint p : points) {
            img.define(p.function(), p.address(), p.length());
        }
        return img;
    }

    public synchronized void define(FunctionCode f, int address, int count) {
        defined.get(f).set(address, address + count);
    }

    public synchronized void set(FunctionCode f, int address, int... values) {
        int[] t = words.get(f);
        for (int i = 0; i < values.length; i++) {
            t[address + i] = f.bits() ? (values[i] != 0 ? 1 : 0) : values[i] & 0xFFFF;
        }
    }

    /** Writes an engineering value of a point ({@code raw = (value - offset) / scale}); integer counters wrap. */
    public synchronized void write(RegisterPoint p, double value) {
        if (p.type() == DataType.BOOL) {
            set(p.function(), p.address(), value != 0 ? 1 : 0);
            return;
        }
        double raw = p.toRaw(value);
        if (p.type().integer()) {
            raw = Math.rint(raw);
            double lo = p.type().minRaw();
            double m = p.type().modulus();
            if (p.kind() == RegisterPoint.Kind.COUNTER) {
                raw = lo + ((raw - lo) % m + m) % m;
            } else {
                raw = Math.max(lo, Math.min(lo + m - 1, raw));
            }
        }
        set(p.function(), p.address(), RegisterCodec.encode(raw, p.type(), p.wordOrder(), p.byteSwap()));
    }

    /** Writes a raw register value of a point (tests: counter wrap/reset, special patterns). */
    public synchronized void writeRaw(RegisterPoint p, double raw) {
        set(p.function(), p.address(), RegisterCodec.encode(raw, p.type(), p.wordOrder(), p.byteSwap()));
    }

    public synchronized int[] readRegisters(FunctionCode f, int address, int quantity) throws ModbusExceptionResponse {
        check(f, address, quantity);
        int[] out = new int[quantity];
        System.arraycopy(words.get(f), address, out, 0, quantity);
        return out;
    }

    public synchronized boolean[] readBits(FunctionCode f, int address, int quantity) throws ModbusExceptionResponse {
        check(f, address, quantity);
        boolean[] out = new boolean[quantity];
        int[] t = words.get(f);
        for (int i = 0; i < quantity; i++) {
            out[i] = t[address + i] != 0;
        }
        return out;
    }

    private void check(FunctionCode f, int address, int quantity) throws ModbusExceptionResponse {
        if (quantity < 1 || quantity > f.maxQuantity()) {
            throw new ModbusExceptionResponse(f.code(), ModbusExceptionResponse.ILLEGAL_DATA_VALUE);
        }
        if (address + quantity > 0x10000) {
            throw new ModbusExceptionResponse(f.code(), ModbusExceptionResponse.ILLEGAL_DATA_ADDRESS);
        }
        BitSet d = defined.get(f);
        if (d.nextClearBit(address) < address + quantity) {
            throw new ModbusExceptionResponse(f.code(), ModbusExceptionResponse.ILLEGAL_DATA_ADDRESS);
        }
    }
}
