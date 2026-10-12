package org.opentsx.twin;

import org.opentsx.twin.atoms.Appliances;
import org.opentsx.twin.atoms.Battery;
import org.opentsx.twin.atoms.BalanceNode;
import org.opentsx.twin.atoms.Building;
import org.opentsx.twin.atoms.DirectMarketer;
import org.opentsx.twin.atoms.Electrolyzer;
import org.opentsx.twin.atoms.ElectricityMeter;
import org.opentsx.twin.atoms.Generation;
import org.opentsx.twin.atoms.H2Line;
import org.opentsx.twin.atoms.H2Offtake;
import org.opentsx.twin.atoms.HotWater;
import org.opentsx.twin.atoms.Market;
import org.opentsx.twin.atoms.OilBoiler;
import org.opentsx.twin.atoms.OilTank;
import org.opentsx.twin.atoms.OperatingRules;
import org.opentsx.twin.atoms.Weather;
import org.opentsx.twin.atoms.WoodStore;
import org.opentsx.twin.atoms.WoodStove;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Standard parts: atom types the wiring plan can name. Energy-system parts (H2 chain) and household parts (house
 * twin: weather, building, boiler, stove, hot water, appliances, meter, oil tank, wood store) share one catalog.
 */
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
                .register("operating-rules", OperatingRules::new)
                .register("weather", Weather::new)
                .register("building", Building::new)
                .register("oil-boiler", OilBoiler::new)
                .register("wood-stove", WoodStove::new)
                .register("dhw", HotWater::new)
                .register("appliances", Appliances::new)
                .register("meter", ElectricityMeter::new)
                .register("oil-tank", OilTank::new)
                .register("wood-store", WoodStore::new);
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
