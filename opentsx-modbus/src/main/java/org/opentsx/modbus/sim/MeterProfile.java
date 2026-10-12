package org.opentsx.modbus.sim;

import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.map.SimSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Simulated values for the points of a register map, from their {@code sim} blocks (see {@link SimSpec}).
 * Deterministic for a seed and a sequence of update instants. Points without a {@code sim} block stay at 0.
 * Order of evaluation: constant/sine, then clamp, then integrate (counters).
 */
public final class MeterProfile {

    private final RegisterMap map;
    private final Random random;
    private final Instant epoch;
    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Map<String, Double> integrals = new HashMap<>();
    private Instant last;

    public MeterProfile(RegisterMap map, long seed, Instant epoch) {
        this.map = map;
        this.random = new Random(seed);
        this.epoch = epoch;
    }

    /** Advances the profile to {@code t} and returns the engineering value of every simulated point. */
    public synchronized Map<String, Double> update(Instant t) {
        double secs = Duration.between(epoch, t).toNanos() / 1e9;
        double dt = last == null ? 0 : Math.max(0, Duration.between(last, t).toNanos() / 1e9);
        last = t;
        List<RegisterPoint> clamps = new ArrayList<>();
        List<RegisterPoint> integrators = new ArrayList<>();
        for (RegisterPoint p : map.points()) {
            SimSpec s = p.sim();
            if (s == null) {
                continue;
            }
            String profile = s.profile() == null ? "constant" : s.profile();
            switch (profile) {
                case "clamp": clamps.add(p); break;
                case "integrate": integrators.add(p); break;
                case "sine": values.put(p.name(), sine(s, secs)); break;
                default: values.put(p.name(), noise(nz(s.value(), 0), s));
            }
        }
        for (RegisterPoint p : clamps) {
            double src = values.getOrDefault(p.sim().source(), 0.0);
            values.put(p.name(), "negative".equals(p.sim().sign()) ? Math.max(0, -src) : Math.max(0, src));
        }
        for (RegisterPoint p : integrators) {
            SimSpec s = p.sim();
            double src = values.getOrDefault(s.source(), 0.0);
            double acc = integrals.getOrDefault(p.name(), nz(s.start(), 0)) + nz(s.factor(), 1) * src * dt;
            integrals.put(p.name(), acc);
            values.put(p.name(), acc);
        }
        return Map.copyOf(values);
    }

    /** Overrides an integrated counter (simulate a meter reset or a value close to overflow). */
    public synchronized void setCounter(String point, double value) {
        integrals.put(point, value);
        values.put(point, value);
    }

    private double sine(SimSpec s, double secs) {
        double period = s.period() == null ? 600 : RegisterMap.duration(s.period()).toNanos() / 1e9;
        double v = nz(s.mean(), 0) + nz(s.amplitude(), 0) * Math.sin(2 * Math.PI * (secs / period + nz(s.phase(), 0)));
        return noise(v, s);
    }

    private double noise(double v, SimSpec s) {
        double out = v + nz(s.noise(), 0) * random.nextGaussian();
        if (s.min() != null) {
            out = Math.max(s.min(), out);
        }
        if (s.max() != null) {
            out = Math.min(s.max(), out);
        }
        return out;
    }

    private static double nz(Double d, double dflt) {
        return d == null ? dflt : d;
    }
}
