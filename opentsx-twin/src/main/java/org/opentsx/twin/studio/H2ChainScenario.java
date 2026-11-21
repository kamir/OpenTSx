package org.opentsx.twin.studio;

import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.demo.H2ChainDemo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Scenario S1, the H2 chain, for the Studio. */
public final class H2ChainScenario implements StudioScenario {

    @Override
    public String id() {
        return "h2-chain";
    }

    @Override
    public String title() {
        return "Hydrogen chain (wind farm, electrolyzer, compressor, buffer, H₂ pipeline)";
    }

    @Override
    public String description() {
        return "The handover station closes its valve at 01:00. The buffer fills up, the electrolyzer shuts down, "
                + "and the direct marketer has to sell more electricity. Later in the night a restriction limits the "
                + "farm for one hour, and the operations management curtails it for half an hour. Six hours in "
                + "one-minute steps, synthetic data.";
    }

    @Override
    public TwinPlan plan() {
        return H2ChainDemo.plan();
    }

    @Override
    public Inputs observed() {
        return H2ChainDemo.observed();
    }

    @Override
    public List<Intervention> reference() {
        return H2ChainDemo.reference();
    }

    @Override
    public List<Alternative> alternatives() {
        List<Alternative> out = new ArrayList<>();
        for (String policy : new String[]{"follow", "pressure-aware"}) {
            String pName = policy.equals("follow") ? "operating policy unchanged"
                    : "electrolyzer throttles as the pressure rises";
            Map<String, String> pol = policy.equals("follow") ? Map.of() : Map.of("ely", policy);
            if (!policy.equals("follow")) {
                out.add(new Alternative("closed-" + policy, "Valve stays closed · " + pName, reference(), pol, null));
            }
            for (int minute : new int[]{80, 100, 120, 140, 160, 180, 200, 240, 300}) {
                List<Intervention> log = new ArrayList<>(reference());
                log.add(Intervention.set(H2ChainDemo.START.plusSeconds(minute * 60L), "handover", "valve", 1,
                        "h2-handover", "valve reopened"));
                out.add(new Alternative("reopen-" + minute + "-" + policy, String.format(Locale.ROOT,
                        "Valve open again at %02d:%02d · %s", minute / 60, minute % 60, pName), log, pol, null));
            }
        }
        return out;
    }

    @Override
    public List<Forecast> forecasts() {
        List<Forecast> out = new ArrayList<>();
        for (int now : new int[]{70, 90, 110, 130}) {
            String t = String.format(Locale.ROOT, "%02d:%02d", now / 60, now % 60);
            out.add(new Forecast("now-" + now + "-follow", "from " + t + " · operating policy unchanged", now,
                    Map.of()));
            out.add(new Forecast("now-" + now + "-aware", "from " + t + " · electrolyzer throttles as the pressure rises",
                    now, Map.of("ely", "pressure-aware")));
        }
        return out;
    }

    @Override
    public List<String> smartMetrics() {
        return List.of("buffer.ttl_min", "buffer.headroom_kg", "marketer.deviation_kw", "grid.p_direct_kw");
    }

    @Override
    public List<String> compareKeys() {
        return List.of("buffer.p_bar", "ely.p_el_kw", "marketer.deviation_kw");
    }

    @Override
    public List<Kpi> kpis() {
        return List.of(
                new Kpi("ely.trips", "Protective shutdowns", "", 0, -1),
                new Kpi("buffer.p_max_bar", "Highest pressure in the buffer", "bar", 2, -1),
                new Kpi("ely.h2_kg", "Hydrogen produced", "kg", 1, 1),
                new Kpi("handover.delivered_kg", "Delivered to the customer", "kg", 1, 1),
                new Kpi("handover.shortfall_kg", "Not delivered", "kg", 1, -1),
                new Kpi("compressor.energy_kwh", "Electricity for compression", "kWh", 0, 0),
                new Kpi("park.curtailed_rules_kwh", "Not generated because of restrictions", "kWh", 0, -1),
                new Kpi("park.curtailed_dispatch_kwh", "Not generated because of the dispatch command", "kWh", 0, -1),
                new Kpi("marketer.extra_kwh", "Unplanned extra feed-in", "kWh", 0, -1),
                new Kpi("marketer.revenue_eur", "Feed-in revenue (without balancing energy and H₂ revenue)", "€",
                        0, 0));
    }

    @Override
    public Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("buffer.ttl_min", "Time to pressure limit");
        m.put("buffer.headroom_kg", "Buffer headroom");
        m.put("marketer.deviation_kw", "Schedule deviation");
        m.put("grid.p_direct_kw", "Generation used directly");
        m.put("buffer.p_bar", "Pressure in the buffer");
        m.put("buffer.m_out_kgph", "Buffer output to the pipeline");
        m.put("buffer.m_net_kgph", "Net inflow to the buffer");
        m.put("line.p_in_bar", "Pipeline pressure at the start");
        m.put("line.p_out_bar", "Pipeline pressure at the end");
        m.put("line.linepack_kg", "Hydrogen in the pipeline");
        m.put("compressor.p_el_kw", "Compressor power");
        m.put("compressor.m_out_kgph", "Compressed hydrogen");
        m.put("handover.m_out_kgph", "H₂ delivery");
        m.put("handover.shortfall_kgph", "Shortfall");
        m.put("handover.p_delivery_bar", "Delivery pressure");
        m.put("offtake.request_kgph", "H₂ request");
        m.put("park.p_avail_kw", "Available wind farm power");
        m.put("ely.p_el_kw", "Electrolyzer power");
        m.put("ely.m_h2_kgph", "H₂ production");
        m.put("park.p_gen_kw", "Wind farm generation");
        m.put("grid.p_grid_kw", "Feed-in to the grid");
        m.put("grid.residual_kw", "Residual to the meter reading");
        m.put("market.price_eur_mwh", "Electricity price");
        m.put("battery.p_bat_kw", "Battery power");
        m.put("battery.soc", "Battery state of charge");
        m.put("line.m_net_kgph", "Net inflow to the pipeline");
        m.put("rules.limit_kw", "Power limit (operating rules)");
        return m;
    }

    @Override
    public List<String> headline() {
        return List.of("ely.trips", "marketer.extra_kwh", "handover.delivered_kg", "buffer.p_max_bar");
    }

    @Override
    public String decision() {
        return "By when must the valve of the handover station be open again, and when is the direct marketer told?";
    }

    @Override
    public Map<String, List<Limit>> limits() {
        TwinPlan p = plan();
        return Map.of("buffer.p_bar", List.of(
                new Limit("Maximum pressure", param(p, "buffer", "pMaxBar")),
                new Limit("Restart", param(p, "ely", "pResumeBar"))));
    }

    private static double param(TwinPlan plan, String atom, String name) {
        return plan.atoms().stream().filter(a -> a.id().equals(atom)).findFirst()
                .map(a -> ((Number) a.params().get(name)).doubleValue()).orElseThrow();
    }

    @Override
    public StudioGuide guide() {
        return StudioGuide.resource("twin/h2-chain.guide.yaml");
    }

    @Override
    public List<String> markerEvents() {
        return List.of("intervention", "pressure_limit", "protective_shutdown");
    }
}
