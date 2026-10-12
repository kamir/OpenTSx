package org.opentsx.twin;

import org.opentsx.twin.atoms.Battery;
import org.opentsx.twin.atoms.BalanceNode;
import org.opentsx.twin.atoms.DirectMarketer;
import org.opentsx.twin.atoms.Electrolyzer;
import org.opentsx.twin.atoms.Generation;
import org.opentsx.twin.atoms.H2Line;
import org.opentsx.twin.atoms.H2Offtake;
import org.opentsx.twin.atoms.Market;
import org.opentsx.twin.atoms.OperatingRules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Standard parts: atom types the wiring plan can name. */
public final class AtomCatalog {

    private final Map<String, BiFunction<String, Params, Atom>> types = new LinkedHashMap<>();

    public static AtomCatalog standard() {
        return new AtomCatalog()
                .register("generation", Generation::new)
                .register("market", Market::new)
                .register("h2-offtake", H2Offtake::new)
                .register("electrolyzer", Electrolyzer::new)
                .register("battery", Battery::new)
                .register("balance", BalanceNode::new)
                .register("h2-line", H2Line::new)
                .register("direct-marketer", DirectMarketer::new)
                .register("operating-rules", OperatingRules::new);
    }

    public AtomCatalog register(String type, BiFunction<String, Params, Atom> factory) {
        types.put(type, factory);
        return this;
    }

    public Atom create(String type, String id, Params params) {
        BiFunction<String, Params, Atom> f = types.get(type);
        if (f == null) {
            throw new IllegalArgumentException("unknown atom type '" + type + "', known: " + types.keySet());
        }
        return f.apply(id, params);
    }

    public Set<String> types() {
        return types.keySet();
    }
}
