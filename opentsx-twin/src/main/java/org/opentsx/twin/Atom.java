package org.opentsx.twin;

import java.util.List;
import java.util.Map;

/**
 * An atomic element of the twin, split into model, views, dynamics and policy (MVC+P):
 * <ul>
 *   <li><b>Model:</b> {@link #state()} and the {@link Params}; {@link #restore(Map)} sets it back.</li>
 *   <li><b>Views:</b> the output ports and the recorded state series (see {@link RunResult}).</li>
 *   <li><b>Dynamics:</b> {@link #step(StepContext)} advances the state by one superstep. It is fixed per atom type.</li>
 *   <li><b>Policy:</b> decides the {@link #controlDefaults() controls} before each step. It is exchangeable
 *       (schedule, operator, optimizer); interventions override it.</li>
 * </ul>
 * Instances are created per run by the {@link AtomCatalog}, so a run never sees state from another run.
 */
public abstract class Atom {

    private final String id;
    protected final Params params;

    protected Atom(String id, Params params) {
        this.id = id;
        this.params = params;
    }

    public final String id() {
        return id;
    }

    public abstract String type();

    public abstract Phase phase();

    public abstract List<PortSpec> inputs();

    public abstract List<PortSpec> outputs();

    /** Controls a policy or an intervention may set, with their defaults. */
    public Map<String, Double> controlDefaults() {
        return Map.of();
    }

    /** State variables in a fixed order (copy). Cumulative variables double as KPIs. */
    public abstract Map<String, Double> state();

    public abstract void restore(Map<String, Double> state);

    /** Outputs before the first superstep, read by wires that deliver the previous superstep's value. */
    public Map<String, Double> restingOutputs() {
        return Map.of();
    }

    /**
     * Dynamics for the interval {@code [t, t + dt)}. Inputs and controls come from the context; outputs written to it
     * describe the end of the interval.
     */
    public abstract void step(StepContext ctx);

    /** Steps only every n-th superstep (multirate); outputs are held in between. */
    public int every() {
        return Math.max(1, params.i("every", 1));
    }

    /** Policy used unless the run or the plan names another one. */
    public String defaultPolicy() {
        return "follow";
    }

    public Params params() {
        return params;
    }
}
