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
 * A5/A6 for a household: the electricity meter as balance node. {@code p_import_kw = p_appl_kw + p_heating_kw}
 * (no generation in this house). Sensor view: with a bound meter reading ({@code p_meas_kw}),
 * {@code residual_kw = measured − computed}. Tariff: {@code price_eur_kwh} if bound, else {@code priceEurKwh}.
 * <p>
 * Efficiency views in the state: {@code peak_kw} (highest step mean), base load as the mean power in the night
 * window {@code baseFromH}..{@code baseToH} ({@code base_kwh / base_h}).
 */
public final class ElectricityMeter extends Atom {

    private final double priceEurKwh;
    private final double[] baseWindow;
    private final LocalClock clock;

    private double importKwh;
    private double costEur;
    private double peakKw;
    private double baseKwh;
    private double baseHours;
    private double residualKwh;

    public ElectricityMeter(String id, Params params) {
        super(id, params);
        priceEurKwh = params.d("priceEurKwh", 0.36);
        baseWindow = new double[]{params.d("baseFromH", 2), params.d("baseToH", 5)};
        clock = new LocalClock(params.s("zone", "Europe/Berlin"));
    }

    @Override
    public String type() {
        return "meter";
    }

    @Override
    public Phase phase() {
        return Phase.BALANCE;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.flow("p_appl_kw", "kW"),
                PortSpec.flow("p_heating_kw", "kW"),
                PortSpec.flow("p_meas_kw", "kW"),
                PortSpec.signal("price_eur_kwh", "EUR/kWh"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_import_kw", "kW"), PortSpec.flow("residual_kw", "kW"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("import_kwh", importKwh);
        s.put("cost_eur", costEur);
        s.put("peak_kw", peakKw);
        s.put("base_kwh", baseKwh);
        s.put("base_h", baseHours);
        s.put("residual_kwh", residualKwh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        importKwh = s.get("import_kwh");
        costEur = s.get("cost_eur");
        peakKw = s.get("peak_kw");
        baseKwh = s.get("base_kwh");
        baseHours = s.get("base_h");
        residualKwh = s.get("residual_kwh");
    }

    @Override
    public void step(StepContext ctx) {
        double p = Math.max(0, ctx.inOr("p_appl_kw", 0)) + Math.max(0, ctx.has("p_heating_kw")
                ? ctx.in("p_heating_kw").getAsDouble() : 0);
        double price = ctx.has("price_eur_kwh") ? ctx.in("price_eur_kwh").getAsDouble() : priceEurKwh;
        double dt = ctx.dtHours();
        importKwh += p * dt;
        costEur += p * dt * price;
        peakKw = Math.max(peakKw, p);
        if (LocalClock.in(baseWindow, clock.hour(ctx.tMicros()))) {
            baseKwh += p * dt;
            baseHours += dt;
        }
        ctx.out("p_import_kw", p);
        OptionalDouble meas = ctx.in("p_meas_kw");
        if (meas.isPresent()) {
            residualKwh += (meas.getAsDouble() - p) * dt;
            ctx.out("residual_kw", meas.getAsDouble() - p);
        }
    }
}
