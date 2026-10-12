package org.opentsx.twin.demo;

import org.opentsx.model.Micros;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Mode;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinEvent;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.house.BillCalibration;
import org.opentsx.twin.house.HouseKpis;
import org.opentsx.twin.house.HouseScenario;
import org.opentsx.twin.house.SimulatedBills;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * House twin (P9.7): a single-family house in the Erzgebirge with oil boiler and wood stove. Runs the reference year
 * in shadow mode on the synthetic climate and every efficiency lever as a what-if, and prints the KPIs and the
 * <b>modelled</b> potential of each lever as alternative minus reference (same weather, same information).
 * <p>
 * With {@code --calibrate bills.csv} it first fits the plan to the bills (see {@link BillCalibration}) and then
 * evaluates the levers on the calibrated house.
 * <p>
 * With {@code --simulated} it calibrates on the bundled simulated bills ({@link SimulatedBills}) instead.
 * <p>
 * Usage: {@code bin/opentsx house [--plan file.yaml] [--calibrate bills.csv [--weather daily-temps.csv]]
 * [--simulated] [--hdd-ref Kd]}
 * <p>
 * The static methods {@link #plan()}, {@link #observed()}, {@link #reference()}, {@link #alternatives()} and
 * {@link #smartMetrics()} expose the scenario to generic views (e.g. the studio export); the full year has 8760
 * hourly steps, {@link #winterWeekPlan()} is a 168-step window for lighter embedding.
 */
public final class HouseDemo {

    /** Long-term reference heating degree days G20/15 of the invented site (assumption, Kd). */
    public static final double HDD_REF = 4300;

    /** A what-if: the full intervention log (reference plus lever), policy overrides, optional input overrides. */
    public record Alternative(String description, List<Intervention> interventions, Map<String, String> policyOverrides,
                              SeriesInputs inputOverrides) {
    }

    private HouseDemo() {
    }

    /** The example plan, one year in hourly steps (8760). */
    public static TwinPlan plan() {
        return HouseScenario.plan();
    }

    /** A winter week (13–19 January 2025, 168 hourly steps) of the same plan, for compact views. */
    public static TwinPlan winterWeekPlan() {
        return HouseScenario.withParams(plan(), Map.of(), HouseScenario.local("2025-01-13T00:00"), 168);
    }

    /**
     * Inputs of the shadow run. Empty: the example runs on the synthetic climate and the plan's schedules, so every
     * number is SYNTHETIC. With real data, bind e.g. {@code weather.t_out_meas_c}, {@code meter.p_meas_kw} or
     * {@code tank.level_meas_l} here.
     */
    public static SeriesInputs observed() {
        return new SeriesInputs();
    }

    /** Intervention log of the reference operation (oil and wood deliveries). */
    public static List<Intervention> reference() {
        return HouseScenario.reference(plan());
    }

    /** The levers as what-ifs, in display order; each log contains the reference plus the lever. */
    public static Map<String, Alternative> alternatives() {
        return alternativesFor(plan());
    }

    /** Output keys worth showing first in a view. */
    public static List<String> smartMetrics() {
        return List.of("building.t_in_c", "weather.t_out_c", "building.q_req_kw", "boiler.q_space_kw",
                "boiler.fuel_lph", "tank.level_l", "stove.q_heat_kw", "woodstore.stock_ster", "meter.p_import_kw");
    }

    public static void main(String[] args) {
        TwinPlan plan = plan();
        Path bills = null;
        Path weatherCsv = null;
        boolean simulated = false;
        double hddRef = HDD_REF;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--simulated")) {
                simulated = true;
                continue;
            }
            if (i + 1 >= args.length) {
                usage();
            }
            switch (a) {
                case "--plan" -> plan = TwinPlan.load(Path.of(args[++i]));
                case "--calibrate" -> bills = Path.of(args[++i]);
                case "--weather" -> weatherCsv = Path.of(args[++i]);
                case "--hdd-ref" -> hddRef = Double.parseDouble(args[++i]);
                default -> usage();
            }
        }
        PrintStream out = System.out;
        if (simulated) {
            out.println("House twin '" + plan.twin() + "' calibrated on SIMULATED bills (" + SimulatedBills.BILLS
                    + ", " + SimulatedBills.TEMPERATURES + ") - no real data");
            SeriesInputs weather = BillCalibration.dailyTemperatures(SimulatedBills.bundledTemperatures(),
                    HouseScenario.id(plan, "weather"), 5.0);
            BillCalibration.Result c = new BillCalibration(plan, weather)
                    .fit(BillCalibration.parse(SimulatedBills.bundledBills()));
            report(out, c);
            out.println("   hidden true parameters of the simulation (unknown to the fit): " + SimulatedBills.truth());
            out.println("   (boiler.eta is fixed at the plan's assumption in the fit, so H and the hot-water scale "
                    + "absorb the eta ratio)");
            evaluate(out, c.calibratedPlan(), hddRef);
            return;
        }
        out.println("House twin '" + plan.twin() + "' - ALL NUMBERS ARE INVENTED EXAMPLE ASSUMPTIONS"
                + (bills == null ? " (not calibrated)" : " (calibrated on " + bills + ")"));
        if (bills != null) {
            SeriesInputs weather = weatherCsv == null ? new SeriesInputs()
                    : BillCalibration.dailyTemperatures(weatherCsv, HouseScenario.id(plan, "weather"), 5.0);
            BillCalibration.Result c = new BillCalibration(plan, weather).fit(BillCalibration.read(bills));
            report(out, c);
            plan = c.calibratedPlan();
        }
        evaluate(out, plan, hddRef);
    }

    private static void usage() {
        System.err.println("usage: bin/opentsx house [--plan file.yaml] [--calibrate bills.csv [--weather temps.csv]] "
                + "[--simulated] [--hdd-ref Kd]");
        System.exit(1);
    }

    /** Shadow run of the reference year plus every lever as alternative minus reference. */
    static void evaluate(PrintStream out, TwinPlan plan, double hddRef) {
        TwinRunner runner = new TwinRunner(plan);
        List<Intervention> ref = HouseScenario.reference(plan);
        RunResult shadow = runner.run(new TwinRunner.RunSpec(Mode.SHADOW, observed(), ref, null, 0, null, null));
        HouseKpis k = HouseKpis.of(plan, shadow, hddRef);

        out.println();
        out.println("1. Shadow: reference year " + plan.start() + " + " + plan.steps() + " x " + plan.baseStep());
        out.println("   run " + shadow.runId() + "  hash " + shadow.hash() + "  origin " + k.origin());
        for (TwinEvent e : shadow.events()) {
            if (!e.type().equals("intervention")) {
                out.printf(Locale.ROOT, "   %s  %-10s %-16s %s%n",
                        Micros.toInstant(e.tMicros()).atZone(HouseScenario.ZONE).toLocalDateTime(), e.atom(),
                        e.type(), e.attrs());
            }
        }
        out.printf(Locale.ROOT, "   electricity      %8.0f kWh  (heating pump/burner %.0f, standby %.0f = %.1f %%, "
                        + "base load %.0f W, peak %.1f kW)%n", k.electricityKwh(), k.heatingElectricityKwh(),
                k.standbyKwh(), 100 * k.standbyKwh() / k.electricityKwh(), k.baseLoadW(), k.peakKw());
        out.printf(Locale.ROOT, "   heating oil      %8.0f l    (%.0f kWh)%n", k.oilLitres(), k.oilKwh());
        out.printf(Locale.ROOT, "   firewood         %8.1f Ster (%.0f kWh)%n", k.woodSter(), k.woodKwh());
        out.printf(Locale.ROOT, "   final heat       %8.0f kWh = %.0f kWh/m2a; G20/15 %.0f Kd, normalized to %.0f Kd: "
                + "%.0f kWh%n", k.finalHeatKwh(), k.finalHeatKwhPerM2(), k.hddKd(), hddRef, k.normalizedFinalHeatKwh());
        out.printf(Locale.ROOT, "   useful heat      %8.0f kWh; boiler losses %.0f kWh (annual efficiency %.2f); "
                + "hot water %.0f kWh%n", k.usefulHeatKwh(), k.boilerLossKwh(), k.boilerEfficiency(), k.hotWaterKwh());
        out.printf(Locale.ROOT, "   comfort          mean indoor %.1f C, %.0f h below set-point%n", k.meanIndoorC(),
                k.underHours());
        out.printf(Locale.ROOT, "   cost %.0f EUR/a, CO2 %.0f kg/a%n", k.costEur(), k.co2Kg());

        out.println();
        out.println("2. What-if: MODELLED potential per lever = alternative - reference (same weather, same plan)");
        out.printf(Locale.ROOT, "   %-18s %9s %9s %9s %10s %8s %8s %8s  %s%n", "lever", "oil l", "wood St",
                "el kWh", "final kWh", "EUR", "CO2 kg", "under h", "change");
        for (Map.Entry<String, Alternative> e : alternativesFor(plan).entrySet()) {
            RunResult alt = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, observed(), e.getValue().interventions(),
                    null, 0, e.getValue().policyOverrides(), null));
            Map<String, Double> d = HouseKpis.of(plan, alt, hddRef).minus(k);
            out.printf(Locale.ROOT, "   %-18s %+9.0f %+9.2f %+9.0f %+10.0f %+8.0f %+8.0f %+8.0f  %s%n", e.getKey(),
                    d.get("oil_l"), d.get("wood_ster"), d.get("electricity_kwh"), d.get("final_heat_kwh"),
                    d.get("cost_eur"), d.get("co2_kg"), d.get("under_h"), e.getValue().description());
        }
        out.println("   Modelled, not proven: savings count as proven only after metered periods before/after the change"
                + " (weather-normalized).");
    }

    /** {@link #alternatives()} for another plan with the same atom ids (e.g. a calibrated one). */
    public static Map<String, Alternative> alternativesFor(TwinPlan plan) {
        Map<String, Alternative> out = new LinkedHashMap<>();
        List<Intervention> ref = HouseScenario.reference(plan);
        List<Intervention> combined = new ArrayList<>(ref);
        for (HouseScenario.Lever l : HouseScenario.levers(plan)) {
            List<Intervention> log = new ArrayList<>(ref);
            log.addAll(l.interventions());
            out.put(l.name(), new Alternative(l.description(), log, Map.of(), null));
            if (!l.name().equals("more-wood")) {
                combined.addAll(l.interventions());
            }
        }
        out.put("combined", new Alternative("all levers except more-wood", combined, Map.of(), null));
        return out;
    }

    static void report(PrintStream out, BillCalibration.Result c) {
        out.println();
        out.println("0. Calibration against bills (" + c.runs() + " twin runs)");
        c.parameters().forEach((key, v) -> out.printf(Locale.ROOT, "   %-24s %10.3f   (rel. std. error %s)%n", key, v,
                Double.isNaN(c.relStdErr().get(key)) ? "n/a" : String.format(Locale.ROOT, "%.1f %%",
                        100 * c.relStdErr().get(key))));
        out.printf(Locale.ROOT, "   identifiable %s (condition %.3g, max correlation %.3f)%n", c.identifiable(),
                c.conditionNumber(), c.maxCorrelation());
        out.printf(Locale.ROOT, "   %-23s %-14s %10s %10s %8s%n", "period", "quantity", "bill", "model", "diff");
        for (BillCalibration.Period p : c.periods()) {
            out.printf(Locale.ROOT, "   %s..%s %-14s %10.0f %10.0f %+7.1f%%%s%n", p.bill().from(), p.bill().to(),
                    p.quantity(), p.observed(), p.modelled(), 100 * p.residual() / p.observed(),
                    p.proxy() ? "  (delivery as consumption)" : "");
        }
        c.cvRmse().forEach((q, v) -> out.printf(Locale.ROOT, "   %-14s CV(RMSE) %.1f %%, NMBE %+.1f %%%n", q, 100 * v,
                100 * c.nmbe().get(q)));
        c.warnings().forEach(w -> out.println("   ! " + w));
    }
}
