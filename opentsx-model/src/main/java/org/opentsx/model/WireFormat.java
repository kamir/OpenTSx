package org.opentsx.model;

/** Payload framings OpenTSx can read. */
public enum WireFormat {
    /** Avro single-object encoding: {@code C3 01} + 8-byte little-endian CRC-64-AVRO fingerprint + body. Default. */
    SINGLE_OBJECT,
    /** Confluent Schema Registry framing: {@code 00} + 4-byte big-endian schema id + body. */
    CONFLUENT,
    UNKNOWN;

    public static WireFormat detect(byte[] payload) {
        if (payload != null && payload.length >= 10 && payload[0] == (byte) 0xC3 && payload[1] == 0x01) {
            return SINGLE_OBJECT;
        }
        if (payload != null && payload.length >= 5 && payload[0] == 0x00) {
            return CONFLUENT;
        }
        return UNKNOWN;
    }
}
