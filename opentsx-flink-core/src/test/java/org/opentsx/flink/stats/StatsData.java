package org.opentsx.flink.stats;

import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.StatsEngine;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Simulated wind-farm episodes and the statistics a single {@link StatsEngine} computes from them. */
final class StatsData {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Comparator<SeriesStats> ORDER = Comparator.comparing(SeriesStats::getSeriesId)
            .thenComparing(SeriesStats::getWindowType)
            .thenComparing(SeriesStats::getWindowStart)
            .thenComparing(SeriesStats::getWindowEnd);

    private StatsData() {
    }

    /** One park with the given turbines, 1 Hz values, 10-minute episodes. */
    static List<Episode> episodes(String name, Duration duration, int turbines) {
        Scenario s = new Scenario(name, 5, START, duration, Duration.ofSeconds(1), Duration.ofMinutes(10),
                new Scenario.ParkSpec("p1", name, 54, 13, "GENERIC-3MW", 1, turbines, 5, 7, 0.05),
                Scenario.WindSpec.DEFAULT, List.of(), List.of());
        List<Episode> out = new ArrayList<>();
        new WindparkSimulator(s, ChannelCatalog.standard()).run(out::add);
        return out;
    }

    static List<StatsInput> inputs(List<Episode> episodes) {
        OpenTsxAvro avro = new OpenTsxAvro();
        return episodes.stream()
                .map(e -> new StatsInput(e.getSeries().getSeriesId(), true, avro.encode(e)))
                .toList();
    }

    /** What the KafScale processor computes in a bounded run: every closed window, then {@link StatsEngine#flush()}. */
    static List<SeriesStats> direct(List<Episode> episodes, EngineSpec spec) {
        StatsEngine engine = spec.newEngine();
        List<SeriesStats> out = new ArrayList<>();
        episodes.forEach(e -> out.addAll(engine.onEpisode(e)));
        out.addAll(engine.flush());
        return sorted(out);
    }

    static List<SeriesStats> decode(Collection<StatsRecord> records) {
        OpenTsxAvro avro = new OpenTsxAvro();
        return sorted(records.stream().map(r -> avro.decode(r.payload, SeriesStats.class)).toList());
    }

    static List<SeriesStats> sorted(Collection<SeriesStats> stats) {
        return stats.stream().sorted(ORDER).toList();
    }
}
