package org.opentsx.modbus;

import org.junit.jupiter.api.Test;
import org.opentsx.modbus.client.ModbusClient;
import org.opentsx.modbus.client.SerialRtuTransport;
import org.opentsx.modbus.client.TcpTransport;
import org.opentsx.modbus.map.DataType;
import org.opentsx.modbus.map.RegisterCodec;
import org.opentsx.modbus.map.WordOrder;
import org.opentsx.modbus.protocol.Framing;
import org.opentsx.modbus.protocol.FunctionCode;
import org.opentsx.modbus.protocol.ModbusException;
import org.opentsx.modbus.protocol.ModbusExceptionResponse;
import org.opentsx.modbus.protocol.ModbusTimeoutException;
import org.opentsx.modbus.sim.ModbusTcpServer;
import org.opentsx.modbus.sim.RegisterImage;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientServerTest {

    static RegisterImage image() {
        RegisterImage img = new RegisterImage();
        img.define(FunctionCode.READ_HOLDING_REGISTERS, 0, 10);
        img.define(FunctionCode.READ_INPUT_REGISTERS, 100, 4);
        img.define(FunctionCode.READ_COILS, 0, 16);
        img.define(FunctionCode.READ_DISCRETE_INPUTS, 0, 3);
        img.set(FunctionCode.READ_HOLDING_REGISTERS, 0, 1, 2, 3, 0xFFFF);
        img.set(FunctionCode.READ_INPUT_REGISTERS, 100, RegisterCodec.encode(49.98f, DataType.FLOAT32, WordOrder.BIG, false));
        img.set(FunctionCode.READ_COILS, 0, 1, 0, 1);
        img.set(FunctionCode.READ_DISCRETE_INPUTS, 2, 1);
        return img;
    }

    static TcpTransport.Settings settings(ModbusTcpServer s) {
        return TcpTransport.Settings.of(InetAddress.getLoopbackAddress().getHostAddress(), s.port())
                .withTimeout(Duration.ofMillis(300)).withRetries(1);
    }

    @Test
    void readsAllFourFunctions() throws Exception {
        try (ModbusTcpServer server = ModbusTcpServer.local(image());
             ModbusClient c = new ModbusClient(new TcpTransport(settings(server)))) {
            assertArrayEquals(new int[]{1, 2, 3, 0xFFFF}, c.readHoldingRegisters(1, 0, 4));
            int[] f = c.readInputRegisters(1, 100, 2);
            assertEquals(49.98f, RegisterCodec.decode(f, 0, DataType.FLOAT32, WordOrder.BIG, false), 0.0);
            assertArrayEquals(new boolean[]{true, false, true, false}, c.readCoils(1, 0, 4));
            assertArrayEquals(new boolean[]{false, false, true}, c.readDiscreteInputs(1, 0, 3));
            assertEquals(4, server.requests());
        }
    }

    @Test
    void exceptionResponsesAreMappedAndTheConnectionSurvives() throws Exception {
        try (ModbusTcpServer server = ModbusTcpServer.local(image());
             TcpTransport t = new TcpTransport(settings(server));
             ModbusClient c = new ModbusClient(t)) {
            ModbusExceptionResponse addr = assertThrows(ModbusExceptionResponse.class, () -> c.readHoldingRegisters(1, 8, 4));
            assertEquals(ModbusExceptionResponse.ILLEGAL_DATA_ADDRESS, addr.exceptionCode());
            assertEquals(3, addr.function());
            server.failNext(ModbusExceptionResponse.SERVER_DEVICE_BUSY);
            ModbusExceptionResponse busy = assertThrows(ModbusExceptionResponse.class, () -> c.readInputRegisters(1, 100, 2));
            assertTrue(busy.getMessage().contains("SERVER_DEVICE_BUSY"));
            assertEquals(3, c.readHoldingRegisters(1, 2, 1)[0]);
            assertEquals(1, t.counters()[1], "exception responses must not cost a reconnect");
        }
    }

    @Test
    void wrongUnitIdThroughAGateway() throws Exception {
        try (ModbusTcpServer server = new ModbusTcpServer(image(), Framing.Kind.TCP, 0, InetAddress.getLoopbackAddress(), 5);
             ModbusClient c = new ModbusClient(new TcpTransport(settings(server)))) {
            assertEquals(1, c.readHoldingRegisters(5, 0, 1)[0]);
            ModbusExceptionResponse e = assertThrows(ModbusExceptionResponse.class, () -> c.readHoldingRegisters(6, 0, 1));
            assertEquals(ModbusExceptionResponse.GATEWAY_TARGET_FAILED, e.exceptionCode());
        }
    }

    @Test
    void timeoutIsReportedAfterRetries() throws Exception {
        try (ModbusTcpServer server = ModbusTcpServer.local(image());
             TcpTransport t = new TcpTransport(settings(server));
             ModbusClient c = new ModbusClient(t)) {
            server.responseDelay(Duration.ofMillis(800));
            ModbusTimeoutException e = assertThrows(ModbusTimeoutException.class, () -> c.readHoldingRegisters(1, 0, 1));
            assertTrue(e.getMessage().contains("within 300 ms"), e.getMessage());
            assertEquals(2, t.counters()[2], "one timeout per attempt");
            server.responseDelay(Duration.ZERO);
            assertEquals(1, c.readHoldingRegisters(1, 0, 1)[0], "recovers with a fresh connection");
        }
    }

    @Test
    void reconnectsTransparentlyAfterTheConnectionDrops() throws Exception {
        try (ModbusTcpServer server = ModbusTcpServer.local(image());
             TcpTransport t = new TcpTransport(settings(server));
             ModbusClient c = new ModbusClient(t)) {
            assertEquals(1, c.readHoldingRegisters(1, 0, 1)[0]);
            server.dropConnections();
            Thread.sleep(50);
            assertEquals(2, c.readHoldingRegisters(1, 1, 1)[0]);
            assertEquals(2, t.counters()[1]);
        }
    }

    @Test
    void connectionRefusedIsAClearError() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        TcpTransport.Settings st = new TcpTransport.Settings(InetAddress.getLoopbackAddress().getHostAddress(), port,
                Framing.Kind.TCP, Duration.ofMillis(200), Duration.ofMillis(200), 1, Duration.ofMillis(10));
        try (ModbusClient c = new ModbusClient(new TcpTransport(st))) {
            ModbusException e = assertThrows(ModbusException.class, () -> c.readHoldingRegisters(1, 0, 1));
            assertTrue(e.getMessage().startsWith("connection to tcp://"), e.getMessage());
            assertTrue(e.getMessage().contains("attempt 2"), e.getMessage());
        }
    }

    @Test
    void rtuOverTcp() throws Exception {
        try (ModbusTcpServer server = new ModbusTcpServer(image(), Framing.Kind.RTU_OVER_TCP, 0, InetAddress.getLoopbackAddress(), 3);
             ModbusClient c = new ModbusClient(new TcpTransport(settings(server).withFraming(Framing.Kind.RTU_OVER_TCP)))) {
            assertArrayEquals(new int[]{1, 2, 3}, c.readHoldingRegisters(3, 0, 3));
            assertArrayEquals(new boolean[]{true, false, true}, c.readCoils(3, 0, 3));
            assertThrows(ModbusExceptionResponse.class, () -> c.readInputRegisters(3, 0, 1));
            // a silent slave (other unit id) means a timeout on RTU
            assertThrows(ModbusTimeoutException.class, () -> c.readHoldingRegisters(4, 0, 1));
            assertEquals(1, c.readHoldingRegisters(3, 0, 1)[0]);
        }
    }

    @Test
    void lateAnswersWithOlderTransactionIdsAreSkipped() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (java.net.Socket s = ss.accept()) {
                    Framing.Mbap mbap = new Framing.Mbap();
                    org.opentsx.modbus.protocol.Frame req = mbap.readRequest(s.getInputStream());
                    int tid = req.transactionId();
                    byte[] stale = mbap.encode(new org.opentsx.modbus.protocol.Frame(tid - 1, 1,
                            org.opentsx.modbus.protocol.Pdu.registersResponse(FunctionCode.READ_HOLDING_REGISTERS, new int[]{666})));
                    byte[] fresh = mbap.encode(new org.opentsx.modbus.protocol.Frame(tid, 1,
                            org.opentsx.modbus.protocol.Pdu.registersResponse(FunctionCode.READ_HOLDING_REGISTERS, new int[]{42})));
                    s.getOutputStream().write(stale);
                    s.getOutputStream().write(fresh);
                    s.getOutputStream().flush();
                    s.getInputStream().read();
                } catch (Exception ignored) {
                    // test peer
                }
            });
            peer.setDaemon(true);
            peer.start();
            TcpTransport.Settings st = TcpTransport.Settings.of(InetAddress.getLoopbackAddress().getHostAddress(), ss.getLocalPort())
                    .withTimeout(Duration.ofMillis(500)).withRetries(0);
            try (ModbusClient c = new ModbusClient(new TcpTransport(st))) {
                assertEquals(42, c.readHoldingRegisters(1, 0, 1)[0]);
            }
        }
    }

    @Test
    void serialRtuIsAnExplicitGap() {
        try (SerialRtuTransport t = new SerialRtuTransport("/dev/ttyUSB0", 9600, 'E', 1)) {
            assertThrows(UnsupportedOperationException.class, () -> t.exchange(1, new byte[]{3, 0, 0, 0, 1}));
        }
    }
}
