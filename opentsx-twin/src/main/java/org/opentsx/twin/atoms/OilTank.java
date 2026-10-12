package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * H6: heating-oil tank, a stock in litres (the house counterpart of the H2 line). The boiler draws {@code fuel_lph};
 * deliveries come as an intervention on {@code delivery_lph} (set at the delivery time, released one step later;
 * see {@code HouseScenario.oilDelivery}). Balance:
 * <pre>level = level0 + delivered − consumed + shortfall − overfill</pre>
 * Events: {@code delivery}, {@code reorder_warning} (falls below {@code reorderL}), {@code tank_empty},
 * {@code overfill}. Sensor view: a bound gauge reading ({@code level_meas_l}) gives {@code residual_l}.
 */
public final class OilTank extends Atom {

    private final double capacityL;
    private final double reorderL;
    private final double priceEurL;

    private double levelL;
    private double consumedL;
    private double deliveredL;
    private double shortfallL;
    private double overfillL;
    private double costEur;
    private double warned;

    public OilTank(String id, Params params) {
        super(id, params);
        capacityL = params.d("capacityL", 4000);
        reorderL = params.d("reorderL", 600);
        priceEurL = params.d("priceEurL", 1.0);
        levelL = params.d("level0L", 2000);
        warned = levelL < reorderL ? 1 : 0;
    }

    @Override
    public String type() {
        return "oil-tank";
    }

    @Override
    public Phase phase() {
        return Phase.FLOWS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.flow("fuel_lph", "l/h"), PortSpec.signal("level_meas_l", "l"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("level_l", "l"), PortSpec.signal("residual_l", "l"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("delivery_lph", 0.0);
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("level_l", levelL);
        s.put("consumed_l", consumedL);
        s.put("delivered_l", deliveredL);
        s.put("shortfall_l", shortfallL);
        s.put("overfill_l", overfillL);
        s.put("cost_eur", costEur);
        s.put("warned", warned);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        levelL = s.get("level_l");
        consumedL = s.get("consumed_l");
        deliveredL = s.get("delivered_l");
        shortfallL = s.get("shortfall_l");
        overfillL = s.get("overfill_l");
        costEur = s.get("cost_eur");
        warned = s.get("warned");
    }

    @Override
    public Map<String, Double> restingOutputs() {
        return Map.of("level_l", levelL);
    }

    @Override
    public void step(StepContext ctx) {
        double dt = ctx.dtHours();
        double use = Math.max(0, ctx.inOr("fuel_lph", 0)) * dt;
        double in = Math.max(0, ctx.control("delivery_lph")) * dt;
        if (in > 0) {
            ctx.event("delivery", Map.of("litres", Electrolyzer.fmt(in)));
        }
        consumedL += use;
        deliveredL += in;
        costEur += use * priceEurL;
        double before = levelL;
        levelL += in - use;
        if (levelL < 0) {
            shortfallL += -levelL;
            levelL = 0;
            if (before > 0) {
                ctx.event("tank_empty", Map.of());
            }
        }
        if (levelL > capacityL) {
            overfillL += levelL - capacityL;
            ctx.event("overfill", Map.of("litres", Electrolyzer.fmt(levelL - capacityL)));
            levelL = capacityL;
        }
        if (levelL < reorderL && warned == 0) {
            warned = 1;
            ctx.event("reorder_warning", Map.of("level_l", Electrolyzer.fmt(levelL)));
        } else if (levelL >= reorderL) {
            warned = 0;
        }
        ctx.out("level_l", levelL);
        OptionalDouble meas = ctx.in("level_meas_l");
        if (meas.isPresent()) {
            ctx.out("residual_l", meas.getAsDouble() - levelL);
        }
    }
}
