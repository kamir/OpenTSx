package org.opentsx.modbus.protocol;

/** One application frame: transaction id (MBAP only, -1 for RTU), unit id and PDU. */
public record Frame(int transactionId, int unitId, byte[] pdu) {
}
