package org.opentsx.modbus;

import org.junit.jupiter.api.Test;
import org.opentsx.modbus.protocol.Crc16;
import org.opentsx.modbus.protocol.Frame;
import org.opentsx.modbus.protocol.Framing;
import org.opentsx.modbus.protocol.FunctionCode;
import org.opentsx.modbus.protocol.ModbusExceptionResponse;
import org.opentsx.modbus.protocol.ModbusProtocolException;
import org.opentsx.modbus.protocol.Pdu;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    private static byte[] hex(String s) {
        String[] p = s.trim().split("\\s+");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) {
            b[i] = (byte) Integer.parseInt(p[i], 16);
        }
        return b;
    }

    @Test
    void crc16Vectors() {
        assertEquals(0x4B37, Crc16.of("123456789".getBytes(StandardCharsets.US_ASCII)));
        // read 10 holding registers from unit 1: 01 03 00 00 00 0A C5 CD
        assertArrayEquals(hex("01 03 00 00 00 0A C5 CD"), Crc16.append(hex("01 03 00 00 00 0A")));
        // Modbus over serial line spec example: unit 0x11, read 3 holding registers from 0x006B
        assertArrayEquals(hex("11 03 00 6B 00 03 76 87"), Crc16.append(hex("11 03 00 6B 00 03")));
        assertArrayEquals(hex("01 04 00 00 00 01 31 CA"), Crc16.append(hex("01 04 00 00 00 01")));
        assertTrue(Crc16.valid(hex("01 03 00 00 00 0A C5 CD")));
        assertFalse(Crc16.valid(hex("01 03 00 00 00 0B C5 CD")));
    }

    @Test
    void mbapRequestLayout() {
        byte[] pdu = Pdu.readRequest(FunctionCode.READ_HOLDING_REGISTERS, 0x006B, 3);
        byte[] frame = new Framing.Mbap().encode(new Frame(0x0102, 0x11, pdu));
        assertArrayEquals(hex("01 02 00 00 00 06 11 03 00 6B 00 03"), frame);
    }

    @Test
    void mbapResponseParsing() throws Exception {
        byte[] wire = hex("01 02 00 00 00 09 11 03 06 02 2B 00 00 00 64");
        Frame f = new Framing.Mbap().readResponse(new ByteArrayInputStream(wire));
        assertEquals(0x0102, f.transactionId());
        assertEquals(0x11, f.unitId());
        assertArrayEquals(new int[]{0x022B, 0, 100}, Pdu.parseRegisters(f.pdu(), FunctionCode.READ_HOLDING_REGISTERS, 3));
    }

    @Test
    void exceptionResponsesBecomeClearErrors() throws Exception {
        byte[] wire = hex("00 07 00 00 00 03 01 84 02");
        Frame f = new Framing.Mbap().readResponse(new ByteArrayInputStream(wire));
        ModbusExceptionResponse e = assertThrows(ModbusExceptionResponse.class,
                () -> Pdu.parseRegisters(f.pdu(), FunctionCode.READ_INPUT_REGISTERS, 2));
        assertEquals(ModbusExceptionResponse.ILLEGAL_DATA_ADDRESS, e.exceptionCode());
        assertEquals(4, e.function());
        assertTrue(e.getMessage().contains("ILLEGAL_DATA_ADDRESS"), e.getMessage());
        ModbusExceptionResponse gw = assertThrows(ModbusExceptionResponse.class,
                () -> Pdu.parseRegisters(hex("83 0B"), FunctionCode.READ_HOLDING_REGISTERS, 1));
        assertTrue(gw.getMessage().contains("unit id"), gw.getMessage());
    }

    @Test
    void malformedFramesAreProtocolErrors() {
        assertThrows(ModbusProtocolException.class,
                () -> new Framing.Mbap().readResponse(new ByteArrayInputStream(hex("00 01 00 05 00 03 01 03 00"))));
        assertThrows(ModbusProtocolException.class,
                () -> new Framing.Mbap().readResponse(new ByteArrayInputStream(hex("00 01 00 00 01 00 01 03"))));
        assertThrows(EOFException.class,
                () -> new Framing.Mbap().readResponse(new ByteArrayInputStream(hex("00 01 00 00 00 05 01 03"))));
        // wrong byte count / function
        assertThrows(ModbusProtocolException.class,
                () -> Pdu.parseRegisters(hex("03 04 00 01"), FunctionCode.READ_HOLDING_REGISTERS, 2));
        assertThrows(ModbusProtocolException.class,
                () -> Pdu.parseRegisters(hex("04 02 00 01"), FunctionCode.READ_HOLDING_REGISTERS, 1));
    }

    @Test
    void rtuFramesWithCrc() throws Exception {
        Framing rtu = new Framing.Rtu();
        byte[] req = rtu.encode(new Frame(-1, 0x11, Pdu.readRequest(FunctionCode.READ_HOLDING_REGISTERS, 0x006B, 3)));
        assertArrayEquals(hex("11 03 00 6B 00 03 76 87"), req);
        Frame parsedReq = rtu.readRequest(new ByteArrayInputStream(req));
        assertEquals(new Pdu.ReadRequest(3, 0x6B, 3), Pdu.parseReadRequest(parsedReq.pdu()));

        byte[] resp = Crc16.append(hex("11 03 06 AE 41 56 52 43 40"));
        Frame f = rtu.readResponse(new ByteArrayInputStream(resp));
        assertEquals(0x11, f.unitId());
        assertArrayEquals(new int[]{0xAE41, 0x5652, 0x4340}, Pdu.parseRegisters(f.pdu(), FunctionCode.READ_HOLDING_REGISTERS, 3));

        byte[] exc = Crc16.append(hex("11 83 02"));
        Frame fe = rtu.readResponse(new ByteArrayInputStream(exc));
        assertThrows(ModbusExceptionResponse.class, () -> Pdu.parseRegisters(fe.pdu(), FunctionCode.READ_HOLDING_REGISTERS, 3));

        resp[4] ^= 0x01;
        ModbusProtocolException crc = assertThrows(ModbusProtocolException.class, () -> rtu.readResponse(new ByteArrayInputStream(resp)));
        assertTrue(crc.getMessage().contains("CRC"));
    }

    @Test
    void bitsPackLsbFirst() throws Exception {
        boolean[] bits = {true, false, true, true, false, false, true, true, true, false};
        byte[] pdu = Pdu.bitsResponse(FunctionCode.READ_COILS, bits);
        assertArrayEquals(hex("01 02 CD 01"), pdu);
        assertArrayEquals(bits, Pdu.parseBits(pdu, FunctionCode.READ_COILS, bits.length));
    }

    @Test
    void requestLimits() {
        assertThrows(IllegalArgumentException.class, () -> Pdu.readRequest(FunctionCode.READ_HOLDING_REGISTERS, 0, 126));
        assertThrows(IllegalArgumentException.class, () -> Pdu.readRequest(FunctionCode.READ_INPUT_REGISTERS, 65535, 2));
        assertEquals(5, Pdu.readRequest(FunctionCode.READ_COILS, 0, 2000).length);
    }
}
