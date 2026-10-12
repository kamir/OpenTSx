package org.opentsx.twin;

import java.util.Optional;
import java.util.Set;

/**
 * External values bound to input ports that have no wire: measurements, schedules, forecasts. The binding decides
 * the mode, not the atoms.
 */
public interface Inputs {

    /** Value for {@code "atom.port"} in the superstep starting at {@code tMicros}; empty if none (or NaN). */
    Optional<Value> value(String atomPort, long tMicros);

    /** Bound ports, {@code "atom.port"}. */
    Set<String> ports();

    /** Stable hash of the bound data, part of every run id. */
    String fingerprint();

    Inputs NONE = new Inputs() {
        @Override
        public Optional<Value> value(String atomPort, long tMicros) {
            return Optional.empty();
        }

        @Override
        public Set<String> ports() {
            return Set.of();
        }

        @Override
        public String fingerprint() {
            return "none";
        }
    };
}
