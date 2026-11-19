package org.opentsx.windpark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.Summaries;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.sim.GroundTruth;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.SimulationReport;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private final ChannelCatalog catalog = ChannelCatalog.standard();

    private static Scenario scenario(String name, Duration duration, Scenario.ParkSpec park, Scenario.WindSpec wind,
                                     List<Scenario.Operation> ops, List<Scenario.SensorFault> faults, Duration sample) {
        return new Scenario(name, 7, START, duration, sample, Duration.ofMinutes(10), park, wind, ops, faults);
    }

    private static Scenario.ParkSpec row(int turbines) {
        return new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW", 1, turbines, 5, 7, 0.05);
    }

    private static List<Episode> run(WindparkSimulator sim, List<SimulationReport> report) {
        List<Episode> out = new ArrayList<>();
        report.add(sim.run(out::add));
        return out;
    }

    private static List<Episode> of(List<Episode> eps, String metric, String turbine) {
        return eps.stream().filter(e -> e.getSeries().getMetric().equals(metric)
                && e.getSeries().getTags().get("turbine").equals(turbine)).toList();
    }

    private static double[] concat(List<Episode> eps) {
        return eps.stream().flatMapToDouble(e -> Arrays.stream(Episodes.values(e))).toArray();
    }

    @Test
    void exampleScenarioLoadsAndIsDeterministic() throws Exception {
        Scenario full = Scenario.load(Path.of("scenarios/ostsee-demo.yaml"));
        assertEquals(10, full.park().rows() * full.park().columns());
        // a shortened copy keeps the test fast; identical seeds must give identical episodes, ids included
        Scenario s = new Scenario(full.name(), full.seed(), full.start(), Duration.ofMinutes(20), full.sampleInterval(),
                full.episodeDuration(), full.park(), full.wind(), full.operations(), full.sensorFaults());
        List<SimulationReport> r = new ArrayList<>();
        List<Episode> a = run(new WindparkSimulator(s, catalog), r);
        List<Episode> b = run(new WindparkSimulator(s, catalog), r);
        assertEquals(10 * 10 * 2, a.size()); // turbines x channels x windows
        assertEquals(a, b);
        // curtailment of all 10 turbines + 1 fault + 1 maintenance
        assertEquals(12, r.get(0).groundTruth().stream().filter(g -> g.category().equals("operation")).count());
        assertEquals(5, r.get(0).groundTruth().stream().filter(g -> g.category().equals("sensor-fault")).count());
    }

    @Test
    void episodesAreTenMinuteScadaBlocksWithMatchingSummaries() {
        List<SimulationReport> r = new ArrayList<>();
        List<Episode> eps = run(new WindparkSimulator(scenario("blocks", Duration.ofMinutes(30), row(1),
                Scenario.WindSpec.DEFAULT, List.of(), List.of(), Duration.ofSeconds(1)), catalog), r);
        Episode e = of(eps, "wind.turbine.power_active", "T01").get(1);
        assertEquals(600, e.getCount());
        assertEquals(Micros.of(START) + 600_000_000L, Micros.of(e.getTStart()));
        assertEquals("FIXED_DURATION", e.getSegmentation().getStrategy().name());
        double mean = Arrays.stream(Episodes.values(e)).average().orElseThrow();
        assertEquals(mean, e.getSummary().getMean(), 1e-6);      // the episode summary IS the 10-min SCADA mean
        assertEquals(Summaries.of(Episodes.values(e)), e.getSummary());
        assertEquals("p1", e.getSeries().getTags().get("park"));
        assertEquals("kW", e.getSeries().getUnit());
    }

    @Test
    void powerFollowsThePowerCurveAndStaysWithinRating() {
        TurbineType t = TurbineType.GENERIC_3MW;
        List<SimulationReport> r = new ArrayList<>();
        List<Episode> eps = run(new WindparkSimulator(scenario("curve", Duration.ofHours(24), row(1),
                new Scenario.WindSpec(1.8, 11, 0.12, 270, 10, 0.1, 10), List.of(), List.of(), Duration.ofSeconds(5)), catalog), r);
        double[] wind = concat(of(eps, "wind.met.wind_speed", "T01"));
        double[] power = concat(of(eps, "wind.turbine.power_active", "T01"));
        double[] state = concat(of(eps, "wind.turbine.state", "T01"));
        double maxP = Arrays.stream(power).max().orElseThrow();
        assertTrue(maxP <= t.ratedPowerKw() + 0.1, "power above rating: " + maxP);
        assertTrue(Arrays.stream(power).min().orElseThrow() >= 0);
        // binned power curve: higher wind -> more power
        double low = binMean(wind, power, 3.5, 7);
        double mid = binMean(wind, power, 8, 11);
        double high = binMean(wind, power, 13, 22);
        assertTrue(low < mid && mid < high, low + " < " + mid + " < " + high);
        assertTrue(high > 0.85 * t.ratedPowerKw(), "near rated above rated wind: " + high);
        for (int i = 0; i < wind.length; i++) {
            if (state[i] == 2) {      // idle
                assertTrue(wind[i] < t.cutInMs() + 1e-9);
            }
        }
    }

    @Test
    void wakesReduceWindDownstreamDependingOnDirection() {
        List<SimulationReport> west = new ArrayList<>();
        run(new WindparkSimulator(scenario("west", Duration.ofHours(1), row(5),
                new Scenario.WindSpec(3, 10, 0.08, 270, 2, 0, 10), List.of(), List.of(), Duration.ofSeconds(2)), catalog), west);
        var w = west.get(0).meanWindMs();
        assertTrue(w.get("T01") > w.get("T02") && w.get("T02") > w.get("T05") * 0.98, "westerly: T01 upstream " + w);
        assertTrue(w.get("T05") < 0.9 * w.get("T01"), "deep-array deficit expected: " + w);

        List<SimulationReport> east = new ArrayList<>();
        run(new WindparkSimulator(scenario("east", Duration.ofHours(1), row(5),
                new Scenario.WindSpec(3, 10, 0.08, 90, 2, 0, 10), List.of(), List.of(), Duration.ofSeconds(2)), catalog), east);
        var e = east.get(0).meanWindMs();
        assertTrue(e.get("T05") > e.get("T01"), "easterly: T05 upstream " + e);
    }

    @Test
    void operationsChangeStateAndPower() {
        List<Scenario.Operation> ops = List.of(
                new Scenario.Operation(Scenario.OperationType.CURTAILMENT, List.of("T01"), Duration.ofMinutes(20), Duration.ofMinutes(20), 800.0),
                new Scenario.Operation(Scenario.OperationType.FAULT, List.of("T02"), Duration.ofMinutes(20), Duration.ofMinutes(20), null),
                new Scenario.Operation(Scenario.OperationType.MAINTENANCE, List.of("T03"), Duration.ofMinutes(20), Duration.ofMinutes(20), null));
        List<SimulationReport> r = new ArrayList<>();
        List<Episode> eps = run(new WindparkSimulator(scenario("ops", Duration.ofHours(1), row(3),
                new Scenario.WindSpec(3, 13, 0.08, 0, 2, 0, 10), ops, List.of(), Duration.ofSeconds(1)), catalog), r);
        // window 30-40 min: fully inside the events, lags have settled
        Episode curtailedP = of(eps, "wind.turbine.power_active", "T01").get(3);
        assertTrue(curtailedP.getSummary().getMax() <= 800 + 1, "curtailed max " + curtailedP.getSummary().getMax());
        assertEquals("CURTAILMENT", curtailedP.getLabels().get("gt.operation"));
        assertTrue(Arrays.stream(Episodes.values(of(eps, "wind.turbine.state", "T01").get(3))).anyMatch(s -> s == 3));
        assertEquals(0.0, of(eps, "wind.turbine.power_active", "T02").get(3).getSummary().getMax(), 0.5);
        assertTrue(Arrays.stream(Episodes.values(of(eps, "wind.turbine.state", "T02").get(3))).allMatch(s -> s == 4));
        assertTrue(Arrays.stream(Episodes.values(of(eps, "wind.turbine.state", "T03").get(3))).allMatch(s -> s == 5));
        assertTrue(of(eps, "wind.rotor.pitch_angle", "T02").get(3).getSummary().getMin() > 85);
        assertEquals(null, of(eps, "wind.turbine.power_active", "T01").get(0).getLabels().get("gt.operation"));
        assertTrue(r.get(0).energyKwh().get("T02") < r.get(0).energyKwh().get("T01"));
    }

    @Test
    void sensorFaultsAreInjectedExactlyWhereGroundTruthSaysSo() {
        Duration at = Duration.ofMinutes(10);
        Duration len = Duration.ofMinutes(10);
        List<Scenario.SensorFault> faults = List.of(
                new Scenario.SensorFault(Scenario.SensorFaultType.STUCK, "T01", "wind.met.wind_speed", at, len, null, null, null),
                new Scenario.SensorFault(Scenario.SensorFaultType.GAP, "T01", "wind.turbine.power_active", at, len, null, null, null),
                new Scenario.SensorFault(Scenario.SensorFaultType.SPIKES, "T01", "wind.rotor.speed", at, len, null, 6.0, 0.05),
                new Scenario.SensorFault(Scenario.SensorFaultType.DRIFT, "T01", "wind.generator.temperature", at, len, 30.0, null, null),
                new Scenario.SensorFault(Scenario.SensorFaultType.OFFSET, "T01", "wind.met.ambient_temperature", at, len, null, 5.0, null));
        Scenario clean = scenario("faults", Duration.ofMinutes(30), row(1), Scenario.WindSpec.DEFAULT, List.of(), List.of(), Duration.ofSeconds(1));
        Scenario faulty = scenario("faults", Duration.ofMinutes(30), row(1), Scenario.WindSpec.DEFAULT, List.of(), faults, Duration.ofSeconds(1));
        List<SimulationReport> r = new ArrayList<>();
        List<Episode> ref = run(new WindparkSimulator(clean, catalog), r);
        List<Episode> eps = run(new WindparkSimulator(faulty, catalog), r);

        Episode stuck = of(eps, "wind.met.wind_speed", "T01").get(1);
        assertEquals(stuck.getSummary().getMin(), stuck.getSummary().getMax(), "stuck sensor is constant");
        assertEquals("STUCK", stuck.getLabels().get("gt.sensor_fault"));
        assertTrue(of(eps, "wind.met.wind_speed", "T01").get(0).getSummary().getM2() > 0);

        Episode gap = of(eps, "wind.turbine.power_active", "T01").get(1);
        assertEquals(600, gap.getSummary().getNanCount());
        byte[] q = new byte[600];
        gap.getQuality().duplicate().get(q);
        assertTrue(new String(q, java.nio.charset.StandardCharsets.ISO_8859_1).chars().allMatch(c -> c == 2), "quality MISSING");

        double[] spikes = Episodes.values(of(eps, "wind.rotor.speed", "T01").get(1));
        double[] clean1 = Episodes.values(of(ref, "wind.rotor.speed", "T01").get(1));
        long changed = 0;
        for (int i = 0; i < spikes.length; i++) {
            if (Math.abs(spikes[i] - clean1[i]) > 5) {
                changed++;
            }
        }
        assertTrue(changed > 10 && changed < 60, "about 5 % spikes: " + changed);

        double drift = of(eps, "wind.generator.temperature", "T01").get(1).getSummary().getMean()
                - of(ref, "wind.generator.temperature", "T01").get(1).getSummary().getMean();
        assertEquals(2.5, drift, 0.2); // 30 K/h over 10 min, mean of the ramp = 2.5 K
        double offset = of(eps, "wind.met.ambient_temperature", "T01").get(1).getSummary().getMean()
                - of(ref, "wind.met.ambient_temperature", "T01").get(1).getSummary().getMean();
        assertEquals(5.0, offset, 0.05);

        // outside the fault window the faulty run equals the clean run
        assertEquals(Arrays.toString(Episodes.values(of(ref, "wind.met.wind_speed", "T01").get(2))),
                Arrays.toString(Episodes.values(of(eps, "wind.met.wind_speed", "T01").get(2))));
        List<GroundTruth> gt = r.get(1).groundTruth();
        assertEquals(5, gt.size());
        assertTrue(gt.stream().allMatch(g -> g.toMicros() - g.fromMicros() == 600_000_000L));
    }

    @Test
    void aCurtailmentWithoutLimitFailsToLoadAndNamesTheOperation(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("no-limit.yaml");
        Files.writeString(file, Files.readString(Path.of("scenarios/ostsee-demo.yaml"))
                .replace("duration: PT40M, limitKw: 1500 }", "duration: PT40M }"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Scenario.load(file));
        assertEquals(file + ": CURTAILMENT operation at PT1H: limitKw missing; set limitKw to the power limit in kW, 0 or more",
                e.getMessage());
    }

    @Test
    void operationsSensorFaultsAndTheParkRequireTheirParameters() {
        Duration at = Duration.ofMinutes(20);
        assertEquals("FAULT operation at PT20M: limitKw belongs to CURTAILMENT only; remove it",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.Operation(Scenario.OperationType.FAULT,
                        List.of("T01"), at, at, 800.0)).getMessage());
        assertEquals("CURTAILMENT operation at PT20M: limitKw -1.0 is negative; set limitKw to the power limit in kW, 0 or more",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.Operation(Scenario.OperationType.CURTAILMENT,
                        List.of("T01"), at, at, -1.0)).getMessage());
        assertEquals("MAINTENANCE operation: offset missing; set offset to the time after start, such as PT1H",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.Operation(Scenario.OperationType.MAINTENANCE,
                        List.of("T01"), null, at, null)).getMessage());
        assertEquals("FAULT operation at PT20M: duration missing; set duration to a positive length, such as PT30M",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.Operation(Scenario.OperationType.FAULT,
                        List.of("T01"), at, null, null)).getMessage());
        assertEquals("SPIKES sensor fault of T01 wind.rotor.speed at PT20M: probability missing; "
                        + "set probability to the share of samples with a spike, above 0 and at most 1",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.SensorFault(Scenario.SensorFaultType.SPIKES,
                        "T01", "wind.rotor.speed", at, at, null, 6.0, null)).getMessage());
        assertEquals("DRIFT sensor fault of T01 wind.generator.temperature at PT20M: ratePerHour missing; "
                        + "set ratePerHour to the change per hour in the unit of the channel",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.SensorFault(Scenario.SensorFaultType.DRIFT,
                        "T01", "wind.generator.temperature", at, at, null, null, null)).getMessage());
        assertEquals("OFFSET sensor fault of T01 wind.met.ambient_temperature at PT20M: magnitude missing; "
                        + "set magnitude to the offset in the unit of the channel",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.SensorFault(Scenario.SensorFaultType.OFFSET,
                        "T01", "wind.met.ambient_temperature", at, at, null, null, null)).getMessage());
        assertEquals("GAP sensor fault of T01 wind.turbine.power_active at PT20M: magnitude belongs to OFFSET and SPIKES only; "
                        + "remove it",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.SensorFault(Scenario.SensorFaultType.GAP,
                        "T01", "wind.turbine.power_active", at, at, null, 5.0, null)).getMessage());
        assertEquals("park: rows missing or below 1; set rows to 1 or more",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW",
                        0, 3, 5, 7, 0.05)).getMessage());
        assertEquals("park: columns missing or below 1; set columns to 1 or more",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW",
                        2, -1, 5, 7, 0.05)).getMessage());
        assertEquals("park: spacingRotorDiameters -3.0 is not allowed; "
                        + "set spacingRotorDiameters to a positive number of rotor diameters or leave it out for 5",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW",
                        1, 3, -3, 7, 0.05)).getMessage());
        assertEquals("park: rowSpacingRotorDiameters NaN is not allowed; "
                        + "set rowSpacingRotorDiameters to a positive number of rotor diameters or leave it out for 7",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW",
                        1, 3, 5, Double.NaN, 0.05)).getMessage());
        assertEquals("park: wakeDecay -0.1 is not allowed; set wakeDecay to a positive decay constant or leave it out for 0.05",
                assertThrows(IllegalArgumentException.class, () -> new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW",
                        1, 3, 5, 7, -0.1)).getMessage());
        assertEquals(new Scenario.ParkSpec("p1", "test", 54, 13, "GENERIC-3MW", 1, 3, 5, 7, 0.05),
                new Scenario.ParkSpec("p1", "test", 54, 13, null, 1, 3, 0, 0, 0));
    }

    @Test
    void aScenarioRefusesANonPositiveDurationSampleIntervalOrEpisodeDuration() {
        assertEquals("duration PT-1H is not positive; set duration to a positive length, such as PT6H",
                assertThrows(IllegalArgumentException.class, () -> new Scenario("s", 1, START, Duration.ofHours(-1),
                        null, null, row(1), null, null, null)).getMessage());
        assertEquals("sampleInterval PT0S is not positive; set sampleInterval to a positive length, such as PT1S",
                assertThrows(IllegalArgumentException.class, () -> new Scenario("s", 1, START, Duration.ofHours(1),
                        Duration.ZERO, null, row(1), null, null, null)).getMessage());
        assertEquals("sampleInterval PT-1S is not positive; set sampleInterval to a positive length, such as PT1S",
                assertThrows(IllegalArgumentException.class, () -> new Scenario("s", 1, START, Duration.ofHours(1),
                        Duration.ofSeconds(-1), null, row(1), null, null, null)).getMessage());
        assertEquals("episodeDuration PT0S is not positive; set episodeDuration to a positive length, such as PT10M",
                assertThrows(IllegalArgumentException.class, () -> new Scenario("s", 1, START, Duration.ofHours(1),
                        null, Duration.ZERO, row(1), null, null, null)).getMessage());
    }

    @Test
    void aSensorFaultOrOperationOnAnUnknownTurbineFailsWhenTheSimulatorIsBuilt() {
        Duration at = Duration.ofMinutes(10);
        Scenario fault = scenario("refs", Duration.ofMinutes(30), row(2), Scenario.WindSpec.DEFAULT, List.of(),
                List.of(new Scenario.SensorFault(Scenario.SensorFaultType.GAP, "T09", "wind.turbine.power_active", at, at,
                        null, null, null)), Duration.ofSeconds(1));
        assertEquals("GAP sensor fault of T09 wind.turbine.power_active at PT10M: unknown turbine T09; "
                        + "expected a turbine of park p1, T01 to T02",
                assertThrows(IllegalArgumentException.class, () -> new WindparkSimulator(fault, catalog)).getMessage());
        Scenario op = scenario("refs", Duration.ofMinutes(30), row(2), Scenario.WindSpec.DEFAULT,
                List.of(new Scenario.Operation(Scenario.OperationType.FAULT, List.of("T1"), at, at, null)), List.of(),
                Duration.ofSeconds(1));
        assertEquals("FAULT operation at PT10M: unknown turbine T1; expected a turbine of park p1, T01 to T02",
                assertThrows(IllegalArgumentException.class, () -> new WindparkSimulator(op, catalog)).getMessage());
        Scenario channel = scenario("refs", Duration.ofMinutes(30), row(2), Scenario.WindSpec.DEFAULT, List.of(),
                List.of(new Scenario.SensorFault(Scenario.SensorFaultType.GAP, "T01", "wind.power", at, at, null, null, null)),
                Duration.ofSeconds(1));
        assertEquals("GAP sensor fault of T01 wind.power at PT10M: unknown channel wind.power; expected a metric of catalog "
                        + "opentsx-windpark-datapoints such as wind.met.wind_speed",
                assertThrows(IllegalArgumentException.class, () -> new WindparkSimulator(channel, catalog)).getMessage());
    }

    @Test
    void gapsCarryTheMissingCodeOfTheCatalog() {
        ChannelCatalog custom = new ChannelCatalog("custom", "1.0.0", catalog.iecEdition(),
                Map.of(0, "GOOD", 7, "IMPLAUSIBLE", 9, "MISSING"), catalog.channels(), catalog.sources());
        List<Scenario.SensorFault> gap = List.of(new Scenario.SensorFault(Scenario.SensorFaultType.GAP, "T01",
                "wind.turbine.power_active", Duration.ZERO, Duration.ofMinutes(10), null, null, null));
        List<Episode> eps = run(new WindparkSimulator(scenario("codes", Duration.ofMinutes(10), row(1),
                Scenario.WindSpec.DEFAULT, List.of(), gap, Duration.ofSeconds(1)), custom), new ArrayList<>());
        byte[] q = new byte[600];
        of(eps, "wind.turbine.power_active", "T01").get(0).getQuality().duplicate().get(q);
        for (byte b : q) {
            assertEquals(9, b);
        }
    }

    @Test
    void stateTimeSumsToTheSimulatedSpanAtEveryStep() {
        for (Duration step : List.of(Duration.ofMillis(200), Duration.ofMillis(1500))) {
            SimulationReport r = new WindparkSimulator(scenario("steps", Duration.ofMinutes(30), row(2),
                    Scenario.WindSpec.DEFAULT, List.of(), List.of(), step), catalog).run(e -> { });
            for (Map<Integer, ? extends Number> states : r.stateSeconds().values()) {
                assertEquals(1800.0, states.values().stream().mapToDouble(Number::doubleValue).sum(), 1e-6, step + ": " + states);
            }
        }
    }

    @Test
    void longRunMeanWindFollowsTheWeibullDistribution() {
        double k = 2.0;
        double c = 9.0;
        List<SimulationReport> r = new ArrayList<>();
        run(new WindparkSimulator(scenario("weibull", Duration.ofDays(30), row(1),
                new Scenario.WindSpec(k, c, 0.1, 270, 5, 0, 10), List.of(), List.of(), Duration.ofSeconds(60)), catalog), r);
        double expected = c * gamma(1 + 1 / k); // 7.98 m/s
        assertEquals(expected, r.get(0).meanWindMs().get("T01"), 0.15 * expected);
    }

    private static double binMean(double[] x, double[] y, double lo, double hi) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i] >= lo && x[i] < hi) {
                sum += y[i];
                n++;
            }
        }
        assertTrue(n > 30, "not enough samples in bin " + lo + "-" + hi + ": " + n);
        return sum / n;
    }

    /** Lanczos approximation. */
    private static double gamma(double x) {
        double[] g = {676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059,
                12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        x -= 1;
        double a = 0.99999999999980993;
        double t = x + 7.5;
        for (int i = 0; i < g.length; i++) {
            a += g[i] / (x + i + 1);
        }
        return Math.sqrt(2 * Math.PI) * Math.pow(t, x + 0.5) * Math.exp(-t) * a;
    }

}
