package org.opentsx.store;

import java.util.EnumSet;
import java.util.Set;

public record StoreCapabilities(Set<Capability> capabilities) {

    public static StoreCapabilities of(Capability... c) {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        set.addAll(java.util.List.of(c));
        return new StoreCapabilities(set);
    }

    public boolean has(Capability c) {
        return capabilities.contains(c);
    }
}
