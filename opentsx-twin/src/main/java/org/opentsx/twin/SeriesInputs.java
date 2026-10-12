package org.opentsx.twin;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Regular series (sample-and-hold per superstep) bound to input ports. */
public final class SeriesInputs implements Inputs {

    private record Series(long startMicros, long intervalMicros, double[] values, Origin origin) {
    }

    private final Map<String, Series> bound = new TreeMap<>();

    public SeriesInputs bind(String atomPort, long startMicros, long intervalMicros, double[] values, Origin origin) {
        if (intervalMicros <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        bound.put(atomPort, new Series(startMicros, intervalMicros, values.clone(), origin));
        return this;
    }

    /** Binds a REGULAR episode, e.g. a measured series from a store or a Smart Topic forecast. */
    public SeriesInputs bind(String atomPort, Episode episode, Origin origin) {
        if (episode.getTimeEncoding() != TimeEncoding.REGULAR) {
            throw new IllegalArgumentException("only REGULAR episodes can be bound");
        }
        return bind(atomPort, Micros.of(episode.getTStart()), episode.getIntervalMicros(), Episodes.values(episode), origin);
    }

    /** A constant over all time. */
    public SeriesInputs constant(String atomPort, double value, Origin origin) {
        return bind(atomPort, Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, new double[]{value, value, value}, origin);
    }

    /** Same data, every series re-labelled with {@code origin} (e.g. replaying history as a forecast). */
    public SeriesInputs as(Origin origin) {
        SeriesInputs out = new SeriesInputs();
        bound.forEach((k, s) -> out.bound.put(k, new Series(s.startMicros, s.intervalMicros, s.values, origin)));
        return out;
    }

    /** This binding with the ports of {@code other} added or replaced. */
    public SeriesInputs with(SeriesInputs other) {
        SeriesInputs out = new SeriesInputs();
        out.bound.putAll(bound);
        out.bound.putAll(other.bound);
        return out;
    }

    @Override
    public Optional<Value> value(String atomPort, long tMicros) {
        Series s = bound.get(atomPort);
        if (s == null || tMicros < s.startMicros) {
            return Optional.empty();
        }
        long idx = (tMicros - s.startMicros) / s.intervalMicros;
        if (idx >= s.values.length || Double.isNaN(s.values[(int) idx])) {
            return Optional.empty();
        }
        return Optional.of(new Value(s.values[(int) idx], s.origin));
    }

    @Override
    public Set<String> ports() {
        return Collections.unmodifiableSet(bound.keySet());
    }

    @Override
    public String fingerprint() {
        long h = 0;
        for (Map.Entry<String, Series> e : bound.entrySet()) {
            Series s = e.getValue();
            ByteBuffer b = ByteBuffer.allocate(8 * (s.values.length + 4));
            b.putLong(h).putLong(s.startMicros).putLong(s.intervalMicros).putLong(s.origin.ordinal());
            for (double v : s.values) {
                b.putDouble(v);
            }
            h = XxHash64.hash(b.array(), XxHash64.hash(e.getKey().getBytes(StandardCharsets.UTF_8)));
        }
        return XxHash64.hex(h);
    }
}
