package org.opentsx.twin;

/** The "P" in MVC+P: sets an atom's controls before its dynamics run. Interventions override the result. */
@FunctionalInterface
public interface Policy {

    void decide(Atom atom, StepContext ctx);
}
