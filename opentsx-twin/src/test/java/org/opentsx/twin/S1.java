package org.opentsx.twin;

import org.opentsx.twin.demo.H2ChainDemo;

import java.time.Instant;
import java.util.List;

/** Scenario S1 (H2 chain) for the tests; data from {@link H2ChainDemo}. */
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
        return H2ChainDemo.plan();
    }

    static SeriesInputs observed() {
        return H2ChainDemo.observed();
    }

    static List<Intervention> valveClosed() {
        return H2ChainDemo.valveClosed();
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
