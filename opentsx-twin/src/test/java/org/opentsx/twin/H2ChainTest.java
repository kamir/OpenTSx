package org.opentsx.twin;

import org.junit.jupiter.api.Test;
import org.opentsx.twin.atoms.H2Line;
import org.opentsx.twin.demo.H2ChainDemo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The H2 chain in the plan in use: electrolyzer, compressor, high-pressure buffer, pipeline, handover station,
 * customer. The numbers the tests expect follow from the parameters of the plan, computed here a second time.
 */
class H2ChainTest {

    static final int STEPS = H2ChainDemo.STEPS;
    static final double M = H2ChainDemo.OFFTAKE_KGPH;
    /** kg of hydrogen per bar in the buffer (20 m3, 288 K, ideal gas) */
    static final double BUFFER_KG_PER_BAR = 1e5 * 20 / (H2Line.R_H2 * 288);
    static final double LINE_KG_PER_BAR = 1e5 * 157 / (H2Line.R_H2 * 288);

    private final Twin twin = new Twin(H2ChainDemo.plan());
    private final RunResult r = twin.shadow(H2ChainDemo.observed(), H2ChainDemo.valveClosed());

    static long at(double minutes) {
        return H2ChainDemo.T0 + (long) (minutes * H2ChainDemo.MIN);
    }

    @Test
    void thePlanFeedsEveryInputOrLeavesItToTheOutside() {
        TwinPlan p = H2ChainDemo.plan();
        assertEquals(12, p.atoms().size());
        assertEquals(20, p.wiring().size());
        // what is bound from outside in the observed history, and nothing else is needed for a run
        assertEquals(360, r.series("park.p_gen_kw").length);
    }

    @Test
    void theChainRestsWhileProductionEqualsDelivery() {
        for (int k : new int[]{0, 1, 30, 59}) {
            assertEquals(30.0, r.series("line.p_in_bar")[k], 1e-9, "line at " + k);
            assertEquals(120.0, r.series("buffer.p_bar")[k], 1e-9, "buffer at " + k);
            assertEquals(M, r.series("handover.m_out_kgph")[k], 1e-9);
            assertEquals(0, r.series("handover.shortfall_kgph")[k], 1e-9);
        }
        // the pressure at the far end is lower by the loss of the flow that is taken out
        assertEquals(30.0 - 1.5 * (M / 100) * (M / 100), r.series("line.p_out_bar")[59], 1e-9);
        assertEquals(r.series("line.p_out_bar")[58], r.series("handover.p_delivery_bar")[59], 1e-12);
    }

    @Test
    void aClosedValveFillsTheBufferAndNotThePipeline() {
        long valve = r.firstEvent("intervention").orElseThrow().tMicros();
        long warn = r.firstEvent("time_to_limit_warning").orElseThrow().tMicros();
        long limit = r.firstEvent("pressure_limit").orElseThrow().tMicros();
        long trip = r.firstEvent("protective_shutdown").orElseThrow().tMicros();
        long deviation = r.firstEvent("schedule_deviation").orElseThrow().tMicros();
        assertEquals(at(60), valve);
        assertTrue(valve < warn && warn < limit && limit <= trip && trip <= deviation, r.events().toString());
        assertEquals("buffer", r.firstEvent("pressure_limit").orElseThrow().atom());

        // the buffer has room for (200 - 120) bar; the electrolyzer fills it at its full production
        double analytic = 60 + (200 - 120) * BUFFER_KG_PER_BAR / M * 60;
        assertEquals(analytic, (limit - H2ChainDemo.T0) / 60e6, 0.05);
        assertEquals(analytic - 60, (warn - H2ChainDemo.T0) / 60e6, 1.01);

        // the regulator closes: the pipeline keeps the one minute of flow that was on its way, and no more
        double pLine = 30 + M / 60 / LINE_KG_PER_BAR;
        assertEquals(pLine, r.series("line.p_in_bar")[60], 1e-9);
        assertEquals(pLine, r.series("line.p_in_bar")[STEPS - 1], 1e-9);
        assertEquals(pLine, r.series("line.p_out_bar")[STEPS - 1], 1e-9, "no flow, no loss");
        assertTrue(r.max("line.p_in_bar") < 40);
        assertEquals(0, r.series("buffer.m_out_kgph")[61], 1e-9);

        assertEquals(1, r.kpis().get("ely.trips"));
        assertEquals(0, r.series("ely.p_el_kw")[STEPS - 1], 1e-9);
        assertEquals(M * 5, r.kpis().get("handover.shortfall_kg"), 1e-6, "five hours of demand not delivered");
        assertEquals(M, r.kpis().get("handover.delivered_kg"), 1e-6, "one hour delivered");
    }

    @Test
    void powerAndHydrogenAreConserved() {
        double[] gen = r.series("park.p_gen_kw");
        double[] ely = r.series("ely.p_el_kw");
        double[] comp = r.series("compressor.p_el_kw");
        double[] bat = r.series("battery.p_bat_kw");
        double[] grid = r.series("grid.p_grid_kw");
        for (int i = 0; i < STEPS; i++) {
            assertEquals(0, gen[i] - ely[i] - comp[i] - bat[i] - grid[i], 1e-9, "power balance at step " + i);
        }
        // hydrogen: what the electrolyzer made is in the buffer, in the pipeline, delivered, or on a wire that
        // crosses a step (electrolyzer to compressor, buffer to pipeline)
        double buffer0 = 120 * BUFFER_KG_PER_BAR;
        double line0 = 30 * LINE_KG_PER_BAR;
        double made = r.kpis().get("ely.h2_kg");
        double compressed = r.kpis().get("compressor.h2_kg");
        double delivered = r.kpis().get("handover.delivered_kg");
        double inBuffer = r.last("buffer.state.mass_kg") - buffer0;
        double inLine = r.last("line.state.mass_kg") - line0;
        // the compressor is one step behind the electrolyzer: it also took the resting minute before the run, and
        // has not yet seen the last one, which is zero after the shutdown
        assertEquals(made + M / 60, compressed, 1e-9);
        // the pipeline is one step behind the buffer in the same way
        assertEquals(compressed + M / 60, inBuffer + inLine + delivered, 1e-9);
        assertEquals(delivered, r.kpis().get("offtake.received_kg"), 1e-9);
    }

