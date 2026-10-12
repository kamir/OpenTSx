package org.opentsx.twin;

/**
 * Where a port value comes from, best first. A computed value carries the worst origin of the inputs it read in the
 * same superstep (taint propagation); a default used for a missing input counts as {@link #SYNTHETIC}.
 */
public enum Origin {
    /** measured by a meter or sensor */
    MEASURED,
    /** reported by a partner or a plan (schedule, operator log) */
    REPORTED,
    /** a forecast */
    FORECAST,
    /** estimated by a learned model (Smart Topic) */
    ESTIMATED,
    /** a parameter, profile or simulator value */
    SYNTHETIC;

    public static Origin worst(Origin a, Origin b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
