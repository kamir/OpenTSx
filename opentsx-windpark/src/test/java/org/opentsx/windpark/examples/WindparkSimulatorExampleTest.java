package org.opentsx.windpark.examples;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.sim.GroundTruth;
import org.opentsx.windpark.sim.PowerCurve;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator example gen.windpark (docs/user-manual/17-operators.md): power follows the curve, a stuck anemometer does not. */
class WindparkSimulatorExampleTest {

    @Test
    void powerFollowsTheCurveAndTheStuckSensorIsInTheGroundTruth() throws IOException {
        // usage:begin
        Scenario scenario = new Scenario("example", 10, Instant.parse("2026-10-01T00:00:00Z"), Duration.ofHours(6),
                Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", "example", 54, 13, "GENERIC-3MW", 1, 1, 5, 7, 0.05), Scenario.WindSpec.DEFAULT, List.of(),
                List.of(new Scenario.SensorFault(Scenario.SensorFaultType.STUCK, "T01", "wind.met.wind_speed",
                        Duration.ofHours(2), Duration.ofMinutes(30), null, null, null)));
        WindparkSimulator sim = new WindparkSimulator(scenario, ChannelCatalog.standard());
        List<Episode> episodes = new ArrayList<>();
        sim.run(episodes::add);
        List<GroundTruth> truth = sim.groundTruth();
        // usage:end

        List<Episode> windEpisodes = episodes.stream()
                .filter(e -> e.getSeries().getMetric().equals("wind.met.wind_speed")).toList();
        double[] wind = windEpisodes.stream().flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
        double[] power = episodes.stream().filter(e -> e.getSeries().getMetric().equals("wind.turbine.power_active"))
                .flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
        assertEquals(21_600, wind.length);
        assertEquals(21_600, power.length);

        assertEquals(1, truth.size());
        GroundTruth fault = truth.get(0);
        long t0 = Micros.of(scenario.start());
        assertEquals(List.of("sensor-fault", "STUCK", "T01", "wind.met.wind_speed"),
                List.of(fault.category(), fault.type(), fault.turbine(), fault.channel()));
        assertEquals(List.of(t0 + 7_200_000_000L, t0 + 9_000_000_000L), List.of(fault.fromMicros(), fault.toMicros()));
        List<Long> labelled = windEpisodes.stream().filter(e -> "STUCK".equals(e.getLabels().get("gt.sensor_fault")))
                .map(e -> (Micros.of(e.getTStart()) - t0) / 60_000_000L).toList();
        assertEquals(List.of(120L, 130L, 140L), labelled);

        TurbineType type = TurbineType.GENERIC_3MW;
        double[] hour = new double[360];
        double[] minuteWind = new double[360];
        double[] minutePower = new double[360];
        double off = 0;
        for (int m = 0; m < 360; m++) {
            hour[m] = m / 60.0;
            minuteWind[m] = Arrays.stream(wind, 60 * m, 60 * m + 60).average().orElseThrow();
            minutePower[m] = Arrays.stream(power, 60 * m, 60 * m + 60).average().orElseThrow();
            if (m < 120 || m >= 150) {
                off += Math.abs(minutePower[m] - PowerCurve.powerKw(type, minuteWind[m])) / 330;
            }
        }
        assertTrue(off <= 0.03 * type.ratedPowerKw(), "mean distance from the curve " + off + " kW");
        double[] stuckWind = Arrays.copyOfRange(wind, 7200, 9000);
        double[] stuckPower = Arrays.copyOfRange(minutePower, 120, 150);
        double stuckLow = Arrays.stream(stuckPower).min().orElseThrow();
        double stuckHigh = Arrays.stream(stuckPower).max().orElseThrow();
        assertEquals(1, Arrays.stream(stuckWind).distinct().count(), "the stuck anemometer repeats one value");
        assertTrue(stuckHigh - stuckLow > 500, "power keeps changing while the wind reading is stuck");

        double[] v = new double[76];
        double[] ref = new double[76];
        for (int i = 0; i <= 75; i++) {
            v[i] = 0.2 * i;
            ref[i] = PowerCurve.powerKw(type, v[i]);
        }
        double[] normalWind = minuteWind.clone();
        Arrays.fill(normalWind, 120, 150, Double.NaN);
        double[] stuckHour = Arrays.copyOfRange(hour, 120, 150);
        double[] stuckMinuteWind = Arrays.copyOfRange(minuteWind, 120, 150);
        double[] normalX = new double[330];
        double[] normalY = new double[330];
        int k = 0;
        for (int m = 0; m < 360; m++) {
            if (m < 120 || m >= 150) {
                normalX[k] = minuteWind[m];
                normalY[k] = minutePower[m];
                k++;
            }
        }
        ExampleChart chart = new ExampleChart("gen.windpark",
                "Power follows the curve, the stuck anemometer does not");
        chart.panel("Wind speed of T01, 1-minute means", "hour", "m/s", "")
                .add("normal", "line", hour, normalWind)
                .add("stuck sensor", "line", stuckHour, stuckMinuteWind);
        chart.panel("Power against wind speed, 1-minute means", "wind speed, m/s", "kW", "")
                .add("reference", "line", v, ref)
                .add("normal", "point", normalX, normalY)
                .add("stuck sensor", "point", stuckMinuteWind, stuckPower)
                .note("ground truth: STUCK, hour 2 to 2.5")
                .note(String.format("normal: %.0f kW from the curve", off))
                .note(String.format("stuck: %.2f m/s, %.0f to %.0f kW", stuckMinuteWind[0], stuckLow, stuckHigh));
        chart.write();
    }
}
