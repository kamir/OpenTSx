package org.opentsx.market.twin;

import org.opentsx.market.MarketCatalog;
import org.opentsx.market.Micro;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.house.HouseScenario;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario S1 (H2 chain) on archived history: real day-ahead prices and real wind of a chosen period drive the
 * wind park, the electrolyzer, the compressor, buffer, pipeline and handover station, and the direct marketer (plan
 * {@code twin-history/h2-chain-history.yaml}, the second form of the plan with a 15-minute step).
 * <p>
 * The electrolyzer follows a <b>price-limit rule</b> (a modelled operator, origin SYNTHETIC): it runs on the
 * forecast wind, up to its rated power, in every step whose day-ahead price is at or below the limit. The marketer's
 * schedule is the forecast feed-in (forecast wind minus the electrolyzer plan and an estimate of the compressor), so
 * its deviation is the real
 * forecast error of that period. What-ifs compare price limits (always, 60/40/20 % cheapest steps).
 */
public final class H2History {

    public static final String PLAN = "twin-history/h2-chain-history.yaml";

    /** Inputs of one run and the price series (for cost KPIs). */
    public record Run(TwinPlan plan, SeriesInputs inputs, double[] price, double[] availKw, double[] forecastKw,
                      double priceLimit, String windSource, Map<String, Double> coverage) {
    }

    private final HistoryInputs history;
    private final HistorySites.Site park;
    private final double meanCapacityFactor;

    public H2History(HistoryInputs history, HistorySites.Site park, double meanCapacityFactor) {
        this.history = history;
        this.park = park;
        this.meanCapacityFactor = meanCapacityFactor;
    }

    public static TwinPlan plan(Instant start, int steps) {
        return HouseScenario.withParams(TwinPlan.resource(PLAN), Map.of(), start, steps);
    }

    /**
     * Inputs for the plan horizon. {@code wind}: {@code smard} (national onshore generation, scaled) or {@code dwd}
     * (park station through a power curve). {@code priceQuantile} in [0, 1] sets the limit as that quantile of the
     * period's prices (1 = always run).
     */
    public Run inputs(TwinPlan plan, String wind, double priceQuantile) {
        long t0 = plan.startMicros();
        long step = plan.baseStepMicros();
        int n = plan.steps();
        double rated = HouseScenario.param(plan, "park", "ratedKw", 12_000);
        double elyKw = HouseScenario.param(plan, "ely", "ratedKw", 5_000);
        double minLoad = HouseScenario.param(plan, "ely", "minLoad", 0.1);
        double kwhPerKg = HouseScenario.param(plan, "ely", "kwhPerKg", 55);
        // what the compressor draws per kg against a buffer of about 120 bar: part of the schedule, an estimate
        double compressorKwhPerKg = 0.757;

        Map<String, Double> cov = new LinkedHashMap<>();
        double[] price = history.resampled("smard/day_ahead_price", t0, step, n);
        cov.put("price", HistoryInputs.coverage(price));
        double[] avail;
        double[] forecast;
        if (wind.equals("dwd")) {
            if (park.wind() == null) {
                throw new IllegalArgumentException("site " + park.id() + " has no wind station");
            }
            double[] v = history.resampled(MarketCatalog.dwd(park.wind(), "wind_speed"), t0, step, n);
            avail = WindPower.fromStation(v, rated, park.anemometerHeightM(), park.hubHeightM(), park.shearExponent());
            forecast = null;   // no forecast archive per station: the schedule is the measured wind (no error)
        } else {
            avail = WindPower.fromNational(history.resampled("smard/wind_onshore", t0, step, n), rated, meanCapacityFactor);
            double[] f = history.resampled("smard/forecast_wind_onshore", t0, step, n);
            double[] g = history.resampled("smard/wind_onshore", t0, step, n);
            // same scaling as the actuals, so forecast and actual are comparable
            double scale = meanOf(avail) / meanOf(g);
            forecast = new double[n];
            for (int i = 0; i < n; i++) {
                forecast[i] = Double.isFinite(f[i]) ? Math.min(rated, Math.max(0, f[i] * scale)) : Double.NaN;
            }
            cov.put("forecast", HistoryInputs.coverage(forecast));
            if (HistoryInputs.coverage(forecast) == 0) {
                forecast = null;   // no forecast archived: the schedule is the actual wind (no forecast error)
            }
        }
        cov.put("wind", HistoryInputs.coverage(avail));
        double limit = quantile(price, priceQuantile);

        double[] setpoint = new double[n];
        double[] schedule = new double[n];
        double[] offtake = new double[n];
        for (int i = 0; i < n; i++) {
            double fw = forecast != null && Double.isFinite(forecast[i]) ? forecast[i] : avail[i];
            double sp = 0;
            if (Double.isFinite(price[i]) && Double.isFinite(fw) && price[i] <= limit) {
                sp = Math.min(elyKw, fw);
                double min = minLoad * elyKw;
                if (sp > 0 && sp < min) {
                    sp = sp < min / 2 ? 0 : min;   // same snapping as the electrolyzer, so offtake matches output
                }
            }
            setpoint[i] = sp;
            offtake[i] = sp / kwhPerKg;
            schedule[i] = Double.isFinite(fw) ? fw - sp - offtake[i] * compressorKwhPerKg : Double.NaN;
        }
        SeriesInputs in = new SeriesInputs()
                .bind("market.price_eur_mwh", t0, step, price, Origin.REPORTED)
                .bind("park.p_potential_kw", t0, step, avail, Origin.ESTIMATED)
                .bind("ely.setpoint_kw", t0, step, setpoint, Origin.SYNTHETIC)
                .bind("offtake.demand_kgph", t0, step, offtake, Origin.SYNTHETIC)
                .bind("marketer.schedule_kw", t0, step, schedule, forecast != null ? Origin.FORECAST : Origin.ESTIMATED)
                .constant("battery.power_kw", 0, Origin.SYNTHETIC);
        return new Run(plan, in, price, avail, forecast, limit, wind, cov);
    }

