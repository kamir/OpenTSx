package org.opentsx.twin;

import org.opentsx.model.Micros;

import java.time.Instant;
import java.util.Objects;

/**
 * A decision that overrides a control of an atom from the superstep whose interval contains {@code tMicros} (or the
 * first superstep after it). It stays in force until another intervention on the same control releases it
 * ({@code value == null}).
 */
public record Intervention(long tMicros, String atom, String control, Double value, String author, String reason) {

    public Intervention {
        Objects.requireNonNull(atom, "atom");
        Objects.requireNonNull(control, "control");
        author = author == null ? "unknown" : author;
        reason = reason == null ? "" : reason;
    }

    public static Intervention set(Instant at, String atom, String control, double value, String author, String reason) {
        return new Intervention(Micros.of(at), atom, control, value, author, reason);
    }

    public static Intervention release(Instant at, String atom, String control, String author, String reason) {
        return new Intervention(Micros.of(at), atom, control, null, author, reason);
    }

    String canonical() {
        return tMicros + "|" + atom + "|" + control + "|" + (value == null ? "release" : Double.doubleToLongBits(value))
                + "|" + author + "|" + reason;
    }
}
