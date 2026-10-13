package org.opentsx.twin.demo;

import org.opentsx.model.Micros;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Snapshot;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinEvent;
import org.opentsx.twin.TwinPlan;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scenario S1 (H2 chain) in all three modes: the handover station closes its valve after one hour, the pipeline
 * stops taking gas, the buffer fills, the electrolyzer trips, the marketer has to sell more. Prints the event timeline and KPIs of
 * <ol>
 *   <li>shadow: the observed history,</li>
 *   <li>what-if: the valve reopens after two hours,</li>
 *   <li>forecast from minute 70: schedule-following vs. the {@code pressure-aware} policy.</li>
 * </ol>
 * Usage: {@code bin/opentsx twin [--plan file.yaml]}
 */
public final class H2ChainDemo {

    public static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    public static final long T0 = Micros.of(START);
    public static final long MIN = 60_000_000L;
    public static final int STEPS = 360;
    public static final double ELY_KW = 5000;
    public static final double KWH_PER_KG = 55;
    public static final double OFFTAKE_KGPH = ELY_KW / KWH_PER_KG;

    private H2ChainDemo() {
    }

    public static TwinPlan plan() {
        return TwinPlan.resource("twin/h2-chain.yaml");
    }

    /**
     * Observed history: wind 8 MW, electrolyzer schedule 5 MW, battery idle, matching offtake. The feed-in schedule is
     * 2.93 MW: what is left after the electrolyzer and the compressor (about 70 kW at 120 bar).
     */
    public static SeriesInputs observed() {
        double[] price = new double[STEPS / 15];
        for (int i = 0; i < price.length; i++) {
            price[i] = 60 + 20 * Math.sin(i / 4.0);
        }
        return new SeriesInputs()
                .bind("park.p_potential_kw", T0, MIN, constant(STEPS, 8000), Origin.MEASURED)
                .bind("ely.setpoint_kw", T0, MIN, constant(STEPS, ELY_KW), Origin.REPORTED)
                .bind("offtake.demand_kgph", T0, MIN, constant(STEPS, OFFTAKE_KGPH), Origin.MEASURED)
                .bind("marketer.schedule_kw", T0, MIN, constant(STEPS, 2930), Origin.REPORTED)
                .bind("battery.power_kw", T0, MIN, constant(STEPS, 0), Origin.REPORTED)
                .bind("market.price_eur_mwh", T0, 15 * MIN, price, Origin.MEASURED);
    }

    /** The handover station closes its valve after one hour. */
    public static List<Intervention> valveClosed() {
        return List.of(Intervention.set(START.plusSeconds(3600), "handover", "valve", 0, "h2-handover", "valve closed"));
    }

    static double[] constant(int n, double v) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    public static void main(String[] args) {
        TwinPlan plan = plan();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--plan") && i + 1 < args.length) {
                plan = TwinPlan.load(Path.of(args[++i]));
            } else {
                System.err.println("usage: bin/opentsx twin [--plan file.yaml]");
                System.exit(1);
            }
        }
        Twin twin = new Twin(plan);
        PrintStream out = System.out;

        RunResult shadow = twin.shadow(observed(), valveClosed());
        report(out, "1. Shadow: observed history (valve closed at 01:00)", shadow);

        List<Intervention> reopened = new ArrayList<>(valveClosed());
        reopened.add(Intervention.set(START.plusSeconds(7200), "handover", "valve", 1, "h2-handover", "valve reopened"));
        Twin.WhatIf w = twin.whatIf(observed(), valveClosed(), reopened);
        report(out, "2. What-if: valve reopened at 02:00", w.alternative());
        out.println("   KPI delta (what-if minus baseline):");
        w.kpiDelta().forEach((k, v) -> {
            if (Math.abs(v) > 1e-9) {
                out.printf(Locale.ROOT, "     %-28s %+12.1f%n", k, v);
            }
        });

        Snapshot at70 = shadow.snapshotAfter(69);
        RunResult follow = twin.forecast(at70, observed().as(Origin.FORECAST), STEPS - 70, Map.of(), List.of());
        RunResult aware = twin.forecast(at70, observed().as(Origin.FORECAST), STEPS - 70,
                Map.of("ely", "pressure-aware"), List.of());
        report(out, "3a. Forecast from 01:10, electrolyzer follows its schedule", follow);
        report(out, "3b. Forecast from 01:10, electrolyzer policy pressure-aware", aware);
    }

    private static void report(PrintStream out, String title, RunResult r) {
        out.println();
        out.println(title);
        out.println("   run " + r.runId() + "  mode " + r.mode() + "  hash " + r.hash());
        for (TwinEvent e : r.events()) {
            out.printf(Locale.ROOT, "   %s  %-10s %-24s %s%n", Micros.toInstant(e.tMicros()).toString().substring(11, 19),
                    e.atom(), e.type(), e.attrs());
        }
        out.printf(Locale.ROOT, "   max buffer pressure %.2f bar, electrolyzer trips %.0f, H2 %.1f kg, extra feed-in %.0f kWh%n",
                r.max("buffer.p_bar"), r.kpis().get("ely.trips"), r.kpis().get("ely.h2_kg"),
                r.kpis().get("marketer.extra_kwh"));
    }
}
