package org.opentsx.twin.boat;

import org.opentsx.model.Micros;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.Value;
import org.opentsx.twin.demo.BoatDemo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV import of real boat data and calibration against measured SoC. */
class BoatDataTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void csvIsResampledAndBoundWithOrigins() {
        List<String> csv = List.of(
                "# example, invented",
                "zeit;modus;landstrom;soc_prozent;solar_w;last_w;starlink_w;kuehlbox_w;notiz",
                "2026-07-04T10:00;hafen;1;80;100,5;40;30;5;arrived; windy",
                "2026-07-04T10:05;hafen;1;82;;40;;5;",
                "2026-07-04T11:00;segeln;0;81;150;45;32;5;",
                "2026-07-04T13:00;anker;0;;;;;;");
        BoatData d = BoatData.parse(csv, BoatDemo.plan(), BERLIN);
        assertEquals(4, d.rows());
        assertEquals(List.of("kuehlbox_w"), d.ignored());
        long t0 = Micros.of(Instant.parse("2026-07-04T08:00:00Z"));
        assertEquals(t0, d.plan().startMicros());
        assertEquals(13, d.plan().steps());
        SeriesInputs in = d.inputs();

        Value soc = in.value("station.soc_meas", t0).orElseThrow();
        assertEquals(0.81, soc.v(), 1e-12);                       // mean of 80 and 82 %
        assertEquals(Origin.MEASURED, soc.origin());
        assertEquals(100.5, in.value("solar.p_meas_w", t0).orElseThrow().v(), 1e-12);   // decimal comma
        assertEquals(Origin.REPORTED, in.value("boat.mode_obs", t0).orElseThrow().origin());
        assertEquals(BoatClock.HARBOUR, in.value("boat.mode_obs", t0).orElseThrow().v());
        assertEquals(1, in.value("shore.available", t0).orElseThrow().v());
        long t1 = t0 + 4 * 900_000_000L;                         // 11:00 local
        assertEquals(BoatClock.SAILING, in.value("boat.mode_obs", t1).orElseThrow().v());
        assertEquals(32, in.value("starlink.p_meas_w", t1).orElseThrow().v());
        // held for up to one hour, then missing
        assertEquals(0.81, in.value("station.soc_meas", t0 + 3 * 900_000_000L).orElseThrow().v(), 1e-12);
        assertEquals(Optional.empty(), in.value("station.soc_meas", t0 + 9 * 900_000_000L));
        assertEquals(Optional.empty(), in.value("solar.p_meas_w", t0 + 12 * 900_000_000L));
        assertEquals(BoatClock.ANCHOR, in.value("boat.mode_obs", t0 + 12 * 900_000_000L).orElseThrow().v());
        assertTrue(Double.isNaN(d.socMeas()[10]));
        assertEquals(0.81, d.socMeas()[4], 1e-12);

        RunResult r = new Twin(d.plan()).shadow(d.inputs(), List.of());
        assertEquals(Origin.MEASURED, r.origins("starlink.p_w")[0]);
        assertEquals(Origin.MEASURED, r.origins("solar.p_avail_w")[0]);
        assertEquals(Origin.REPORTED, r.origins("boat.mode")[0]);
        assertFalse(Double.isNaN(r.series("station.soc_residual")[0]));
        assertEquals(30 / 0.9, r.series("starlink.p_w")[0], 1e-9);
    }

    @Test
    void csvErrorsAreExplained() {
        assertThrows(IllegalArgumentException.class,
                () -> BoatData.parse(List.of("modus,soc_prozent", "hafen,80"), BoatDemo.plan(), BERLIN));
        assertThrows(IllegalArgumentException.class,
                () -> BoatData.parse(List.of("zeit,modus", "2026-07-04T10:00Z,kreuzfahrt"), BoatDemo.plan(), BERLIN));
    }

    @Test
    void templateLoadsAndRuns() throws IOException {
        List<String> lines;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("twin/bootsdaten-vorlage.csv")) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        BoatData d = BoatData.parse(lines, BoatDemo.plan(), BERLIN);
        assertTrue(d.rows() >= 24);
        assertTrue(d.ignored().isEmpty(), "unknown columns " + d.ignored());
        assertTrue(d.coverage().keySet().containsAll(List.of("boat.mode_obs", "station.soc_meas", "solar.p_meas_w")));
        RunResult r = new Twin(d.plan()).shadow(d.inputs(), List.of());
        assertEquals(d.plan().steps(), r.size());
    }

    @Test
    void calibrationRecoversKnownParameters() {
        TwinPlan full = BoatDemo.plan();
        TwinPlan week = new TwinPlan(full.twin(), full.start(), full.baseStep(), 672, full.atoms(), full.wiring());
        SeriesInputs logbook = BoatDemo.logbook(week, BoatDemo.TRIP);
        List<Calibration.Knob> knobs = List.of(
                new Calibration.Knob("solar.derate", List.of("solar.derate"), 0.2, 1.1, 0.75),
                new Calibration.Knob("starlink.scale", List.of("starlink.scale"), 0.2, 3.0, 1.0));
        // "measurements" produced by the model with known parameters
        List<Intervention> truth = Calibration.interventions(week, knobs, new double[]{0.6, 1.4});
        double[] measured = new Twin(week).shadow(logbook, truth).series("station.soc");

        Calibration.Fit fit = Calibration.fit(week, logbook, List.of(), "station.soc", measured, knobs);
        Map<String, Double> v = fit.values();
        assertEquals(0.6, v.get("solar.derate"), 0.02, "fit " + v);
        assertEquals(1.4, v.get("starlink.scale"), 0.05, "fit " + v);
        assertTrue(fit.rmse() < 0.005 && fit.rmse() < fit.rmseStart(), "rmse " + fit.rmse());
        // deterministic
        assertEquals(v, Calibration.fit(week, logbook, List.of(), "station.soc", measured, knobs).values());
    }
}
