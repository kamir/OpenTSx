package org.opentsx.openmetadata;

import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The park the publisher tests publish: 3 turbines, 3 hours at 1 Hz, a stuck anemometer at T02 from minute 60 to 90
 * and a power outage at T01 from minute 120 to 135, with the statistics of one {@link StatsEngine} over it.
 */
final class SimulatedPark {

    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    static final Duration DURATION = Duration.ofHours(3);
    static final ChannelCatalog CATALOG = ChannelCatalog.standard();

    final Park park;
    final List<SeriesStats> stats;

    private SimulatedPark(Park park, List<SeriesStats> stats) {
        this.park = park;
        this.stats = stats;
    }

    static SimulatedPark simulate() {
        Scenario scenario = new Scenario("om", 11, START, DURATION, Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("ostsee-1", "Ostsee 1", 54.5, 13.6, "GENERIC-3MW", 1, 3, 5, 7, 0.05),
                new Scenario.WindSpec(2.0, 10.0, 0.10, 240, 15, 0.1, 10), List.of(),
                List.of(new Scenario.SensorFault(Scenario.SensorFaultType.STUCK, "T02", "wind.met.wind_speed",
                                Duration.ofHours(1), Duration.ofMinutes(30), null, null, null),
                        new Scenario.SensorFault(Scenario.SensorFaultType.GAP, "T01", "wind.turbine.power_active",
                                Duration.ofHours(2), Duration.ofMinutes(15), null, null, null)));
        WindparkSimulator sim = new WindparkSimulator(scenario, CATALOG);
        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults());
        List<SeriesStats> out = new ArrayList<>();
        sim.run(e -> out.addAll(engine.onEpisode(e)));
        out.addAll(engine.flush());
        return new SimulatedPark(sim.park(), out);
    }

    WindparkMetadataPublisher publisher(OmApi api) {
        return new WindparkMetadataPublisher(api, CATALOG, park, new TopicLayout("wind"),
                WindparkMetadataPublisher.Settings.defaults());
    }
}
