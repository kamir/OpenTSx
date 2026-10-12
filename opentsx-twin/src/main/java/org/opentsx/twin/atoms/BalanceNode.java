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
 * A6: power balance at the grid connection point, solved within the superstep:
 * {@code p_grid = p_gen - (p_self + p_ely + p_bat)} (positive = feed-in). Local generation is attributed to the
 * consumers in priority order ({@code priority}, default self, ely, bat) to report direct supply.
 * <p>
 * Sensor view: if the grid meter is bound to {@code p_grid_meas_kw}, {@code residual_kw = measured - computed}.
 */
public final class BalanceNode extends Atom {

    private static final List<String> LOADS = List.of("self", "ely", "bat");

    private final List<String> priority;
    private final Map<String, Double> directKwh = new LinkedHashMap<>();
    private double exportKwh;
    private double importKwh;

    public BalanceNode(String id, Params params) {
        super(id, params);
        priority = params.list("priority", LOADS);
        if (!LOADS.containsAll(priority) || priority.size() != LOADS.size()) {
            throw new IllegalArgumentException("priority must order " + LOADS);
        }
        LOADS.forEach(l -> directKwh.put(l, 0.0));
    }

    @Override
    public String type() {
        return "balance";
    }

    @Override
    public Phase phase() {
        return Phase.BALANCE;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.flow("p_gen_kw", "kW"),
                PortSpec.flow("p_self_kw", "kW"),
                PortSpec.flow("p_ely_kw", "kW"),
                PortSpec.flow("p_bat_kw", "kW"),
                PortSpec.flow("p_grid_meas_kw", "kW"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.flow("p_grid_kw", "kW"),
                PortSpec.flow("p_direct_kw", "kW"),
                PortSpec.flow("residual_kw", "kW"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        directKwh.forEach((k, v) -> s.put("direct_" + k + "_kwh", v));
        s.put("export_kwh", exportKwh);
        s.put("import_kwh", importKwh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        LOADS.forEach(l -> directKwh.put(l, s.get("direct_" + l + "_kwh")));
        exportKwh = s.get("export_kwh");
        importKwh = s.get("import_kwh");
    }

    @Override
    public void step(StepContext ctx) {
        double gen = ctx.inOr("p_gen_kw", 0);
        Map<String, Double> load = new LinkedHashMap<>();
        load.put("self", ctx.has("p_self_kw") ? ctx.in("p_self_kw").getAsDouble() : 0);
        load.put("ely", ctx.has("p_ely_kw") ? ctx.in("p_ely_kw").getAsDouble() : 0);
        load.put("bat", ctx.has("p_bat_kw") ? ctx.in("p_bat_kw").getAsDouble() : 0);
        double total = load.values().stream().mapToDouble(Double::doubleValue).sum();
        double grid = gen - total;
        double left = Math.max(0, gen);
        double direct = 0;
        for (String l : priority) {
            double take = Math.min(left, Math.max(0, load.get(l)));
            left -= take;
            direct += take;
            directKwh.merge(l, take * ctx.dtHours(), Double::sum);
        }
        if (grid >= 0) {
            exportKwh += grid * ctx.dtHours();
        } else {
            importKwh += -grid * ctx.dtHours();
        }
        ctx.out("p_grid_kw", grid);
        ctx.out("p_direct_kw", direct);
        OptionalDouble meas = ctx.in("p_grid_meas_kw");
        if (meas.isPresent()) {
            ctx.out("residual_kw", meas.getAsDouble() - grid);
        }
    }
}
