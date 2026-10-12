package org.opentsx.modbus.protocol;

/** The device answered with an exception response (function code | 0x80, exception code). */
public final class ModbusExceptionResponse extends ModbusException {

    public static final int ILLEGAL_FUNCTION = 0x01;
    public static final int ILLEGAL_DATA_ADDRESS = 0x02;
    public static final int ILLEGAL_DATA_VALUE = 0x03;
    public static final int SERVER_DEVICE_FAILURE = 0x04;
    public static final int ACKNOWLEDGE = 0x05;
    public static final int SERVER_DEVICE_BUSY = 0x06;
    public static final int MEMORY_PARITY_ERROR = 0x08;
    public static final int GATEWAY_PATH_UNAVAILABLE = 0x0A;
    public static final int GATEWAY_TARGET_FAILED = 0x0B;

    private final int function;
    private final int exceptionCode;

    public ModbusExceptionResponse(int function, int exceptionCode) {
        super(String.format("device answered function 0x%02X with exception 0x%02X %s: %s", function, exceptionCode,
                name(exceptionCode), hint(exceptionCode)));
        this.function = function;
        this.exceptionCode = exceptionCode;
    }

    public int function() {
        return function;
    }

    public int exceptionCode() {
        return exceptionCode;
    }

    public static String name(int code) {
        switch (code) {
            case ILLEGAL_FUNCTION: return "ILLEGAL_FUNCTION";
            case ILLEGAL_DATA_ADDRESS: return "ILLEGAL_DATA_ADDRESS";
            case ILLEGAL_DATA_VALUE: return "ILLEGAL_DATA_VALUE";
            case SERVER_DEVICE_FAILURE: return "SERVER_DEVICE_FAILURE";
            case ACKNOWLEDGE: return "ACKNOWLEDGE";
            case SERVER_DEVICE_BUSY: return "SERVER_DEVICE_BUSY";
            case MEMORY_PARITY_ERROR: return "MEMORY_PARITY_ERROR";
            case GATEWAY_PATH_UNAVAILABLE: return "GATEWAY_PATH_UNAVAILABLE";
            case GATEWAY_TARGET_FAILED: return "GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND";
            default: return "UNKNOWN";
        }
    }

    private static String hint(int code) {
        switch (code) {
            case ILLEGAL_FUNCTION: return "the device does not support this function (holding vs. input registers?)";
            case ILLEGAL_DATA_ADDRESS: return "address range not served (check the register map, 0- vs. 1-based addresses, gaps in merged blocks)";
            case ILLEGAL_DATA_VALUE: return "quantity or value rejected (block too long for this device? lower maxRegistersPerRequest)";
            case SERVER_DEVICE_FAILURE: return "the device failed while processing the request";
            case ACKNOWLEDGE: return "accepted, processing takes long";
            case SERVER_DEVICE_BUSY: return "device busy, retry later";
            case GATEWAY_PATH_UNAVAILABLE: return "the gateway has no path to the unit id";
            case GATEWAY_TARGET_FAILED: return "the gateway got no answer from the unit id (wrong unit id, wiring, serial settings?)";
            default: return "see the device manual";
        }
    }
}
