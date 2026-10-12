package org.opentsx.modbus.protocol;

/** No (complete) response within the configured timeout. */
public final class ModbusTimeoutException extends ModbusException {

    public ModbusTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
