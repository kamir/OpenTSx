package org.opentsx.windpark;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.analysis.PowerCurveAnalysis;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.sim.GroundTruth;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerCurveAnalysisTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void healthyTurbineMatchesTheCurveAndOperationsShowUpAsUnderperformance() {
        Scenario s = new Scenario("pc", 31, START, Duration.ofHours(24), Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", "pc", 54, 13, "GENERIC-3MW", 1, 3, 5, 7, 0.05),
                new Scenario.WindSpec(2.2, 10.0, 0.10, 240, 15, 0.1, 10),
                List.of(new Scenario.Operation(Scenario.OperationType.CURTAILMENT, List.of("T02"), Duration.ofHours(6),
                                Duration.ofHours(3), 800.0),
                        new Scenario.Operation(Scenario.OperationType.FAULT, List.of("T03"), Duration.ofHours(12),
                                Duration.ofHours(2), null)),
                List.of());
        WindparkSimulator sim = new WindparkSimulator(s, ChannelCatalog.standard());
        List<Episode> all = new ArrayList<>();
        sim.run(all::add);
        TurbineType type = TurbineType.GENERIC_3MW;

        PowerCurveAnalysis.Result t1 = analyze(all, "T01", type);
        assertTrue(t1.intervals() >= 140, "24 h of 10-min intervals");
        assertTrue(t1.performanceIndex() > 0.9 && t1.performanceIndex() < 1.15, "PI " + t1.performanceIndex());
        assertTrue(t1.bins().stream().anyMatch(b -> b.count() > 5 && Math.abs(b.meanPowerKw() - b.referenceKw()) < 0.15 * type.ratedPowerKw()));
        long t1Lost = t1.underperformance().stream().mapToLong(u -> u.toMicros() - u.fromMicros()).sum();
        assertTrue(t1Lost <= Duration.ofMinutes(30).toNanos() / 1000, "healthy turbine: at most a few flagged intervals");

        for (String turbine : List.of("T02", "T03")) {
            PowerCurveAnalysis.Result r = analyze(all, turbine, type);
            GroundTruth op = sim.groundTruth().stream()
                    .filter(g -> g.category().equals("operation") && g.turbine().equals(turbine)).findFirst().orElseThrow();
            boolean found = r.underperformance().stream().anyMatch(u -> u.fromMicros() < op.toMicros() && u.toMicros() > op.fromMicros());
            assertTrue(found, turbine + ": " + r.underperformance());
            // flagged time lies (almost) entirely inside the operation
            long inside = r.underperformance().stream().mapToLong(u ->
                    Math.max(0, Math.min(u.toMicros(), op.toMicros()) - Math.max(u.fromMicros(), op.fromMicros()))).sum();
            long flagged = r.underperformance().stream().mapToLong(u -> u.toMicros() - u.fromMicros()).sum();
            assertTrue(inside >= 0.8 * flagged, turbine + ": " + inside + " of " + flagged);
            assertTrue(r.performanceIndex() < t1.performanceIndex());
        }
    }

    @Test
    void anIntervalIsLeftOutOnlyWhenWindOrPowerHasNoValueInIt() {
        double[] wind = new double[30];
        double[] power = new double[30];
        Arrays.fill(wind, 8.0);
        Arrays.fill(power, 1000.0);
        Arrays.fill(power, 0, 9, Double.NaN);
        power[9] = 600.0;
        Arrays.fill(power, 10, 20, Double.NaN);
        long t0 = Micros.of(START);
        long minute = 60_000_000L;
        Episode w = Episodes.regular(SeriesKeys.of("wind.met.wind_speed", Map.of("turbine", "T01"), "m/s"), t0, minute, wind)
                .source("test").build();
        Episode p = Episodes.regular(SeriesKeys.of("wind.turbine.power_active", Map.of("turbine", "T01"), "kW"), t0, minute, power)
                .source("test").build();

        PowerCurveAnalysis.Result r = PowerCurveAnalysis.analyze(v -> 1000.0, 3000, List.of(w), List.of(p),
                PowerCurveAnalysis.Settings.defaults());

        assertEquals(2, r.intervals(), "the interval with one power value is kept, the one without is left out");
        assertEquals(1, r.bins().size());
        assertEquals(800.0, r.bins().get(0).meanPowerKw(), 1e-9, "mean of 600 (the only value) and 1000");
    }

    private static PowerCurveAnalysis.Result analyze(List<Episode> all, String turbine, TurbineType type) {
        List<Episode> wind = all.stream().filter(e -> e.getSeries().getMetric().equals("wind.met.wind_speed")
                && e.getSeries().getTags().get("turbine").equals(turbine)).toList();
        List<Episode> power = all.stream().filter(e -> e.getSeries().getMetric().equals("wind.turbine.power_active")
                && e.getSeries().getTags().get("turbine").equals(turbine)).toList();
        return PowerCurveAnalysis.analyze(type, wind, power, PowerCurveAnalysis.Settings.defaults());
    }
}