    public static RunResult run(Run r, Mode mode) {
        return new TwinRunner(r.plan()).run(new TwinRunner.RunSpec(mode, r.inputs(), List.of(), null, 0, null, null));
    }

    /** KPIs of a run: H2, electrolyzer energy and its cost at the day-ahead price, revenue, forecast deviation. */
    public static Map<String, Double> kpis(Run in, RunResult r) {
        Map<String, Double> k = r.kpis();
        double[] ely = r.series("ely.p_el_kw");
        double[] grid = r.series("grid.p_grid_kw");
        double h = r.stepMicros() / (double) Micro.HOUR;
        double cost = 0;
        double negKwh = 0;
        for (int i = 0; i < ely.length; i++) {
            double p = Double.isFinite(in.price()[i]) ? in.price()[i] : 0;
            cost += ely[i] * h / 1000 * p;
            if (p < 0) {
                negKwh += Math.max(0, grid[i]) * h;
            }
        }
        double h2 = k.get("ely.h2_kg");
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("price_limit_eur_mwh", in.priceLimit());
        out.put("h2_kg", h2);
        out.put("ely_mwh", k.get("ely.energy_kwh") / 1000);
        out.put("ely_full_load_h", k.get("ely.energy_kwh") / HouseScenario.param(in.plan(), "ely", "ratedKw", 5000));
        out.put("ely_cost_eur", cost);
        out.put("power_cost_eur_per_kg", h2 > 0 ? cost / h2 : Double.NaN);
        out.put("park_mwh", k.get("park.energy_kwh") / 1000);
        out.put("feed_in_revenue_eur", k.get("marketer.revenue_eur"));
        out.put("feed_in_at_negative_price_mwh", negKwh / 1000);
        out.put("deviation_extra_mwh", k.get("marketer.extra_kwh") / 1000);
        out.put("deviation_short_mwh", k.get("marketer.short_kwh") / 1000);
        out.put("trips", k.get("ely.trips"));
        out.put("p_max_bar", r.max("buffer.p_bar"));
        out.put("buffer_min_bar", r.min("buffer.p_bar"));
        out.put("line_min_bar", r.min("line.p_out_bar"));
        out.put("line_max_bar", r.max("line.p_in_bar"));
        out.put("compressor_mwh", k.get("compressor.energy_kwh") / 1000);
        out.put("h2_delivered_kg", k.get("handover.delivered_kg"));
        out.put("h2_shortfall_kg", k.get("handover.shortfall_kg"));
        return out;
    }

    static double meanOf(double[] v) {
        return Arrays.stream(v).filter(Double::isFinite).average().orElse(Double.NaN);
    }

    static double quantile(double[] v, double q) {
        if (q >= 1) {
            return Double.POSITIVE_INFINITY;
        }
        double[] s = Arrays.stream(v).filter(Double::isFinite).sorted().toArray();
        if (s.length == 0) {
            return Double.NaN;
        }
        return s[(int) Math.min(s.length - 1, Math.max(0, Math.floor(q * s.length)))];
    }
}
