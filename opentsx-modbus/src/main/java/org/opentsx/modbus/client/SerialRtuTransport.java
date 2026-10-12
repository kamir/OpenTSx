package org.opentsx.modbus.client;

import org.opentsx.modbus.protocol.ModbusException;

/**
 * Placeholder for Modbus RTU on a local serial port (RS-485).
 *
 * <p>TODO: the JDK has no serial-port API. An implementation would open the port through a serial library (or an
 * OS device file configured with {@code stty}), reuse {@link org.opentsx.modbus.protocol.Framing.Rtu} for the frame
 * and CRC, and add the RTU inter-frame silence (3.5 character times). Until then use a serial device server /
 * converter in front of the meter and {@link TcpTransport} with framing {@code rtu-over-tcp} (or a gateway that
 * speaks Modbus TCP).
 */
public final class SerialRtuTransport implements ModbusTransport {

    private final String device;

    public SerialRtuTransport(String device, int baud, char parity, int stopBits) {
        this.device = device;
    }

    @Override
    public byte[] exchange(int unitId, byte[] requestPdu) throws ModbusException {
        throw new UnsupportedOperationException("serial Modbus RTU is not implemented (no serial library); use a "
                + "converter and framing rtu-over-tcp or tcp");
    }

    @Override
    public String endpoint() {
        return "rtu://" + device;
    }

    @Override
    public void close() {
    }
}
