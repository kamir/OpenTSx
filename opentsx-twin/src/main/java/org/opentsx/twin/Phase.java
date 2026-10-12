package org.opentsx.twin;

/**
 * Phases of one superstep, in execution order. A wire from an earlier phase to a later one delivers the value of
 * the same superstep; any other wire (same or earlier phase) delivers the value of the previous superstep. Feedback
 * loops therefore always cross a superstep boundary.
 */
public enum Phase {
    /** exogenous values: market, offtake, meters */
    SOURCES,
    /** standalone policy atoms (schedules, optimizer) */
    POLICY,
    /** controllable assets decide their operating point: generation, electrolyzer, storage */
    ASSETS,
    /** power balance at the grid connection point, solved within the superstep */
    BALANCE,
    /** stocks and accounts integrate: H2 line, marketing */
    FLOWS,
    /** derived views only */
    VIEWS
}
