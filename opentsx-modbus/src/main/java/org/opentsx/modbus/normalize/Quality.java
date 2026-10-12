package org.opentsx.modbus.normalize;

/**
 * Per-value quality codes written into an episode's {@code quality} bytes. 0-4 are the codes of the wind-farm
 * data-point catalog ({@code channel-catalog.yaml}); 5 and 6 are added for meter counters.
 */
public final class Quality {

    public static final byte GOOD = 0;
    /** outside the point's min/max band (kept) */
    public static final byte IMPLAUSIBLE = 1;
    /** read failed (NaN) */
    public static final byte MISSING = 2;
    public static final byte SUBSTITUTED = 3;
    public static final byte QUESTIONABLE = 4;
    /** counter went down from the top of its range to the bottom: register overflow, increment still valid */
    public static final byte COUNTER_WRAP = 5;
    /** counter went down otherwise: meter reset or exchange; the increment across this value is unknown */
    public static final byte COUNTER_RESET = 6;

    private Quality() {
    }

    public static String name(byte q) {
        switch (q) {
            case GOOD: return "GOOD";
            case IMPLAUSIBLE: return "IMPLAUSIBLE";
            case MISSING: return "MISSING";
            case SUBSTITUTED: return "SUBSTITUTED";
            case QUESTIONABLE: return "QUESTIONABLE";
            case COUNTER_WRAP: return "COUNTER_WRAP";
            case COUNTER_RESET: return "COUNTER_RESET";
            default: return "Q" + q;
        }
    }
}
