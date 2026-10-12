package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H7: firewood store in Ster. The stove draws {@code wood_ster_ph}; deliveries (or own felling) come as an
 * intervention on {@code delivery_ster_ph}, set for one step. Event {@code wood_low} below {@code lowSter}; the
 * stock may go negative ({@code missing_ster}), which flags that the burn schedule is not covered by the wood
 * actually stored.
 */
public final class WoodStore extends Atom {

    private final double lowSter;
    private final double priceEurSter;

    private double stockSter;
    private double burnedSter;
    private double deliveredSter;
    private double costEur;
    private double low;

    public WoodStore(String id, Params params) {
        super(id, params);
        lowSter = params.d("lowSter", 1);
        priceEurSter = params.d("priceEurSter", 90);
        stockSter = params.d("stock0Ster", 6);
    }

    @Override
    public String type() {
        return "wood-store";
    }

    @Override
    public Phase phase() {
        return Phase.FLOWS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("wood_ster_ph", "Ster/h"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("stock_ster", "Ster"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("delivery_ster_ph", 0.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("stock_ster", stockSter);
        s.put("burned_ster", burnedSter);
        s.put("delivered_ster", deliveredSter);
        s.put("cost_eur", costEur);
        s.put("missing_ster", Math.max(0, -stockSter));
        s.put("low", low);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        stockSter = s.get("stock_ster");
        burnedSter = s.get("burned_ster");
        deliveredSter = s.get("delivered_ster");
        costEur = s.get("cost_eur");
        low = s.get("low");
    }

    @Override
    public void step(StepContext ctx) {
        double dt = ctx.dtHours();
        double use = Math.max(0, ctx.inOr("wood_ster_ph", 0)) * dt;
        double in = Math.max(0, ctx.control("delivery_ster_ph")) * dt;
        if (in > 0) {
            ctx.event("delivery", Map.of("ster", Electrolyzer.fmt(in)));
        }
        burnedSter += use;
        deliveredSter += in;
        costEur += use * priceEurSter;
        stockSter += in - use;
        if (stockSter < lowSter && low == 0) {
            low = 1;
            ctx.event("wood_low", Map.of("stock_ster", Electrolyzer.fmt(stockSter)));
        } else if (stockSter >= lowSter) {
            low = 0;
        }
        ctx.out("stock_ster", stockSter);
    }
}
