package org.opentsx.twin;

import org.opentsx.twin.atoms.Electrolyzer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Named policies. */
public final class Policies {

    private final Map<String, Policy> byName = new LinkedHashMap<>();

    public static Policies standard() {
        Policies p = new Policies();
        p.register("follow", Policies::follow);
        p.register("hold", (atom, ctx) -> atom.controlDefaults().forEach(ctx::setControl));
        p.register("pressure-aware", Electrolyzer::pressureAware);
        return p;
    }

    /** Each control takes the value of the input port with the same name, or its default. */
    public static void follow(Atom atom, StepContext ctx) {
        atom.controlDefaults().forEach((key, dflt) -> ctx.setControl(key, ctx.has(key) ? ctx.in(key).getAsDouble() : dflt));
    }

    public Policies register(String name, Policy policy) {
        byName.put(name, policy);
        return this;
    }

    public Policy get(String name) {
        Policy p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException("unknown policy '" + name + "', known: " + byName.keySet());
        }
        return p;
    }
}
