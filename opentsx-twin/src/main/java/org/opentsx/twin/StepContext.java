package org.opentsx.twin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** What an atom sees during one superstep: inputs, controls, time; collects outputs and events. */
public final class StepContext {

    private final String atom;
    private final int k;
    private final long tMicros;
    private final double dtSeconds;
    private final Map<String, Value> inputs;
    private final Set<String> delayedPorts;
    private final Map<String, Double> controls = new HashMap<>();
    private final Map<String, Value> outputs = new LinkedHashMap<>();
    private final List<TwinEvent> events = new ArrayList<>();
    private Origin worst;

    StepContext(String atom, int k, long tMicros, double dtSeconds, Map<String, Value> inputs, Set<String> delayedPorts) {
        this.atom = atom;
        this.k = k;
        this.tMicros = tMicros;
        this.dtSeconds = dtSeconds;
        this.inputs = inputs;
        this.delayedPorts = delayedPorts;
    }

    public int k() {
        return k;
    }

    public long tMicros() {
        return tMicros;
    }

    public double dtSeconds() {
        return dtSeconds;
    }

    public double dtHours() {
        return dtSeconds / 3600.0;
    }

    public double dtMinutes() {
        return dtSeconds / 60.0;
    }

    public boolean has(String port) {
        return inputs.containsKey(port);
    }

    /**
     * Reads an input. Values over same-superstep wires and external bindings pass their origin on; values over
     * delayed (feedback) wires carry model state and do not.
     */
    public OptionalDouble in(String port) {
        Value v = inputs.get(port);
        if (v == null) {
            return OptionalDouble.empty();
        }
        if (!delayedPorts.contains(port)) {
            worst = Origin.worst(worst, v.origin());
        }
        return OptionalDouble.of(v.v());
    }

    /** Reads an input or falls back to a default, which taints the outputs as {@link Origin#SYNTHETIC}. */
    public double inOr(String port, double dflt) {
        OptionalDouble v = in(port);
        if (v.isPresent()) {
            return v.getAsDouble();
        }
        worst = Origin.worst(worst, Origin.SYNTHETIC);
        return dflt;
    }

    public double control(String key) {
        Double v = controls.get(key);
        return v == null ? Double.NaN : v;
    }

    /** Used by policies (before the step) and by the runner for interventions. */
    public void setControl(String key, double value) {
        controls.put(key, value);
    }

    public void out(String port, double value) {
        outputs.put(port, new Value(value, worst == null ? Origin.SYNTHETIC : worst));
    }

    /** Writes an output with an explicit origin, e.g. a rule from a plan ({@link Origin#REPORTED}). */
    public void out(String port, double value, Origin origin) {
        outputs.put(port, new Value(value, Origin.worst(worst, origin)));
    }

    public void event(String type, long atMicros, Map<String, String> attrs) {
        events.add(new TwinEvent(atMicros, k, atom, type, attrs));
    }

    public void event(String type, Map<String, String> attrs) {
        event(type, tMicros, attrs);
    }

    Map<String, Value> outputs() {
        return outputs;
    }

    List<TwinEvent> events() {
        return events;
    }
}
