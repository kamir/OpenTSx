package org.opentsx.twin;

/** Flows are conserved quantities exchanged between atoms; signals are not. Stocks stay inside an atom's state. */
public enum PortKind {
    FLOW,
    SIGNAL
}
