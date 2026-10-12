package org.opentsx.market.twin;

import org.opentsx.market.MarketCatalog;
import org.opentsx.market.Micro;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.house.HouseKpis;
import org.opentsx.twin.house.HouseScenario;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The house twin ({@code twin/haus-erzgebirge.yaml}) on a real weather year: hourly air temperature (and, if
 * archived, global radiation) of the DWD stations of the site bound to {@code weather.t_out_meas_c} and
 * {@code weather.ghi_meas_w_m2} (MEASURED). The year runs from local midnight on 1 January; the reference
 * operation (oil delivery in September, wood in June) is moved into that year. Hours without data fall back to the
 * synthetic climate and count as SYNTHETIC.
 */
public final class HouseHistory {

    /** One weather year: plan, inputs, data coverage. */
    public record WeatherYear(int year, TwinPlan plan, SeriesInputs inputs, double temperatureCoverage,
                        double radiationCoverage) {
    }

    private final HistoryInputs history;
    private final HistorySites.Site site;

    public HouseHistory(HistoryInputs history, HistorySites.Site site) {
        this.history = history;
        this.site = site;
    }

    public WeatherYear year(int year) {
        return window(year, LocalDate.of(year, 1, 1), Year.of(year).length() * 24);
    }

    /** A window of {@code hours} from local midnight of {@code first} (tests use a few days). */
    public WeatherYear window(int year, LocalDate first, int hours) {
        TwinPlan base = HouseScenario.plan();
        TwinPlan plan = HouseScenario.withParams(base, Map.of(), first.atStartOfDay(HouseScenario.ZONE).toInstant(), hours);
        long t0 = plan.startMicros();
        long step = plan.baseStepMicros();
        String weather = HouseScenario.id(plan, "weather");
        double[] t = history.resampled(MarketCatalog.dwd(site.temperature(), "air_temperature"), t0, step, hours);
        SeriesInputs in = new SeriesInputs().bind(weather + ".t_out_meas_c", t0, step, t, Origin.MEASURED);
        double radCov = 0;
        if (site.radiation() != null) {
            double[] g = history.resampled(MarketCatalog.dwd(site.radiation(), "global_radiation"), t0, step, hours);
            radCov = HistoryInputs.coverage(g);
            if (radCov > 0) {
                in = in.with(new SeriesInputs().bind(weather + ".ghi_meas_w_m2", t0, step, g, Origin.MEASURED));
            }
        }
        return new WeatherYear(year, plan, in, HistoryInputs.coverage(t), radCov);
    }

    /** Reference operation of the example house, moved into {@code year}. */
    public static List<Intervention> reference(TwinPlan plan, int year) {
        List<Intervention> log = new ArrayList<>();
        log.addAll(HouseScenario.oilDelivery(HouseScenario.id(plan, "oil-tank"),
                HouseScenario.local(year + "-09-15T08:00"), 2000, plan.baseStep()));
        log.addAll(HouseScenario.woodDelivery(HouseScenario.id(plan, "wood-store"),
                HouseScenario.local(year + "-06-02T10:00"), 4, plan.baseStep()));
        return log;
    }

    public static RunResult shadow(WeatherYear y) {
        return new TwinRunner(y.plan()).run(new TwinRunner.RunSpec(Mode.SHADOW, y.inputs(), reference(y.plan(), y.year()),
                null, 0, null, null));
    }

    /** Every lever of the house as what-if on the same weather year: KPI deltas (alternative minus reference). */
    public static Map<String, Map<String, Double>> levers(WeatherYear y, HouseKpis ref, double hddRef) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        TwinRunner runner = new TwinRunner(y.plan());
        for (HouseScenario.Lever l : HouseScenario.levers(y.plan())) {
            List<Intervention> log = new ArrayList<>(reference(y.plan(), y.year()));
            log.addAll(l.interventions());
            RunResult r = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, y.inputs(), log, null, 0, null, null));
            out.put(l.name() + " | " + l.description(), HouseKpis.of(y.plan(), r, hddRef).minus(ref));
        }
        return out;
    }

    /** Mean of the bound temperature over the run (NaN steps skipped), for the report. */
    public static double meanTemperature(WeatherYear y) {
        long t0 = y.plan().startMicros();
        double s = 0;
        int n = 0;
        String key = HouseScenario.id(y.plan(), "weather") + ".t_out_meas_c";
        for (int i = 0; i < y.plan().steps(); i++) {
            var v = y.inputs().value(key, t0 + i * Micro.HOUR);
            if (v.isPresent()) {
                s += v.get().v();
                n++;
            }
        }
        return n == 0 ? Double.NaN : s / n;
    }
}
