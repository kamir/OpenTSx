package org.opentsx.modbus.poll;

import java.time.Instant;

/**
 * One decoded value as read from the device, before normalization - the Modbus counterpart of the wind farm's
 * {@code RawReading}. {@code raw} is the decoded register value before {@code scale}/{@code offset} (counter
 * wraparound is judged on it); a failed read has {@code value = raw = NaN} and the error in {@code error}.
 */
public record RawReading(String device, String point, Instant timestamp, double value, double raw, String unit,
                         String error) {

    public boolean failed() {
        return error != null;
    }
}
