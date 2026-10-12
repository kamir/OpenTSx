package org.opentsx.twin;

/** The three operating modes; the atoms are the same, only the binding of inputs and policies differs. */
public enum Mode {
    /** live or historical observations drive the twin; it estimates what is not measured */
    SHADOW,
    /** historical inputs with a changed intervention log */
    WHAT_IF,
    /** forecasts drive the twin from a snapshot; policies may be replaced (e.g. an optimizer) */
    FORECAST
}
