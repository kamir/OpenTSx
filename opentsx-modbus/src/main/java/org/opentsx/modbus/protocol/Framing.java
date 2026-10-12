package org.opentsx.modbus.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * How a PDU travels over a byte stream. {@link Mbap} is Modbus TCP; {@link Rtu} is the serial RTU frame (unit id,
 * PDU, CRC-16) - used here as "RTU over TCP" through serial device servers / converters. Serial RTU on a local port
 * would reuse {@link Rtu} on top of a serial stream (not implemented, see {@code SerialRtuTransport}).
 */
public interface Framing {

    enum Kind {
        TCP, RTU_OVER_TCP;

        public static Kind parse(String s) {
            String v = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            switch (v) {
                case "tcp": case "mbap":
                    return TCP;
                case "rtu-over-tcp": case "rtu":
                    return RTU_OVER_TCP;
                default:
                    throw new IllegalArgumentException("unknown framing '" + s + "' (expected tcp or rtu-over-tcp)");
            }
        }

        public Framing framing() {
            return this == TCP ? new Mbap() : new Rtu();
        }
    }

    /** True if responses carry the request's transaction id (MBAP); RTU has none. */
    boolean hasTransactionIds();

    byte[] encode(Frame frame);

    /** Reads one response frame (client side). */
    Frame readResponse(InputStream in) throws IOException;

    /** Reads one request frame (server side). */
    Frame readRequest(InputStream in) throws IOException;

    static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(buf, off + n, len - n);
            if (r < 0) {
                throw new java.io.EOFException("connection closed after " + n + " of " + len + " bytes");
            }
            n += r;
        }
    }

    /** Modbus TCP: MBAP header (transaction id, protocol id 0, length, unit id) + PDU. */
    final class Mbap implements Framing {

        public static final int MAX_PDU = 253;

        @Override
        public boolean hasTransactionIds() {
            return true;
        }

        @Override
        public byte[] encode(Frame f) {
            byte[] pdu = f.pdu();
            int len = pdu.length + 1;
            byte[] out = new byte[7 + pdu.length];
            out[0] = (byte) (f.transactionId() >>> 8);
            out[1] = (byte) f.transactionId();
            out[2] = 0;
            out[3] = 0;
            out[4] = (byte) (len >>> 8);
            out[5] = (byte) len;
            out[6] = (byte) f.unitId();
            System.arraycopy(pdu, 0, out, 7, pdu.length);
            return out;
        }

        @Override
        public Frame readResponse(InputStream in) throws IOException {
            return read(in);
        }

        @Override
        public Frame readRequest(InputStream in) throws IOException {
            return read(in);
        }

        static Frame read(InputStream in) throws IOException {
            byte[] h = new byte[7];
            readFully(in, h, 0, 7);
            int tid = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
            int pid = ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            int len = ((h[4] & 0xFF) << 8) | (h[5] & 0xFF);
            if (pid != 0) {
                throw new ModbusProtocolException("MBAP protocol id " + pid + " (expected 0) - not a Modbus TCP peer?");
            }
            if (len < 2 || len > MAX_PDU + 1) {
                throw new ModbusProtocolException("MBAP length " + len + " outside 2.." + (MAX_PDU + 1));
            }
            byte[] pdu = new byte[len - 1];
            readFully(in, pdu, 0, pdu.length);
            return new Frame(tid, h[6] & 0xFF, pdu);
        }
    }

    /**
     * RTU frame: unit id, PDU, CRC-16 (low byte first). There is no length field, so the length is derived from the
     * function code (and the byte count of read responses). Supported: 0x01-0x06, 0x0F, 0x10 and exception responses.
     */
    final class Rtu implements Framing {

        @Override
        public boolean hasTransactionIds() {
            return false;
        }

        @Override
        public byte[] encode(Frame f) {
            byte[] body = new byte[1 + f.pdu().length];
            body[0] = (byte) f.unitId();
            System.arraycopy(f.pdu(), 0, body, 1, f.pdu().length);
            return Crc16.append(body);
        }

        @Override
        public Frame readResponse(InputStream in) throws IOException {
            byte[] head = new byte[2];
            readFully(in, head, 0, 2);
            int fc = head[1] & 0xFF;
            byte[] rest;
            if ((fc & 0x80) != 0) {
                rest = new byte[1];
                readFully(in, rest, 0, 1);
            } else if (fc >= 1 && fc <= 4) {
                byte[] count = new byte[1];
                readFully(in, count, 0, 1);
                rest = new byte[1 + (count[0] & 0xFF)];
                rest[0] = count[0];
                readFully(in, rest, 1, rest.length - 1);
            } else if (fc == 5 || fc == 6 || fc == 0x0F || fc == 0x10) {
                rest = new byte[4];
                readFully(in, rest, 0, 4);
            } else {
                throw new ModbusProtocolException(String.format("RTU response with unsupported function 0x%02X", fc));
            }
            return finish(in, head, rest);
        }

        @Override
        public Frame readRequest(InputStream in) throws IOException {
            byte[] head = new byte[2];
            readFully(in, head, 0, 2);
            int fc = head[1] & 0xFF;
            byte[] rest;
            if (fc >= 1 && fc <= 6) {
                rest = new byte[4];
                readFully(in, rest, 0, 4);
            } else if (fc == 0x0F || fc == 0x10) {
                byte[] fixed = new byte[5];
                readFully(in, fixed, 0, 5);
                rest = new byte[5 + (fixed[4] & 0xFF)];
                System.arraycopy(fixed, 0, rest, 0, 5);
                readFully(in, rest, 5, rest.length - 5);
            } else {
                throw new ModbusProtocolException(String.format("RTU request with unsupported function 0x%02X", fc));
            }
            return finish(in, head, rest);
        }

        private static Frame finish(InputStream in, byte[] head, byte[] rest) throws IOException {
            byte[] frame = new byte[head.length + rest.length + 2];
            System.arraycopy(head, 0, frame, 0, 2);
            System.arraycopy(rest, 0, frame, 2, rest.length);
            readFully(in, frame, 2 + rest.length, 2);
            if (!Crc16.valid(frame)) {
                throw new ModbusProtocolException("RTU CRC mismatch (noise on the line, wrong baud rate at the converter?)");
            }
            byte[] pdu = new byte[1 + rest.length];
            pdu[0] = head[1];
            System.arraycopy(rest, 0, pdu, 1, rest.length);
            return new Frame(-1, head[0] & 0xFF, pdu);
        }
    }
}
