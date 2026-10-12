package org.opentsx.stats;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.ChannelDef;
import org.opentsx.windpark.catalog.ChannelKind;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statistics + data-quality checks find the sensor faults the simulator injected (ground truth). */
class WindparkDataQualityTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void stuckSensorAndOutageAreFoundInExactlyTheRightWindows() {
        ChannelCatalog catalog = ChannelCatalog.standard();
        Scenario scenario = new Scenario("dq", 11, START, Duration.ofHours(3), Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", "dq", 54, 13, "GENERIC-3MW", 1, 3, 5, 7, 0.05),
                new Scenario.WindSpec(2.0, 10.0, 0.10, 240, 15, 0.1, 10), List.of(),
                List.of(new Scenario.SensorFault(Scenario.SensorFaultType.STUCK, "T02", "wind.met.wind_speed",
                                Duration.ofHours(1), Duration.ofMinutes(30), null, null, null),
                        new Scenario.SensorFault(Scenario.SensorFaultType.GAP, "T01", "wind.turbine.power_active",
                                Duration.ofHours(2), Duration.ofMinutes(15), null, null, null)));
        WindparkSimulator sim = new WindparkSimulator(scenario, catalog);

        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withWindows(Duration.ofMinutes(10)));
        List<SeriesStats> stats = new ArrayList<>();
        Map<String, String> metricOf = new HashMap<>();
        Map<String, String> turbineOf = new HashMap<>();
        sim.run(e -> {
            metricOf.put(e.getSeries().getSeriesId(), e.getSeries().getMetric());
            turbineOf.put(e.getSeries().getSeriesId(), e.getSeries().getTags().get("turbine"));
            stats.addAll(engine.onEpisode(e));
        });
        stats.addAll(engine.flush());

        List<DataQuality.Result> failed = new ArrayList<>();
        for (SeriesStats s : stats) {
            if (!s.getWindowType().equals("PT10M")) {
                continue;
            }
            ChannelDef ch = catalog.channel(metricOf.get(s.getSeriesId()));
            DataQuality.Expectation exp = DataQuality.Expectation.of(ch.min(), ch.max(), ch.sampling());
            if (ch.kind() != ChannelKind.ANALOG || !ch.metric().startsWith("wind.met")) {
                exp = exp.withoutFlatline();     // power, speeds and states are legitimately constant at times
            }
            DataQuality.evaluate(s, exp).stream().filter(DataQuality.Result::failed).forEach(failed::add);
        }

        Set<String> flat = failed.stream().filter(r -> r.check() == DataQuality.Check.FLATLINE)
                .map(r -> turbineOf.get(r.seriesId()) + " " + metricOf.get(r.seriesId()) + " " + minute(r.windowStartMicros()))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(Set.of("T02 wind.met.wind_speed 60", "T02 wind.met.wind_speed 70", "T02 wind.met.wind_speed 80"), flat);

        Set<String> incomplete = failed.stream().filter(r -> r.check() == DataQuality.Check.COMPLETENESS)
                .map(r -> turbineOf.get(r.seriesId()) + " " + metricOf.get(r.seriesId()) + " " + minute(r.windowStartMicros()))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(Set.of("T01 wind.turbine.power_active 120", "T01 wind.turbine.power_active 130"), incomplete);

        assertTrue(failed.stream().noneMatch(r -> r.check() == DataQuality.Check.PLAUSIBLE_RANGE), failed::toString);
        assertTrue(failed.stream().noneMatch(r -> r.check() == DataQuality.Check.GAPS), "regular 1 Hz data has no timestamp gaps");
    }

    private static long minute(long micros) {
        return (micros - Micros.of(START)) / 60_000_000L;
    }
}
