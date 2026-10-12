package org.opentsx.twin;

/** A typed port. Wires must connect ports of the same kind and unit. */
public record PortSpec(String name, PortKind kind, String unit) {

    public static PortSpec flow(String name, String unit) {
        return new PortSpec(name, PortKind.FLOW, unit);
    }

    public static PortSpec signal(String name, String unit) {
        return new PortSpec(name, PortKind.SIGNAL, unit);
    }
}
