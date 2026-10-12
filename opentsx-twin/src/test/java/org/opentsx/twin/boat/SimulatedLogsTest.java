package org.opentsx.twin.boat;

import org.opentsx.twin.RunResult;
import org.opentsx.twin.Twin;
import org.opentsx.twin.demo.BoatDemo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simulated operating data: reproducible, clearly marked, and the calibration finds the hidden parameters. */
class SimulatedLogsTest {

    @Test
    void bundledLogsAreTheGeneratorOutputAndMarked() {
        List<String> generated = SimulatedLogs.csv(BoatDemo.plan(), SimulatedLogs.SEED);
        assertEquals(generated, BoatDemo.simulatedLogs(), "regenerate with SimulatedLogs.main after model changes");
        assertTrue(generated.get(0).startsWith("# SIMULIERT"));
        assertTrue(generated.contains(SimulatedLogs.HEADER));
        assertNotEquals(generated, SimulatedLogs.csv(BoatDemo.plan(), SimulatedLogs.SEED + 1), "the seed matters");
        // one day with Starlink off in the logbook, some rows without app data, local time without offset
        assertTrue(generated.stream().anyMatch(l -> l.startsWith("2026-07-08 12:00,") && l.split(",")[3].equals("0")));
        assertTrue(generated.stream().anyMatch(l -> l.startsWith("2026-07-09 12:00,") && l.split(",")[3].equals("1")));
        assertTrue(generated.stream().anyMatch(l -> l.matches("^2026-07-1\\d \\d\\d:\\d\\d,[a-z]+,[01],[01],,,,.*")));
    }

    @Test
    void calibrationRecoversTheHiddenParameters() {
        BoatData d = BoatData.parse(BoatDemo.simulatedLogs(), BoatDemo.plan(), SimulatedLogs.ZONE);
        assertEquals(1344, d.plan().steps());
        assertTrue(d.coverage().get("station.soc_meas") < d.plan().steps(), "gaps stay gaps");

        Calibration.Fit fit = BoatDemo.calibrate(d);
        Map<String, Double> v = fit.values();
        Map<String, Double> truth = SimulatedLogs.truth();
        assertEquals(truth.get("solar.derate"), v.get("solar.derate"), 0.02, "fit " + v);
        assertEquals(truth.get("laptops.scale"), v.get("laptops.scale"), 0.06, "fit " + v);
        assertEquals(truth.get("starlink.scale"), v.get("starlink.scale"), 0.03, "fit " + v);
        assertTrue(fit.rmseByTarget().get("station.soc") < 0.01, "SoC within 1 %-point: " + fit.rmseByTarget());
        assertTrue(fit.rmse() < fit.rmseStart());

        // KPIs on the "measured" trip, with the calibrated values, against the hidden true run
        RunResult shadow = new Twin(d.plan()).shadow(d.inputs(), fit.interventions());
        assertTrue(Calibration.rmse(shadow.series("station.soc"), d.socMeas()) < 0.015);
        Map<String, Double> k = BoatDemo.kpis(shadow);
        Map<String, Double> t = BoatDemo.kpis(SimulatedLogs.truthRun(BoatDemo.plan()));
        assertTrue(t.get("unmet_wh") > 500, "the cloudy spell at anchor drains the station: " + t.get("unmet_wh"));
        assertEquals(t.get("unmet_wh"), k.get("unmet_wh"), 0.1 * t.get("unmet_wh"));
        assertEquals(t.get("unmet_h"), k.get("unmet_h"), 0.1 * t.get("unmet_h"));
        assertEquals(t.get("load_wh_per_day"), k.get("load_wh_per_day"), 0.03 * t.get("load_wh_per_day"));
        assertEquals(5.0, k.get("soc_min_pct"), 1e-6);
        assertTrue(k.get("autonomy_h_mean") > 0 && Double.isFinite(k.get("autonomy_h_mean")));
    }
}
