package org.opentsx.modbus.client;

import org.opentsx.modbus.protocol.FunctionCode;
import org.opentsx.modbus.protocol.ModbusException;
import org.opentsx.modbus.protocol.Pdu;

/**
 * Read functions 0x01-0x04 on top of a {@link ModbusTransport}. Exception responses surface as
 * {@link org.opentsx.modbus.protocol.ModbusExceptionResponse}, timeouts as
 * {@link org.opentsx.modbus.protocol.ModbusTimeoutException}.
 *
 * <pre>{@code
 * try (ModbusClient c = ModbusClient.tcp("10.0.0.5", 502)) {
 *     int[] regs = c.readInputRegisters(1, 0, 10);
 * }
 * }</pre>
 */
public final class ModbusClient implements AutoCloseable {

    private final ModbusTransport transport;

    public ModbusClient(ModbusTransport transport) {
        this.transport = transport;
    }

    public static ModbusClient tcp(String host, int port) {
        return new ModbusClient(new TcpTransport(TcpTransport.Settings.of(host, port)));
    }

    public ModbusTransport transport() {
        return transport;
    }

    public int[] readHoldingRegisters(int unitId, int address, int quantity) throws ModbusException {
        return readRegisters(FunctionCode.READ_HOLDING_REGISTERS, unitId, address, quantity);
    }

    public int[] readInputRegisters(int unitId, int address, int quantity) throws ModbusException {
        return readRegisters(FunctionCode.READ_INPUT_REGISTERS, unitId, address, quantity);
    }

    public boolean[] readCoils(int unitId, int address, int quantity) throws ModbusException {
        return readBits(FunctionCode.READ_COILS, unitId, address, quantity);
    }

    public boolean[] readDiscreteInputs(int unitId, int address, int quantity) throws ModbusException {
        return readBits(FunctionCode.READ_DISCRETE_INPUTS, unitId, address, quantity);
    }

    public int[] readRegisters(FunctionCode function, int unitId, int address, int quantity) throws ModbusException {
        byte[] response = transport.exchange(unitId, Pdu.readRequest(function, address, quantity));
        return Pdu.parseRegisters(response, function, quantity);
    }

    public boolean[] readBits(FunctionCode function, int unitId, int address, int quantity) throws ModbusException {
        byte[] response = transport.exchange(unitId, Pdu.readRequest(function, address, quantity));
        return Pdu.parseBits(response, function, quantity);
    }

    @Override
    public void close() {
        transport.close();
    }
}
