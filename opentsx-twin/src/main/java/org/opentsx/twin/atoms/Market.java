package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.List;
import java.util.Map;

/** A12: market price, stepping every market time unit (default 15 supersteps). */
public final class Market extends Atom {

    private double lastPrice = Double.NaN;

    public Market(String id, Params params) {
        super(id, params);
    }

    @Override
    public String type() {
        return "market";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public int every() {
        return Math.max(1, params.i("every", 15));
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("price_eur_mwh", "EUR/MWh"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("price_eur_mwh", "EUR/MWh"));
    }

    @Override
    public Map<String, Double> state() {
        return Map.of("price_eur_mwh", lastPrice);
    }

    @Override
    public void restore(Map<String, Double> s) {
        lastPrice = s.get("price_eur_mwh");
    }

    @Override
    public void step(StepContext ctx) {
        lastPrice = ctx.inOr("price_eur_mwh", params.d("defaultPrice", 0));
        ctx.out("price_eur_mwh", lastPrice);
    }
}
