package org.opentsx.modbus.poll;

import java.time.Instant;
import java.util.List;

/** Result of one poll cycle: one reading per point (NaN for points of failed blocks) and the block errors. */
public record PollResult(Instant timestamp, List<RawReading> readings, List<BlockError> errors, long elapsedMillis) {

    public record BlockError(ReadBlock block, Exception error) {
        @Override
        public String toString() {
            return block + ": " + error.getMessage();
        }
    }

    public boolean ok() {
        return errors.isEmpty();
    }
}
