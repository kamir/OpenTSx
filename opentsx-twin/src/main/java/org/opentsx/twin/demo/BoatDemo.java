package org.opentsx.twin.demo;

import org.opentsx.model.Micros;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.boat.BoatClock;
import org.opentsx.twin.boat.BoatData;
import org.opentsx.twin.boat.BoatMode;
import org.opentsx.twin.boat.Calibration;
import org.opentsx.twin.boat.SimulatedLogs;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scenario "Boot": an Etap 22 as an island energy system (power station, solar, shore power; laptops, phone,
 * Starlink, Raspberry Pi, VHF). Prints
 * <ol>
 *   <li>the shadow run of a synthetic two-week summer trip (logbook bound, everything else modelled), per day;</li>
 *   <li>what-if alternatives against that reference, as KPI deltas (alternative minus reference, modelled).</li>
 * </ol>
 * With {@code --data file.csv} the twin runs on real operating data instead (see {@link BoatData}), with
 * {@code --calibrate} it first fits solar derating and usage scales to the measurements ({@link #calibrate}).
 * {@code --simulated} does both on the bundled SIMULATED logs ({@link SimulatedLogs}) and compares with the hidden
 * true parameters.
 * <p>
 * For the Studio and other front ends the scenario is exposed as data: {@link #plan()}, {@link #observed()},
 * {@link #reference()}, {@link #alternatives()}, {@link #KEY_SERIES}, {@link #KEY_KPIS} and {@link #kpis(RunResult)}.
 * <p>
 * Usage: {@code bin/opentsx boat [--plan file.yaml] [--data file.csv [--zone Europe/Berlin] [--calibrate] | --simulated]}
 */
public final class BoatDemo {

    /** Saturday 2026-07-04, 00:00 CEST. */
    public static final Instant START = Instant.parse("2026-07-03T22:00:00Z");
    public static final long T0 = Micros.of(START);
    public static final double UTC_OFFSET_H = 2;
    /** Holiday trip: H harbour, S sail to a harbour, A sail to an anchorage, X at anchor, U nobody on board. */
    public static final String TRIP = "HAXSHSAXXSHSAS";
    /** The same two weeks as weekend use: sail out Saturday, back Sunday, boat unattended during the week. */
    public static final String WEEKENDS = "ASUUUUUASUUUUU";

    /** Output series worth showing first, {@code atom.port}. */
    public static final List<String> KEY_SERIES = List.of("station.soc", "station.p_unmet_w", "solar.p_avail_w",
            "station.solar_used_w", "station.shore_used_w", "station.p_load_w", "station.autonomy_h",
            "starlink.p_w", "boat.mode");
    /** Run KPIs worth showing first, keys of {@link #kpis(RunResult)}. */
    public static final List<String> KEY_KPIS = List.of("unmet_wh", "unmet_h", "soc_min_pct", "solar_yield_wh",
            "solar_used_wh", "shore_wh", "solar_share_pct", "curtailed_wh", "cycles", "autonomy_h_mean",
            "load_wh_per_day", "device_unmet_use_wh");

    /** A named what-if: interventions, policy overrides and the complete input binding of the run. */
    public record Alternative(String name, String label, List<Intervention> interventions,
                              Map<String, String> policies, SeriesInputs inputs) {
    }

    private BoatDemo() {
    }

    public static TwinPlan plan() {
        return TwinPlan.resource("twin/boot-etap22.yaml");
    }

    /** Observed history of the synthetic trip: only the logbook (mode per step, REPORTED). */
    public static SeriesInputs observed() {
        return logbook(plan(), TRIP);
    }

    /** The reference run has no interventions: shore power whenever in harbour, all devices as planned. */
    public static List<Intervention> reference() {
        return List.of();
    }

    /** The what-if alternatives of the synthetic trip. */
    public static List<Alternative> alternatives() {
        TwinPlan p = plan();
        return alternatives(p, observed(), logbook(p, WEEKENDS));
    }

    /**
     * What-if alternatives for any boat plan and input binding; interventions take effect at the plan start.
     * {@code weekendInputs} may be null (no usage-pattern alternative, e.g. on real data).
     */
    public static List<Alternative> alternatives(TwinPlan plan, SeriesInputs inputs, SeriesInputs weekendInputs) {
        Instant t = Micros.toInstant(plan.startMicros());
        List<Alternative> out = new ArrayList<>();
        out.add(alt("solar300", "+100 Wp solar (300 Wp)", inputs, set(t, "solar", "wp", 300)));
        out.add(alt("starlink3h", "Starlink only 3 h per day", inputs, set(t, "starlink", "max_on_h", 3)));
        out.add(alt("station2kwh", "power station 2 kWh", inputs, set(t, "station", "capacity_wh", 2048)));
        out.add(alt("shore3d", "shore power only every 3rd day", inputs, set(t, "shore", "every_n_days", 3)));
        out.add(alt("laptopsSun", "laptops charge only 10-16 h", inputs,
                set(t, "laptop1", "charge_from_h", 10), set(t, "laptop1", "charge_to_h", 16),
                set(t, "laptop2", "charge_from_h", 10), set(t, "laptop2", "charge_to_h", 16)));
        out.add(alt("starlinkAc", "Starlink via inverter (230 V)", inputs, set(t, "starlink", "path_eff", 0.8)));
        out.add(alt("starlinkStd", "Starlink Standard instead of Mini", inputs, set(t, "starlink", "power_factor", 2.0)));
        if (weekendInputs != null) {
            out.add(new Alternative("weekends", "weekend use instead of the holiday trip", List.of(), Map.of(),
                    weekendInputs));
        }
        return List.copyOf(out);
    }

    /** The shadow run of the synthetic trip. */
    public static RunResult shadow() {
        return new Twin(plan()).shadow(observed(), reference());
    }

    /** Runs an alternative as a what-if on {@code plan}, with {@code base} interventions before its own. */
    public static RunResult run(TwinPlan plan, Alternative a, List<Intervention> base) {
        List<Intervention> log = new ArrayList<>(base);
        log.addAll(a.interventions());
        return new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.WHAT_IF, a.inputs(), log, null, 0,
                a.policies(), null));
    }

    /** Logbook series {@code boat.mode_obs} for an itinerary over the plan horizon. */
    public static SeriesInputs logbook(TwinPlan plan, String itinerary) {
        double[] mode = new double[plan.steps()];
        long dayZero = LocalDate.of(2026, 7, 4).toEpochDay();
        for (int i = 0; i < mode.length; i++) {
            long t = plan.startMicros() + i * plan.baseStepMicros();
            mode[i] = BoatMode.modeAt(itinerary, BoatClock.dayIndex(t, UTC_OFFSET_H, dayZero),
                    BoatClock.localHour(t, UTC_OFFSET_H), 9, 17);
        }
        return new SeriesInputs().bind("boat.mode_obs", plan.startMicros(), plan.baseStepMicros(), mode,
                Origin.REPORTED);
    }

    /** Scenario KPIs of a run, derived from the atom states and series (energies in Wh on the DC side). */
    public static Map<String, Double> kpis(RunResult r) {
        Map<String, Double> k = r.kpis();
        double days = r.size() * (r.stepMicros() / 3.6e9) / 24.0;
        Map<String, Double> out = new LinkedHashMap<>();
        double solarUsed = k.get("station.solar_wh");
        double shore = k.get("station.shore_wh");
        out.put("unmet_wh", k.get("station.unmet_wh"));
        out.put("unmet_h", k.get("station.unmet_h"));
        out.put("soc_min_pct", 100 * k.get("station.soc_min"));
        out.put("solar_yield_wh", k.get("solar.yield_wh"));
        out.put("solar_used_wh", solarUsed);
        out.put("shore_wh", shore);
        out.put("solar_share_pct", solarUsed + shore > 0 ? 100 * solarUsed / (solarUsed + shore) : 0);
        out.put("curtailed_wh", k.get("station.curtailed_wh"));
        out.put("cycles", k.get("station.cycles"));
        double[] aut = r.series("station.autonomy_h");
        double sum = 0;
        for (double a : aut) {
            sum += a;
        }
        out.put("autonomy_h_mean", aut.length == 0 ? 0 : sum / aut.length);
        out.put("load_wh_per_day", k.get("station.demand_wh") / days);
        double deviceUnmet = 0;
        for (String key : k.keySet()) {
            if (key.endsWith(".unmet_use_wh")) {
                String id = key.substring(0, key.indexOf('.'));
                out.put(id + "_wh_per_day", k.get(id + ".energy_wh") / days);
                deviceUnmet += k.get(key);
            }
        }
        out.put("device_unmet_use_wh", deviceUnmet);
        return out;
    }

    public static void main(String[] args) {
        TwinPlan plan = plan();
        Path data = null;
        boolean calibrate = false;
        boolean simulated = false;
        ZoneId zone = ZoneId.of("Europe/Berlin");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--plan" -> plan = TwinPlan.load(Path.of(arg(args, ++i)));
                case "--data" -> data = Path.of(arg(args, ++i));
                case "--zone" -> zone = ZoneId.of(arg(args, ++i));
                case "--calibrate" -> calibrate = true;
                case "--simulated" -> simulated = true;
                default -> usage();
            }
        }
        if (calibrate && data == null || simulated && data != null) {
            usage();
        }
        PrintStream out = System.out;
        if (simulated) {
            out.println("SIMULATED operating data (" + SimulatedLogs.RESOURCE + "), not measurements: import + calibration demo.");
            BoatData d = BoatData.parse(simulatedLogs(), plan, SimulatedLogs.ZONE);
            Calibration.Fit fit = real(out, plan, d, true);
            out.println();
            out.println("3. Hidden true parameters of the simulation vs. calibrated");
            Map<String, Double> truth = SimulatedLogs.truth();
            fit.values().forEach((k, v) -> out.printf(Locale.ROOT, "     %-16s true %.3f  calibrated %.3f  (%+.1f %%)%n",
                    k, truth.get(k), v, 100 * (v / truth.get(k) - 1)));
            Map<String, Double> t = kpis(SimulatedLogs.truthRun(plan));
            Map<String, Double> c = kpis(new Twin(d.plan()).shadow(d.inputs(), fit.interventions()));
            out.println("   KPIs of the hidden true run (5 min) vs. the calibrated twin on the logs (15 min):");
            for (String k : List.of("unmet_wh", "unmet_h", "soc_min_pct", "solar_used_wh", "shore_wh", "load_wh_per_day",
                    "starlink_wh_per_day")) {
                out.printf(Locale.ROOT, "     %-22s true %9.1f  twin %9.1f%n", k, t.get(k), c.get(k));
            }
        } else if (data == null) {
            synthetic(out, plan);
        } else {
            real(out, plan, BoatData.load(data, plan, zone), calibrate);
        }
    }

    private static void synthetic(PrintStream out, TwinPlan plan) {
        SeriesInputs observed = logbook(plan, TRIP);
        RunResult ref = new Twin(plan).shadow(observed, reference());
        out.println("Boat twin " + plan.twin() + ": synthetic two-week trip " + TRIP + " from 2026-07-04 (CEST), step "
                + plan.baseStep().toMinutes() + " min.");
        out.println("All values are MODELLED from assumptions (origin SYNTHETIC); only the logbook is REPORTED.");
        out.println();
        out.println("1. Shadow (reference): run " + ref.runId() + "  hash " + ref.hash());
        days(out, ref);
        printKpis(out, kpis(ref));
        out.println();
        out.println("2. What-if (modelled; delta = alternative minus reference, same inputs and horizon)");
        whatIfs(out, plan, ref, alternatives(plan, observed, logbook(plan, WEEKENDS)), List.of());
    }

    /** The bundled simulated logs ({@link SimulatedLogs#RESOURCE}) as lines. */
    public static List<String> simulatedLogs() {
        try (java.io.InputStream in = BoatDemo.class.getClassLoader().getResourceAsStream(SimulatedLogs.RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + SimulatedLogs.RESOURCE);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Calibrates {@link #defaultKnobs()} on operating data against every measurement that can see them:
     * <ul>
     *   <li>the measured SoC ({@code station.soc}, sigma 1 %-point);</li>
     *   <li>the measured output power, via {@code station.load_residual_w} (sigma 5 W);</li>
     *   <li>if irradiance is bound: the measured solar power where the battery is below 95 % (not curtailed), with
     *       the solar measurement unbound so that the solar model runs on the irradiance (sigma 5 W).</li>
     * </ul>
     * Knobs of parts whose power is measured in more than half of the steps are left out (they would only absorb
     * errors elsewhere); so is the solar derating if solar power is measured but irradiance is not.
     */
    public static Calibration.Fit calibrate(BoatData d) {
        TwinPlan plan = d.plan();
        int half = plan.steps() / 2;
        boolean solarMeasured = d.coverage().getOrDefault("solar.p_meas_w", 0) > 0;
        boolean ghi = d.coverage().getOrDefault("solar.ghi_wm2", 0) > half;
        List<Calibration.Knob> knobs = defaultKnobs().stream().filter(k -> k.controls().stream().noneMatch(c -> {
            String atom = c.substring(0, c.indexOf('.'));
            if (atom.equals("solar")) {
                return solarMeasured && !ghi;
            }
            return d.coverage().getOrDefault(atom + ".p_meas_w", 0) > half;
        })).toList();
        List<Calibration.Target> targets = new ArrayList<>();
        targets.add(new Calibration.Target("station.soc", d.socMeas(), 0.01));
        double[] load = d.measured("station.p_load_meas_w");
        double[] zero = new double[load.length];
        for (int i = 0; i < load.length; i++) {
            zero[i] = Double.isNaN(load[i]) ? Double.NaN : 0;
        }
        targets.add(new Calibration.Target("station.load_residual_w", zero, 5));
        SeriesInputs inputs = d.inputs();
        if (solarMeasured && ghi) {
            double[] pv = d.measured("solar.p_meas_w");
            double[] soc = d.socMeas();
            for (int i = 0; i < pv.length; i++) {
                if (Double.isNaN(soc[i]) || soc[i] >= 0.95) {
                    pv[i] = Double.NaN;
                }
            }
            targets.add(new Calibration.Target("solar.p_avail_w", pv, 5));
            inputs = d.inputs(java.util.Set.of("solar.p_meas_w"));
        }
        return Calibration.fit(plan, inputs, List.of(), targets, knobs);
    }

    private static Calibration.Fit real(PrintStream out, TwinPlan template, BoatData d, boolean calibrate) {
        Calibration.Fit fit = null;
        TwinPlan plan = d.plan();
        out.println("Boat twin " + plan.twin() + " on operating data: " + d.rows() + " rows, " + plan.steps()
                + " steps from " + plan.start());
        d.coverage().forEach((port, n) -> out.printf(Locale.ROOT, "   bound %-24s %5d of %d steps%n", port, n,
                plan.steps()));
        if (!d.ignored().isEmpty()) {
            out.println("   ignored columns: " + d.ignored());
        }
        List<Intervention> base = List.of();
        RunResult shadow = new Twin(plan).shadow(d.inputs(), base);
        double rmse = Calibration.rmse(shadow.series("station.soc"), d.socMeas());
        out.printf(Locale.ROOT, "%n1. Shadow on the data: run %s, SoC RMSE model vs. measured %s%n", shadow.runId(),
                Double.isNaN(rmse) ? "n/a (no soc_prozent)" : String.format(Locale.ROOT, "%.1f %%-points", 100 * rmse));
        if (calibrate) {
            if (Double.isNaN(rmse)) {
                out.println("   --calibrate needs the column soc_prozent");
            } else {
                fit = calibrate(d);
                out.println("   calibrated " + fit.values().keySet() + " in " + fit.evaluations()
                        + " runs (parts measured in more than half of the steps are left out)");
                Calibration.Fit f = fit;
                f.rmseByTarget().forEach((k, v) -> out.printf(Locale.ROOT, "     RMSE %-24s %8.3f -> %8.3f%n", k,
                        f.rmseStartByTarget().get(k), v));
                fit.values().forEach((k, v) -> out.printf(Locale.ROOT, "     %-16s %.3f%n", k, v));
                base = fit.interventions();
                shadow = new Twin(plan).shadow(d.inputs(), base);
                out.printf(Locale.ROOT, "   shadow with calibrated values: SoC RMSE %.1f %%-points%n",
                        100 * Calibration.rmse(shadow.series("station.soc"), d.socMeas()));
            }
        }
        days(out, shadow);
        printKpis(out, kpis(shadow));
        out.println();
        out.println("2. What-if on the data (modelled; delta = alternative minus shadow, same inputs)");
        whatIfs(out, plan, shadow, alternatives(plan, d.inputs(), null), base);
        return fit;
    }

    /** Knobs fitted by {@code --calibrate}: solar derating and usage scales (start = plan defaults). */
    public static List<Calibration.Knob> defaultKnobs() {
        return List.of(
                new Calibration.Knob("solar.derate", List.of("solar.derate"), 0.2, 1.1, 0.75),
                new Calibration.Knob("laptops.scale", List.of("laptop1.scale", "laptop2.scale"), 0.2, 3.0, 1.0),
                new Calibration.Knob("starlink.scale", List.of("starlink.scale"), 0.2, 3.0, 1.0));
    }

    private static void whatIfs(PrintStream out, TwinPlan plan, RunResult ref, List<Alternative> alts,
                                List<Intervention> base) {
        Map<String, Double> r = kpis(ref);
        out.printf(Locale.ROOT, "   %-40s %9s %9s %9s %9s %9s %9s %9s%n", "alternative", "unmet Wh", "SoC min",
                "solar Wh", "shore Wh", "autonomy", "load Wh/d", "dev.empty");
        out.printf(Locale.ROOT, "   %-40s %9.0f %8.1f%% %9.0f %9.0f %8.1fh %9.0f %9.0f%n", "reference (absolute)",
                r.get("unmet_wh"), r.get("soc_min_pct"), r.get("solar_used_wh"), r.get("shore_wh"),
                r.get("autonomy_h_mean"), r.get("load_wh_per_day"), r.get("device_unmet_use_wh"));
        for (Alternative a : alts) {
            Map<String, Double> k = kpis(run(plan, a, base));
            out.printf(Locale.ROOT, "   %-40s %+9.0f %+7.1fpp %+9.0f %+9.0f %+8.1fh %+9.0f %+9.0f%n", a.label(),
                    k.get("unmet_wh") - r.get("unmet_wh"), k.get("soc_min_pct") - r.get("soc_min_pct"),
                    k.get("solar_used_wh") - r.get("solar_used_wh"), k.get("shore_wh") - r.get("shore_wh"),
                    k.get("autonomy_h_mean") - r.get("autonomy_h_mean"),
                    k.get("load_wh_per_day") - r.get("load_wh_per_day"),
                    k.get("device_unmet_use_wh") - r.get("device_unmet_use_wh"));
        }
    }

    private static void days(PrintStream out, RunResult r) {
        double h = r.stepMicros() / 3.6e9;
        double[] mode = r.series("boat.mode");
        double[] pv = r.series("solar.p_avail_w");
        double[] pvUsed = r.series("station.solar_used_w");
        double[] shore = r.series("station.shore_used_w");
        double[] load = r.series("station.p_load_w");
        double[] unmet = r.series("station.p_unmet_w");
        double[] soc = r.series("station.soc");
        out.printf(Locale.ROOT, "   %-10s %-8s %8s %8s %8s %8s %8s %8s%n", "day", "modes", "PV Wh", "PV used",
                "shore", "load Wh", "SoC min", "unmet");
        int i = 0;
        while (i < r.size()) {
            long day = BoatClock.localEpochDay(r.timeMicros(i), UTC_OFFSET_H);
            StringBuilder modes = new StringBuilder();
            double a = 0;
            double b = 0;
            double c = 0;
            double l = 0;
            double u = 0;
            double sMin = 1;
            for (; i < r.size() && BoatClock.localEpochDay(r.timeMicros(i), UTC_OFFSET_H) == day; i++) {
                char m = "HSAU".charAt((int) mode[i]);
                if (modes.indexOf(String.valueOf(m)) < 0) {
                    modes.append(m);
                }
                a += pv[i] * h;
                b += pvUsed[i] * h;
                c += shore[i] * h;
                l += load[i] * h;
                u += unmet[i] * h;
                sMin = Math.min(sMin, soc[i]);
            }
            out.printf(Locale.ROOT, "   %-10s %-8s %8.0f %8.0f %8.0f %8.0f %7.0f%% %8.0f%n", LocalDate.ofEpochDay(day),
                    modes, a, b, c, l, 100 * sMin, u);
        }
        out.println("   modes: H harbour, S sailing, A anchor, U away");
    }

    private static void printKpis(PrintStream out, Map<String, Double> k) {
        out.println("   KPIs:");
        k.forEach((key, v) -> out.printf(Locale.ROOT, "     %-22s %10.1f%n", key, v));
    }

    private static Alternative alt(String name, String label, SeriesInputs inputs, Intervention... ivs) {
        return new Alternative(name, label, List.of(ivs), Map.of(), inputs);
    }

    private static Intervention set(Instant t, String atom, String control, double v) {
        return Intervention.set(t, atom, control, v, "what-if", control + "=" + v);
    }

    private static String arg(String[] args, int i) {
        if (i >= args.length) {
            usage();
        }
        return args[i];
    }

    private static void usage() {
        System.err.println("usage: bin/opentsx boat [--plan file.yaml] [--data file.csv [--zone Europe/Berlin] [--calibrate] | --simulated]");
        System.exit(1);
    }
}
