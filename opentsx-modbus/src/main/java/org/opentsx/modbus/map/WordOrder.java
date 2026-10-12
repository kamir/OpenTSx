package org.opentsx.modbus.map;

import java.util.Locale;

/**
 * Order of the 16-bit words of a multi-register value. {@link #BIG}: most significant word first (the Modbus
 * convention, "ABCD"); {@link #LITTLE}: least significant word first ("CDAB"). Byte order inside a word is big
 * endian unless {@code byteSwap} is set ("BADC" / "DCBA").
 */
public enum WordOrder {
    BIG, LITTLE;

    public static WordOrder parse(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        switch (v) {
            case "big": case "high-first": case "msw-first": case "abcd":
                return BIG;
            case "little": case "low-first": case "lsw-first": case "cdab":
                return LITTLE;
            default:
                throw new IllegalArgumentException("unknown wordOrder '" + s + "' (expected big or little; byte order inside a word: byteSwap)");
        }
    }
}