    @Test
    void theCompressorDrawsMoreAgainstAFullerBuffer() {
        double[] p = r.series("compressor.p_el_kw");
        // 30 to 120 bar: cp T / eta * (4^0.2908 - 1), one adiabatic stage
        double kwhPerKg = 14_300 * 288 / 0.75 * (Math.pow(120.0 / 30, 0.41 / 1.41) - 1) / 3.6e6;
        assertEquals(M * kwhPerKg, p[0], 1e-9);
        assertTrue(p[140] > p[70] && p[70] > p[0], "rises with the pressure in the buffer");
        assertEquals(0, p[STEPS - 1], 1e-9);
        assertEquals(p[59], r.series("grid.p_grid_kw")[59] * -1 + 8000 - 5000, 1e-9, "it is own consumption at the grid connection");
    }

    @Test
    void reopeningTheValveInTimeAvoidsTheTrip() {
        List<Intervention> reopened = new ArrayList<>(H2ChainDemo.valveClosed());
        reopened.add(Intervention.set(H2ChainDemo.START.plusSeconds(120 * 60), "handover", "valve", 1, "h2-handover", "valve reopened"));
        Twin.WhatIf w = twin.whatIf(H2ChainDemo.observed(), H2ChainDemo.valveClosed(), reopened);
        assertEquals(1, w.baseline().kpis().get("ely.trips"));
        assertEquals(0, w.alternative().kpis().get("ely.trips"));
        assertTrue(w.alternative().max("buffer.p_bar") < 200);
        assertTrue(w.kpiDelta().get("handover.delivered_kg") > 0);
    }

    @Test
    void thePressureAwarePolicyReadsTheBuffer() {
        Snapshot at70 = r.snapshotAfter(69);
        RunResult follow = twin.forecast(at70, H2ChainDemo.observed().as(Origin.FORECAST), STEPS - 70, java.util.Map.of(), List.of());
        RunResult aware = twin.forecast(at70, H2ChainDemo.observed().as(Origin.FORECAST), STEPS - 70, java.util.Map.of("ely", "pressure-aware"), List.of());
        assertEquals(1, follow.kpis().get("ely.trips"));
        assertEquals(0, aware.kpis().get("ely.trips"));
        assertTrue(aware.max("buffer.p_bar") < 200, "max " + aware.max("buffer.p_bar"));
    }

    @Test
    void theNightOfTheScenarioSeparatesRuleAndDispatch() {
        RunResult night = twin.shadow(H2ChainDemo.observed(), H2ChainDemo.reference());
        double[] avail = night.series("park.p_avail_kw");
        double[] gen = night.series("park.p_gen_kw");
        // 04:00 to 05:00 the rule of the plan holds the park at 6 MW; 05:00 to 05:30 the operator dispatches it to 5 MW
        assertEquals(8000, avail[239]);
        assertEquals(6000, avail[240]);
        assertEquals(6000, gen[299]);
        assertEquals(8000, avail[300], "the rule has ended; what is available is the potential again");
        assertEquals(5000, gen[300]);
        assertEquals(8000, gen[330], "the dispatch is released");
        assertEquals(2000, night.kpis().get("park.curtailed_rules_kwh"), 1e-6);
        assertEquals(1500, night.kpis().get("park.curtailed_dispatch_kwh"), 1e-6);
        assertEquals("bat curtailment", night.firstEvent("restriction_start").orElseThrow().attrs().get("reason"));
        // the story of the valve is the same with and without the later dispatch
        assertEquals(r.firstEvent("protective_shutdown").orElseThrow().tMicros(), night.firstEvent("protective_shutdown").orElseThrow().tMicros());
    }

    @Test
    void theParkSaysWhyEnergyWasNotGenerated() {
        TwinPlan plan = H2ChainDemo.plan();
        List<Intervention> log = List.of(
                Intervention.set(H2ChainDemo.START.plusSeconds(60 * 60), "park", "limit_kw", 6000, "rules", "bat curtailment"),
                Intervention.set(H2ChainDemo.START.plusSeconds(120 * 60), "park", "dispatch_kw", 5000, "operator", "negative price"));
        RunResult x = new Twin(plan).shadow(H2ChainDemo.observed(), log);
        assertEquals(8000, x.series("park.p_avail_kw")[59]);
        assertEquals(6000, x.series("park.p_avail_kw")[60]);
        assertEquals(6000, x.series("park.p_gen_kw")[119]);
        assertEquals(5000, x.series("park.p_gen_kw")[120]);
        assertEquals(6000, x.series("park.p_avail_kw")[120], "the dispatch does not change what is available");
        // 5 h at 2000 kW lost to the rule; 4 h at 1000 kW dispatched away; no kilowatt counted twice
        assertEquals(10_000, x.kpis().get("park.curtailed_rules_kwh"), 1e-6);
        assertEquals(4_000, x.kpis().get("park.curtailed_dispatch_kwh"), 1e-6);
        assertEquals(x.kpis().get("park.potential_kwh"),
                x.kpis().get("park.energy_kwh") + x.kpis().get("park.curtailed_rules_kwh") + x.kpis().get("park.curtailed_dispatch_kwh"), 1e-6);
    }
}
