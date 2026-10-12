package org.opentsx.modbus.protocol;

/** A frame that violates the protocol: bad header, wrong length, CRC mismatch, unexpected function code. */
public final class ModbusProtocolException extends ModbusException {

    public ModbusProtocolException(String message) {
        super(message);
    }
}
