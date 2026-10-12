package org.opentsx.modbus.client;

import org.opentsx.modbus.protocol.ModbusException;

/** Sends one request PDU to a unit and returns the response PDU (which may be an exception response). */
public interface ModbusTransport extends AutoCloseable {

    byte[] exchange(int unitId, byte[] requestPdu) throws ModbusException;

    /** Human-readable endpoint for messages, e.g. {@code tcp://10.0.0.5:502}. */
    String endpoint();

    @Override
    void close();
}
