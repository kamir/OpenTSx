package org.opentsx.twin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What-if and forecast modes on scenario S1. */
class TwinModesTest {

    private final Twin twin = new Twin(S1.plan());

    @Test
    void whatIfTheValveReopensBeforeTheLimit() {
        List<Intervention> reopened = new ArrayList<>(S1.valveClosed());
        reopened.add(Intervention.set(S1.START.plusSeconds(120 * 60), "offtake", "valve", 1, "h2-offtake",
                "valve reopened"));
        Twin.WhatIf w = twin.whatIf(S1.observed(), S1.valveClosed(), reopened);

        assertEquals(Mode.WHAT_IF, w.alternative().mode());
        assertEquals(1, w.baseline().kpis().get("ely.trips"));
        assertEquals(0, w.alternative().kpis().get("ely.trips"));
        assertTrue(w.alternative().max("line.p_bar") < 40);
        assertTrue(w.alternative().events("protective_shutdown").isEmpty());

        Map<String, Double> delta = w.kpiDelta();
        assertEquals(-1, delta.get("ely.trips"));
        assertTrue(delta.get("marketer.extra_kwh") < -10_000, "less energy to sell on top: " + delta);
        assertTrue(delta.get("ely.h2_kg") > 0, "more hydrogen produced: " + delta);

        // identical up to the reopening: the twin changes only what the decision touches
        double[] b = w.baseline().series("line.p_bar");
        double[] a = w.alternative().series("line.p_bar");
        assertArrayEquals(java.util.Arrays.copyOf(b, 120), java.util.Arrays.copyOf(a, 120));
    }

    @Test
    void whatIfTheOperatorReducesTheElectrolyzerEarly() {
        List<Intervention> early = new ArrayList<>(S1.valveClosed());
        early.add(Intervention.set(S1.START.plusSeconds(90 * 60), "ely", "setpoint_kw", 0, "operator",
                "reduce load before the line is full"));
        Twin.WhatIf w = twin.whatIf(S1.observed(), S1.valveClosed(), early);
        RunResult alt = w.alternative();
        assertEquals(0, alt.kpis().get("ely.trips"));
        assertTrue(alt.max("line.p_bar") < 40);
        // the deviation starts earlier, at the operator's decision, not at the trip
        long dev = alt.firstEvent("schedule_deviation").orElseThrow().tMicros();
        assertEquals(S1.at(90), dev);
        // consistency: the power balance still closes in every step
        double[] gen = alt.series("park.p_gen_kw");
        double[] ely = alt.series("ely.p_el_kw");
        double[] grid = alt.series("grid.p_grid_kw");
        for (int i = 0; i < alt.size(); i++) {
            assertEquals(gen[i] - ely[i], grid[i], 1e-9);
        }
    }

    @Test
    void forecastContinuesExactlyFromAShadowSnapshot() {
        RunResult shadow = twin.shadow(S1.observed(), S1.valveClosed());
        Snapshot at70 = shadow.snapshotAfter(69);
        assertEquals(70, at70.k());
        assertEquals(0.0, at70.overrides().get("offtake").get("valve"));   // the closed valve stays in force

        // same values, now labelled as forecast: identical trajectory, origin FORECAST
        RunResult fc = twin.forecast(at70, S1.observed().as(Origin.FORECAST), 290, Map.of(), List.of());
        assertEquals(Mode.FORECAST, fc.mode());
        assertEquals(70, fc.firstK());
        double[] s = shadow.series("line.p_bar");
        double[] f = fc.series("line.p_bar");
        assertArrayEquals(java.util.Arrays.copyOfRange(s, 70, 360), f);
        assertEquals(Origin.FORECAST, fc.origin("marketer.deviation_kw"));
        assertEquals(1, fc.kpis().get("ely.trips"));
    }

    @Test
    void pressureAwarePolicyAvoidsTheTrip() {
        RunResult shadow = twin.shadow(S1.observed(), S1.valveClosed());
        Snapshot at70 = shadow.snapshotAfter(69);
        RunResult follow = twin.forecast(at70, S1.observed().as(Origin.FORECAST), 290, Map.of(), List.of());
        RunResult aware = twin.forecast(at70, S1.observed().as(Origin.FORECAST), 290,
                Map.of("ely", "pressure-aware"), List.of());

        assertEquals(1, follow.kpis().get("ely.trips"));
        assertEquals(0, aware.kpis().get("ely.trips"));
        assertTrue(aware.max("line.p_bar") < 40, "max " + aware.max("line.p_bar"));
        // following the schedule overfills the line (overshoot during the ramp-down); the aware policy does not
        assertTrue(follow.max("line.p_bar") > 40);
        // it reduces load gradually and is still producing when the schedule-following run has already tripped
        int tripStep = (int) ((follow.firstEvent("protective_shutdown").orElseThrow().tMicros() - at70.tMicros()) / S1.MIN);
        assertTrue(aware.series("ely.p_el_kw")[tripStep + 10] > 0);
        assertEquals(0, follow.series("ely.p_el_kw")[tripStep + 10], 1e-9);
        assertEquals(S1.ELY_KW, aware.series("ely.p_el_kw")[0], 1e-9);
        // a policy change is part of the run identity
        assertTrue(!aware.runId().equals(follow.runId()));
    }
}
