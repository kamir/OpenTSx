package org.opentsx.twin;

import org.opentsx.twin.demo.H2ChainDemo;

import java.time.Instant;
import java.util.List;

/**
 * Scenario S1 (H2 chain) in its first form, where the line is the only buffer: the plan and the observed history the
 * tests of the runner were written against. The plan in use is tested in {@code H2ChainTest}.
 */
final class S1 {

    static final Instant START = H2ChainDemo.START;
    static final long T0 = H2ChainDemo.T0;
    static final long MIN = H2ChainDemo.MIN;
    static final int STEPS = H2ChainDemo.STEPS;
    static final double ELY_KW = H2ChainDemo.ELY_KW;
    static final double OFFTAKE_KGPH = H2ChainDemo.OFFTAKE_KGPH;

    private S1() {
    }

    static TwinPlan plan() {
        return TwinPlan.resource("twin/h2-chain-1.yaml");
    }

    static SeriesInputs observed() {
        double[] price = new double[STEPS / 15];
        for (int i = 0; i < price.length; i++) {
            price[i] = 60 + 20 * Math.sin(i / 4.0);
        }
        return new SeriesInputs()
                .bind("park.p_avail_kw", T0, MIN, constant(STEPS, 8000), Origin.MEASURED)
                .bind("ely.setpoint_kw", T0, MIN, constant(STEPS, ELY_KW), Origin.REPORTED)
                .bind("offtake.demand_kgph", T0, MIN, constant(STEPS, OFFTAKE_KGPH), Origin.MEASURED)
                .bind("marketer.schedule_kw", T0, MIN, constant(STEPS, 3000), Origin.REPORTED)
                .bind("battery.power_kw", T0, MIN, constant(STEPS, 0), Origin.REPORTED)
                .bind("market.price_eur_mwh", T0, 15 * MIN, price, Origin.MEASURED);
    }

    static List<Intervention> valveClosed() {
        return List.of(Intervention.set(START.plusSeconds(3600), "offtake", "valve", 0, "h2-offtake", "valve closed"));
    }

    static long at(double minutes) {
        return T0 + (long) (minutes * MIN);
    }

    static double[] constant(int n, double v) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** Analytic time (minutes after start) when the line reaches 40 bar after the valve closes at 60 min. */
    static double analyticLimitMinute() {
        double kgPerBar = 1e5 * 157 / (4124.2 * 288);
        return 60 + (40 - 30) * kgPerBar / OFFTAKE_KGPH * 60;
    }
}
