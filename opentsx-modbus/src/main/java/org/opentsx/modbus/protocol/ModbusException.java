package org.opentsx.modbus.protocol;

import java.io.IOException;

/** Base class of everything that can go wrong talking Modbus: exception responses, timeouts, broken frames, I/O. */
public class ModbusException extends IOException {

    public ModbusException(String message) {
        super(message);
    }

    public ModbusException(String message, Throwable cause) {
        super(message, cause);
    }
}
