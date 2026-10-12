package org.opentsx.modbus.normalize;

import org.opentsx.modbus.map.DataType;
import org.opentsx.modbus.map.RegisterPoint;

import java.util.HashMap;
import java.util.Map;

/**
 * Watches monotonic counters (points with {@code kind: counter}) across polls, on the raw register value.
 * <ul>
 *   <li>raw goes up or stays: {@link Quality#GOOD}</li>
 *   <li>integer type, previous value in the top quarter of the type's range and new value in the bottom quarter:
 *   {@link Quality#COUNTER_WRAP} (register overflow; the increment is {@code new - prev + 2^bits})</li>
 *   <li>any other decrease (or any decrease of a float counter): {@link Quality#COUNTER_RESET}</li>
 * </ul>
 * NaN values (failed reads) do not change the remembered value.
 */
public final class CounterTracker {

    private final Map<String, Double> last = new HashMap<>();

    public byte check(String seriesKey, RegisterPoint point, double raw) {
        if (point.kind() != RegisterPoint.Kind.COUNTER || Double.isNaN(raw)) {
            return Quality.GOOD;
        }
        Double prev = last.put(seriesKey, raw);
        if (prev == null || raw >= prev) {
            return Quality.GOOD;
        }
        DataType t = point.type();
        if (t.integer()) {
            double lo = t.minRaw();
            double m = t.modulus();
            if ((prev - lo) >= 0.75 * m && (raw - lo) < 0.25 * m) {
                return Quality.COUNTER_WRAP;
            }
        }
        return Quality.COUNTER_RESET;
    }

    /** Increment between two raw counter values, assuming at most one wrap; NaN for a reset. */
    public static double increment(RegisterPoint point, double prevRaw, double raw, byte quality) {
        if (quality == Quality.COUNTER_RESET) {
            return Double.NaN;
        }
        double d = raw - prevRaw;
        if (quality == Quality.COUNTER_WRAP) {
            d += point.type().modulus();
        }
        return d * point.scale();
    }
}
