package org.opentsx.windpark.examples;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.analysis.PowerCurveAnalysis;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.sim.PowerCurve;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator example powercurve.bins (docs/user-manual/17-operators.md): the method of bins finds a curtailment. */
class PowerCurveBinsExampleTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final long HOUR = 3_600_000_000L;

    @Test
    void binnedCurveMatchesTheReferenceAndTheCurtailmentIsFound() throws IOException {
        Scenario scenario = new Scenario("bins", 1, START, Duration.ofHours(48), Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", "bins", 54, 13, "GENERIC-3MW", 1, 1, 5, 7, 0.05),
                new Scenario.WindSpec(2.2, 11.0, 0.10, 240, 15, 0.1, 10),
                List.of(new Scenario.Operation(Scenario.OperationType.CURTAILMENT, List.of("T01"), Duration.ofHours(20),
                        Duration.ofHours(6), 500.0)),
                List.of());
        List<Episode> episodes = new ArrayList<>();
        new WindparkSimulator(scenario, ChannelCatalog.standard()).run(episodes::add);
        List<Episode> wind = episodes.stream().filter(e -> e.getSeries().getMetric().equals("wind.met.wind_speed")).toList();
        List<Episode> power = episodes.stream().filter(e -> e.getSeries().getMetric().equals("wind.turbine.power_active")).toList();
        List<Episode> healthyWind = wind.stream().filter(e -> !e.getLabels().containsKey("gt.operation")).toList();
        List<Episode> healthyPower = power.stream().filter(e -> !e.getLabels().containsKey("gt.operation")).toList();

        // usage:begin
        PowerCurveAnalysis.Settings settings = PowerCurveAnalysis.Settings.defaults();
        PowerCurveAnalysis.Result all = PowerCurveAnalysis.analyze(TurbineType.GENERIC_3MW, wind, power, settings);
        List<PowerCurveAnalysis.Underperformance> found = all.underperformance();
        PowerCurveAnalysis.Result healthy = PowerCurveAnalysis.analyze(TurbineType.GENERIC_3MW, healthyWind, healthyPower, settings);
        List<PowerCurveAnalysis.Bin> curve = healthy.bins();
        // usage:end

        TurbineType type = TurbineType.GENERIC_3MW;
        long t0 = Micros.of(START);
        long from = t0 + 20 * HOUR;
        long to = t0 + 26 * HOUR;
        assertEquals(288, all.intervals());
        assertEquals(252, healthy.intervals());
        List<PowerCurveAnalysis.Bin> shown = curve.stream().filter(b -> b.count() >= 5).toList();
        double worst = shown.stream().mapToDouble(b -> Math.abs(b.meanPowerKw() - b.referenceKw())).max().orElseThrow();
        assertTrue(worst <= 0.12 * type.ratedPowerKw(), "largest deviation of a bin " + worst + " kW");
        assertEquals(1.0, healthy.performanceIndex(), 0.03);
        assertTrue(all.performanceIndex() < healthy.performanceIndex());
        long inside = 0;
        long flagged = 0;
        for (PowerCurveAnalysis.Underperformance u : found) {
            inside += Math.max(0, Math.min(u.toMicros(), to) - Math.max(u.fromMicros(), from));
            flagged += u.toMicros() - u.fromMicros();
        }
        assertTrue(inside >= HOUR, "flagged inside the curtailment: " + inside / 3.6e9 + " h");
        assertTrue(inside >= 0.9 * flagged, "flagged outside the curtailment: " + (flagged - inside) / 3.6e9 + " h");

        double[] v = new double[101];
        double[] ref = new double[101];
        for (int i = 0; i <= 100; i++) {
            v[i] = 0.2 * i;
            ref[i] = PowerCurve.powerKw(type, v[i]);
        }
        double[] binWind = shown.stream().mapToDouble(PowerCurveAnalysis.Bin::meanWindMs).toArray();
        double[] binPower = shown.stream().mapToDouble(PowerCurveAnalysis.Bin::meanPowerKw).toArray();
        double[] hour = new double[288];
        double[] expected = new double[288];
        double[] measured = new double[288];
        double[] lost = new double[288];
        for (int k = 0; k < 288; k++) {
            hour[k] = (Micros.of(wind.get(k).getTStart()) - t0) / 3.6e9;
            expected[k] = PowerCurve.powerKw(type, wind.get(k).getSummary().getMean());
            measured[k] = power.get(k).getSummary().getMean();
            lost[k] = expected[k] - measured[k];
        }
        PowerCurveAnalysis.Underperformance first = found.get(0);
        PowerCurveAnalysis.Underperformance last = found.get(found.size() - 1);
        ExampleChart chart = new ExampleChart("powercurve.bins",
                "Power curve: the bins follow the reference curve, the curtailment shows as underperformance");
        chart.panel("Bins of 0.5 m/s with 5 or more healthy intervals", "wind speed, m/s", "kW", "")
                .add("reference", "line", v, ref)
                .add("measured", "point", binWind, binPower)
                .note("expected: bins on the curve")
                .note(String.format("measured: %.0f kW off at most", worst))
                .note(String.format("PI %.3f, all hours %.3f", healthy.performanceIndex(), all.performanceIndex()));
        chart.panel("10-minute means, cap of 500 kW from hour 20 to 26", "hour", "kW", "")
                .add("reference", "line", hour, expected)
                .add("measured", "line", hour, measured);
        chart.panel("Underperformance of all intervals", "hour", "reference minus measured, kW", "")
                .add("lost power", "line", hour, lost)
                .note("expected: 20 to 26")
                .note(String.format("found: %.1f to %.1f", (first.fromMicros() - t0) / 3.6e9, (last.toMicros() - t0) / 3.6e9))
                .note(String.format("%d run, ratio %.2f", found.size(), first.meanRatio()));
        chart.write();
    }
}
