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
 * A13: direct marketer. Compares the grid feed-in with the schedule. A positive deviation is energy the marketer
 * has to sell additionally, a negative one energy it has to buy back. Revenue is feed-in times market price.
 * <p>
 * Events: {@code schedule_deviation} when |deviation| first exceeds {@code toleranceKw}, {@code schedule_back} when
 * it returns.
 */
public final class DirectMarketer extends Atom {

    private final double toleranceKw;
    private double extraKwh;
    private double shortKwh;
    private double revenueEur;
    private double deviating;

    public DirectMarketer(String id, Params params) {
        super(id, params);
        toleranceKw = params.d("toleranceKw", 100);
    }

    @Override
    public String type() {
        return "direct-marketer";
    }

    @Override
    public Phase phase() {
        return Phase.FLOWS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.flow("p_grid_kw", "kW"),
                PortSpec.signal("schedule_kw", "kW"),
                PortSpec.signal("price_eur_mwh", "EUR/MWh"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("deviation_kw", "kW"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("extra_kwh", extraKwh);
        s.put("short_kwh", shortKwh);
        s.put("revenue_eur", revenueEur);
        s.put("deviating", deviating);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        extraKwh = s.get("extra_kwh");
        shortKwh = s.get("short_kwh");
        revenueEur = s.get("revenue_eur");
        deviating = s.get("deviating");
    }

    @Override
    public void step(StepContext ctx) {
        double grid = ctx.inOr("p_grid_kw", 0);
        double price = ctx.inOr("price_eur_mwh", 0);
        revenueEur += Math.max(0, grid) * ctx.dtHours() / 1000.0 * price;
        OptionalDouble schedule = ctx.in("schedule_kw");
        if (schedule.isEmpty()) {
            return;
        }
        double dev = grid - schedule.getAsDouble();
        if (dev > 0) {
            extraKwh += dev * ctx.dtHours();
        } else {
            shortKwh += -dev * ctx.dtHours();
        }
        boolean now = Math.abs(dev) > toleranceKw;
        if (now && deviating == 0) {
            ctx.event("schedule_deviation", Map.of("deviation_kw", Electrolyzer.fmt(dev)));
        } else if (!now && deviating == 1) {
            ctx.event("schedule_back", Map.of("deviation_kw", Electrolyzer.fmt(dev)));
        }
        deviating = now ? 1 : 0;
        ctx.out("deviation_kw", dev);
    }
}
