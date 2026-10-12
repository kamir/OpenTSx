package org.opentsx.twin.boat;

import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.demo.BoatDemo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scenario "Boot" (Etap 22): plan, energy balance, limits, what-ifs, determinism. */
class BoatTwinTest {

    private final TwinPlan plan = BoatDemo.plan();
    private final Twin twin = new Twin(plan);

    /** A tight setup: Starlink Standard (twice the Mini's power) and no shore power at all. */
    private List<Intervention> tight() {
        return List.of(
                Intervention.set(BoatDemo.START, "starlink", "power_factor", 2.0, "test", "Starlink Standard"),
                Intervention.set(BoatDemo.START, "shore", "enabled", 0, "test", "no shore power"));
    }

    @Test
    void planValidatesAndTypeChecks() {
        assertTrue(AtomCatalog.standard().types().containsAll(
                List.of("boat-mode", "solar", "shore-power", "power-station", "device")));
        new TwinRunner(plan);
        assertEquals(1344, plan.steps());
        // flow into a signal port
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(rewired("vhf.p_w -> station.soc_meas")));
        // signal with a different unit
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(rewired("solar.ghi_wm2 -> vhf.mode")));
        // a load port that the station does not have
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(rewired("vhf.p_w -> station.p_fridge_w")));
        // a second source for an input
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(rewired("raspi.p_w -> station.p_vhf_w")));
        // unknown mode names and itinerary letters are rejected
        assertThrows(IllegalArgumentException.class, () -> new Device("d", org.opentsx.twin.Params.of(
                "windows", Map.of("kitchen", List.of("07-09")))));
        assertThrows(IllegalArgumentException.class, () -> new BoatMode("b", org.opentsx.twin.Params.of(
                "itinerary", "HQ")));
    }

    private TwinPlan rewired(String extra) {
        List<String> w = new ArrayList<>(plan.wiring());
        w.add(extra);
        return new TwinPlan(plan.twin(), plan.start(), plan.baseStep(), plan.steps(), plan.atoms(), w);
    }

    @Test
    void energyBalanceClosesOnTheDcSide() {
        for (List<Intervention> log : List.of(BoatDemo.reference(), tight())) {
            RunResult r = twin.shadow(BoatDemo.observed(), log);
            Map<String, Double> k = r.kpis();
            double lhs = k.get("station.solar_wh") + k.get("station.shore_wh") - k.get("station.served_wh")
                    - k.get("station.loss_wh") + k.get("station.nudge_wh");
            double rhs = k.get("station.soc_wh") - k.get("station.soc_start_wh");
            assertEquals(rhs, lhs, 1e-6, "sources - losses - served load = delta SoC");
            assertEquals(k.get("station.demand_wh"), k.get("station.served_wh") + k.get("station.unmet_wh"), 1e-6);

            // per step at the bus: solar + shore - charging + discharging = served load + idle draw
            double[] pv = r.series("station.solar_used_w");
            double[] shore = r.series("station.shore_used_w");
            double[] batt = r.series("station.p_batt_w");
            double[] load = r.series("station.p_load_w");
            double[] unmet = r.series("station.p_unmet_w");
            for (int i = 0; i < r.size(); i++) {
                double supplied = pv[i] + shore[i] - batt[i];
                if (unmet[i] == 0) {
                    assertEquals(load[i] + 2.0, supplied, 1e-9, "step " + i);
                } else {
                    // short: the station's own draw (2 W) may go unserved too
                    assertTrue(supplied >= load[i] - unmet[i] - 1e-9 && supplied <= load[i] - unmet[i] + 2 + 1e-9);
                }
            }
            // the station's load is the sum of the device draws
            double[] sum = new double[r.size()];
            for (String d : List.of("laptop1", "laptop2", "phone", "starlink", "raspi", "vhf")) {
                double[] p = r.series(d + ".p_w");
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += p[i];
                }
            }
            assertArrayEquals(load, sum, 1e-9);
        }
    }

    @Test
    void socLimitsHoldAndUnmetDemandIsReported() {
        RunResult r = twin.shadow(BoatDemo.observed(), tight());
        for (double s : r.series("station.soc")) {
            assertTrue(s >= 0.05 - 1e-9 && s <= 1 + 1e-9, "SoC " + s);
        }
        Map<String, Double> k = r.kpis();
        assertEquals(0.05, k.get("station.soc_min"), 1e-6);
        assertTrue(k.get("station.unmet_wh") > 100, "unmet " + k.get("station.unmet_wh"));
        assertTrue(r.firstEvent("supply_short").isPresent());
        assertTrue(r.firstEvent("soc_low").isPresent());
        // priority: when the battery still delivers part of the demand, Starlink is cut first and the VHF radio last;
        // when it is empty, everything goes off
        double starlinkShare = k.get("station.unmet_starlink_wh") / k.get("starlink.energy_wh");
        double raspiShare = k.get("station.unmet_raspi_wh") / k.get("raspi.energy_wh");
        assertTrue(starlinkShare > raspiShare, starlinkShare + " vs " + raspiShare);
        assertTrue(k.get("station.unmet_vhf_wh") <= k.get("station.unmet_raspi_wh") + 1e-9);
        double byLoad = 0;
        for (String d : List.of("laptop1", "laptop2", "phone", "starlink", "raspi", "vhf")) {
            byLoad += k.get("station.unmet_" + d + "_wh");
        }
        assertEquals(k.get("station.unmet_wh"), byLoad, 1e-6);
    }

    @Test
    void runsAreDeterministic() {
        RunResult a = twin.shadow(BoatDemo.observed(), tight());
        RunResult b = twin.shadow(BoatDemo.observed(), tight());
        assertEquals(a.hash(), b.hash());
        assertEquals(a.runId(), b.runId());
        assertArrayEquals(a.series("station.soc"), b.series("station.soc"));
        assertFalse(a.hash().equals(twin.shadow(BoatDemo.observed(), BoatDemo.reference()).hash()));
    }

    @Test
    void moreSolarReducesUnmetDemand() {
        List<Intervention> alt = new ArrayList<>(tight());
        alt.add(Intervention.set(BoatDemo.START, "solar", "wp", 300, "test", "+100 Wp"));
        Twin.WhatIf w = twin.whatIf(BoatDemo.observed(), tight(), alt);
        Map<String, Double> d = w.kpiDelta();
        assertTrue(d.get("station.unmet_wh") < -50, "less unmet energy: " + d.get("station.unmet_wh"));
        assertTrue(d.get("station.solar_wh") > 0);
        assertTrue(d.get("solar.yield_wh") > 0.45 * w.baseline().kpis().get("solar.yield_wh"));
        assertTrue(BoatDemo.kpis(w.alternative()).get("autonomy_h_mean")
                > BoatDemo.kpis(w.baseline()).get("autonomy_h_mean"));
    }

    @Test
    void starlinkScheduleAndShoreEveryNthDay() {
        List<Intervention> log = List.of(
                Intervention.set(BoatDemo.START, "starlink", "max_on_h", 3, "test", "3 h"),
                Intervention.set(BoatDemo.START, "shore", "every_n_days", 3, "test", "every 3rd day"));
        RunResult r = twin.shadow(BoatDemo.observed(), log);
        double[] active = r.series("starlink.active");
        double[] connected = r.series("shore.connected");
        long dayZero = java.time.LocalDate.of(2026, 7, 4).toEpochDay();
        double[] perDay = new double[14];
        for (int i = 0; i < r.size(); i++) {
            int day = (int) BoatClock.dayIndex(r.timeMicros(i), 2, dayZero);
            perDay[day] += active[i] * 0.25;
            if (connected[i] > 0) {
                assertEquals(0, day % 3, "shore only on every third day");
            }
        }
        for (double h : perDay) {
            assertTrue(h <= 3 + 1e-9 && h > 0, "Starlink hours per day " + h);
        }
    }

    @Test
    void originsFollowTheLadder() {
        RunResult r = twin.shadow(BoatDemo.observed(), BoatDemo.reference());
        assertEquals(Origin.REPORTED, r.origin("boat.mode"));
        assertEquals(Origin.SYNTHETIC, r.origin("starlink.p_w"));
        assertEquals(Origin.SYNTHETIC, r.origin("solar.p_avail_w"));
        assertEquals(Origin.SYNTHETIC, r.origin("station.soc"));
        // without the logbook, the itinerary of the plan gives the same modes, but as SYNTHETIC
        RunResult plain = twin.shadow(new SeriesInputs(), List.of());
        assertEquals(Origin.SYNTHETIC, plain.origin("boat.mode"));
        assertArrayEquals(r.series("boat.mode"), plain.series("boat.mode"));
    }

    @Test
    void scenarioApiForTheStudio() {
        RunResult r = BoatDemo.shadow();
        assertTrue(r.keys().containsAll(BoatDemo.KEY_SERIES), "key series exist");
        assertTrue(BoatDemo.kpis(r).keySet().containsAll(BoatDemo.KEY_KPIS), "key KPIs exist");
        assertTrue(BoatDemo.reference().isEmpty());
        Set<String> names = new HashSet<>();
        for (BoatDemo.Alternative a : BoatDemo.alternatives()) {
            assertTrue(names.add(a.name()), "unique name " + a.name());
            RunResult alt = BoatDemo.run(plan, a, List.of());
            assertEquals(r.size(), alt.size());
            assertFalse(alt.hash().equals(r.hash()), a.name() + " changes something");
        }
        assertTrue(names.containsAll(List.of("solar300", "starlink3h", "station2kwh", "shore3d")));
        // KPI sanity of the reference: loads in the assumed range, Starlink Mini the largest consumer
        Map<String, Double> k = BoatDemo.kpis(r);
        assertTrue(k.get("load_wh_per_day") > 500 && k.get("load_wh_per_day") < 1200, "load " + k);
        assertTrue(k.get("starlink_wh_per_day") > k.get("raspi_wh_per_day"));
    }

    @Test
    void forecastFromASnapshotReproducesTheShadow() {
        RunResult r = twin.shadow(BoatDemo.observed(), BoatDemo.reference());
        RunResult f = twin.forecast(r.snapshotAfter(671), BoatDemo.observed(), 1344 - 672, Map.of(), List.of());
        double[] a = r.series("station.soc");
        double[] b = f.series("station.soc");
        assertArrayEquals(java.util.Arrays.copyOfRange(a, 672, 1344), b, 0);
    }
}
